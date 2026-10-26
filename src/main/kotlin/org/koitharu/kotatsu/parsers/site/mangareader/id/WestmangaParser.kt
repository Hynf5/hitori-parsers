package org.koitharu.kotatsu.parsers.site.mangareader.id

import androidx.collection.ArrayMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.util.*

@MangaSourceParser("WESTMANGA", "WestManga", "id")
internal class WestmangaParser(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.WESTMANGA, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("westmanga.top")

    // Pisahkan domain frontend dan API agar Referer header valid di mata Cloudflare
    private val frontendDomain: String
        get() = domain.removePrefix("v1.").removePrefix("api.")

    private val apiDomain: String
        get() = if (domain.startsWith("v1.")) domain else "v1.$frontendDomain"

    private var genresCache: Set<MangaTag>? = null
    private val mutex = Mutex()

    private val visitTokenMutex = Mutex()
    private var visitToken: String? = null
    private var visitTokenExpiresAt: Long = 0L

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL,
        SortOrder.NEWEST,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = true,
            isSearchSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchGenres(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
        ),
    )

    private suspend fun getVisitToken(forceRefresh: Boolean = false): String? = visitTokenMutex.withLock {
        val now = System.currentTimeMillis()
        if (!forceRefresh && visitToken != null && now < visitTokenExpiresAt - (5 * 60 * 1000)) {
            return@withLock visitToken
        }

        val url = "https://$apiDomain/api/visit-token".toHttpUrl()
        try {
            // Header AJAX super ketat untuk membypass Cloudflare WAF
            val headers = Headers.Builder()
                .add("User-Agent", config[userAgentKey])
                .add("Accept", "application/json, text/plain, */*")
                .add("X-Requested-With", "XMLHttpRequest")
                .add("Origin", "https://$frontendDomain")
                .add("Referer", "https://$frontendDomain/")
                .build()
            
            val response = webClient.httpGet(url, headers).parseJson()
            if (response.optBoolean("status", false)) {
                visitToken = response.getStringOrNull("token")
                val expiresAt = response.optLong("expiresAt", 0L)
                visitTokenExpiresAt = if (expiresAt > 0L) {
                    expiresAt * 1000L
                } else {
                    now + (60 * 60 * 1000L)
                }
            } else if (forceRefresh) {
                visitToken = null
                visitTokenExpiresAt = 0L
            }
        } catch (e: Exception) {
            if (forceRefresh) {
                visitToken = null
                visitTokenExpiresAt = 0L
            }
        }
        return@withLock visitToken
    }

    private suspend fun createApiHeaders(): Headers {
        val builder = Headers.Builder()
            .add("User-Agent", config[userAgentKey])
            .add("Accept", "application/json, text/plain, */*")
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Origin", "https://$frontendDomain")
            .add("Referer", "https://$frontendDomain/")

        getVisitToken()?.let {
            builder.add("X-WM-Visit", it)
        }
        return builder.build()
    }

    private suspend fun apiGet(url: okhttp3.HttpUrl): org.json.JSONObject {
        return try {
            webClient.httpGet(url, createApiHeaders()).parseJson()
        } catch (e: Exception) {
            getVisitToken(forceRefresh = true)
            webClient.httpGet(url, createApiHeaders()).parseJson()
        }
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = "https://$apiDomain/api/contents".toHttpUrl().newBuilder().apply {
            if (!filter.query.isNullOrEmpty()) {
                addQueryParameter("q", filter.query)
            }
            addQueryParameter("page", page.toString())
            addQueryParameter("per_page", "20")
            addQueryParameter("type", "Comic")

            when (order) {
                SortOrder.UPDATED -> addQueryParameter("sort", "updated_at")
                SortOrder.POPULARITY -> addQueryParameter("sort", "views")
                SortOrder.ALPHABETICAL -> addQueryParameter("sort", "title")
                SortOrder.NEWEST -> addQueryParameter("sort", "created_at")
                else -> {}
            }

            filter.tags.forEach { tag ->
                addQueryParameter("genre[]", tag.key)
            }

            filter.states.oneOrThrowIfMany()?.let {
                addQueryParameter("status", when (it) {
                    MangaState.ONGOING -> "ongoing"
                    MangaState.FINISHED -> "completed"
                    MangaState.PAUSED -> "hiatus"
                    else -> return@let
                })
            }

            filter.types.oneOrThrowIfMany()?.let {
                addQueryParameter("country", when (it) {
                    ContentType.MANGA -> "JP"
                    ContentType.MANHWA -> "KR"
                    ContentType.MANHUA -> "CN"
                    else -> return@let
                })
            }
        }.build()

        val response = apiGet(url)
        val mangaArray = response.optJSONArray("data") ?: return emptyList()

        return mangaArray.mapJSON { jo ->
            val slug = jo.getString("slug")
            Manga(
                id = generateUid(slug),
                title = jo.getString("title"),
                altTitles = emptySet(),
                url = "/manga/$slug/",
                publicUrl = "https://$frontendDomain/comic/$slug",
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = jo.getString("cover"),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.removeSurrounding("/manga/", "/")
        val url = "https://$apiDomain/api/comic/$slug".toHttpUrl()

        val response = apiGet(url)
        val data = response.getJSONObject("data")

        val tags = buildSet {
            when (data.getStringOrNull("country_id") ?: data.getStringOrNull("country")) {
                "JP" -> add(MangaTag("Manga", "manga", source))
                "CN" -> add(MangaTag("Manhua", "manhua", source))
                "KR" -> add(MangaTag("Manhwa", "manhwa", source))
            }
            if (data.optBoolean("color", false)) {
                add(MangaTag("Colored", "colored", source))
            }
            data.optJSONArray("genres")?.let { genres ->
                for (i in 0 until genres.length()) {
                    val genre = genres.getJSONObject(i)
                    add(MangaTag(
                        title = genre.getString("name"),
                        key = genre.getInt("id").toString(),
                        source = source,
                    ))
                }
            }
        }

        val description = buildString {
            data.getStringOrNull("sinopsis")?.let { synopsis ->
                append(org.jsoup.Jsoup.parseBodyFragment(synopsis).wholeText().trim())
            }
            data.getStringOrNull("alternative_name")?.let { alt ->
                append("\n\nAlternative Name: ")
                append(alt.trim())
            }
        }

        val state = when (data.getStringOrNull("status")) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            "hiatus" -> MangaState.PAUSED
            else -> null
        }

        val chapters = data.optJSONArray("chapters")?.mapJSON { chapterObj ->
            val chapterSlug = chapterObj.getString("slug")
            val updatedAt = chapterObj.optJSONObject("updated_at")?.getLongOrDefault("time", 0L)
                ?: chapterObj.getLongOrDefault("updated_at", 0L)
            MangaChapter(
                id = generateUid(chapterSlug),
                url = "/$chapterSlug/",
                title = "Chapter ${chapterObj.getString("number")}",
                number = chapterObj.getString("number").toFloatOrNull() ?: 0f,
                volume = 0,
                branch = null,
                uploadDate = updatedAt * 1000,
                scanlator = null,
                source = source,
            )
        } ?: emptyList()

        return manga.copy(
            title = data.getString("title"),
            coverUrl = data.getString("cover"),
            tags = tags,
            authors = setOfNotNull(data.getStringOrNull("author")),
            description = description.takeIf { it.isNotEmpty() },
            state = state,
            chapters = chapters.reversed(),
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val slug = chapter.url.removeSurrounding("/", "/")
        val url = "https://$apiDomain/api/v/$slug".toHttpUrl()

        val response = apiGet(url)
        val images = response.optJSONObject("data")?.getJSONArray("images")
            ?: response.getJSONArray("images")

        val pages = ArrayList<MangaPage>(images.length())
        for (i in 0 until images.length()) {
            var imageUrl = images.getString(i)
            // Handle relative image URLs automatically
            if (!imageUrl.startsWith("http")) {
                imageUrl = "https://$frontendDomain$imageUrl"
            }
            pages.add(
                MangaPage(
                    id = generateUid(imageUrl),
                    url = imageUrl,
                    preview = null,
                    source = source,
                )
            )
        }
        return pages
    }

    private suspend fun fetchGenres(): Set<MangaTag> = mutex.withLock {
        genresCache?.let { return@withLock it }

        val url = "https://$apiDomain/api/contents/genres".toHttpUrl()
        val response = try {
            apiGet(url)
        } catch (e: Exception) {
            return@withLock emptySet()
        }
        
        val genresArray = response.optJSONArray("data") ?: return@withLock emptySet()

        val genres = genresArray.mapJSONToSet { genreObj ->
            MangaTag(
                title = genreObj.getString("name"),
                key = genreObj.getInt("id").toString(),
                source = source,
            )
        }

        genresCache = genres
        return@withLock genres
    }
}
