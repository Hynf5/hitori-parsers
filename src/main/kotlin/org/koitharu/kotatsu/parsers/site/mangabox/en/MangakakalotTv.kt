package org.koitharu.kotatsu.parsers.site.mangabox.en

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQuery
import org.koitharu.kotatsu.parsers.model.search.MangaSearchQueryCapabilities
import org.koitharu.kotatsu.parsers.model.search.QueryCriteria.Include
import org.koitharu.kotatsu.parsers.model.search.QueryCriteria.Match
import org.koitharu.kotatsu.parsers.model.search.SearchCapability
import org.koitharu.kotatsu.parsers.model.search.SearchableField
import org.koitharu.kotatsu.parsers.model.search.SearchableField.*
import org.koitharu.kotatsu.parsers.site.mangabox.MangaboxParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANGAKAKALOTTV", "Mangakakalot.tv", "en")
internal class MangakakalotTv(context: MangaLoaderContext) :
    MangaboxParser(context, MangaParserSource.MANGAKAKALOTTV) {

    override val configKeyDomain = ConfigKey.Domain("www.mangakakalot.gg")

    // We override getListPage, so these are not directly used for URL generation in the base class
    override val searchUrl = ""
    override val listUrl = ""

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        if (originalRequest.method == "GET" || originalRequest.method == "POST") {
            val newRequest = originalRequest.newBuilder()
                .removeHeader("Content-Encoding")
                .removeHeader("Accept-Encoding")
                .build()
            return chain.proceed(newRequest)
        }
        return chain.proceed(originalRequest)
    }

    override val searchQueryCapabilities: MangaSearchQueryCapabilities
        get() = MangaSearchQueryCapabilities(
            SearchCapability(
                field = TAG,
                criteriaTypes = setOf(Include::class),
                isMultiple = false,
            ),
            SearchCapability(
                field = TITLE_NAME,
                criteriaTypes = setOf(Match::class),
                isMultiple = false,
                isExclusive = true,
            ),
            SearchCapability(
                field = STATE,
                criteriaTypes = setOf(Include::class),
                isMultiple = false,
            ),
        )

    override suspend fun getListPage(query: MangaSearchQuery, page: Int): List<Manga> {
        val titleQuery = query.criteria.filterIsInstance<Match<*>>()
            .firstOrNull { it.field == TITLE_NAME }?.value as? String

        val url = if (!titleQuery.isNullOrBlank()) {
            "https://$domain/search/story/${titleQuery.urlEncoded()}?page=$page"
        } else {
            val tagCriterion = query.criteria.filterIsInstance<Include<*>>()
                .firstOrNull { it.field == TAG }

            val tagKey = (tagCriterion?.values?.firstOrNull() as? MangaTag)?.key
                ?: (tagCriterion?.values?.firstOrNull() as? String)

            val baseUrl = tagKey ?: "https://$domain/genre/all"

            val sortParam = when (query.order ?: SortOrder.UPDATED) {
                SortOrder.POPULARITY -> "topview"
                SortOrder.NEWEST -> "newest"
                else -> "latest"
            }

            val stateParam = query.criteria.filterIsInstance<Include<*>>()
                .firstOrNull { it.field == STATE }?.values?.firstOrNull()?.let {
                    when (it) {
                        MangaState.ONGOING -> "ongoing"
                        MangaState.FINISHED -> "completed"
                        else -> "all"
                    }
                } ?: "all"

            "$baseUrl?type=$sortParam&state=$stateParam&page=$page"
        }

        val doc = webClient.httpGet(url).parseHtml()

        var elements = doc.select("div.list-comic-item-wrap")
        if (elements.isEmpty()) {
            elements = doc.select("div.itemupdate")
        }
        if (elements.isEmpty()) {
            elements = doc.select("div.story_item")
        }

        return elements.map { div ->
            val coverLink = div.selectFirst("a.cover") ?: div.selectFirst("a")
            val href = coverLink?.attrAsRelativeUrl("href") ?: ""
            val img = coverLink?.selectFirst("img")
            val title = div.selectFirst("h3")?.text() ?: ""

            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                coverUrl = img?.attr("data-src")?.takeIf { it.isNotEmpty() } ?: img?.attr("src"),
                title = title,
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                source = source,
                contentRating = null,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val fullUrl = manga.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()
        val mangaSlug = manga.url.substringAfter("/manga/").trimEnd('/')
        val chaptersDeferred = async { fetchChaptersApi(mangaSlug) }

        val descElement = doc.selectFirst("div#contentBox")
        descElement?.select("h2")?.remove()
        val desc = descElement?.html()

        val stateDiv = doc.select(selectState).text().replace("Status : ", "")
        val state = stateDiv.let {
            when (it.lowercase()) {
                in ongoing -> MangaState.ONGOING
                in finished -> MangaState.FINISHED
                else -> null
            }
        }
        val alt = doc.body().select(selectAlt).text().replace("Alternative : ", "").nullIfEmpty()
        val author = doc.body().select(selectAut).eachText().joinToString().nullIfEmpty()

        manga.copy(
            tags = doc.select(selectTag).mapToSet { a ->
                MangaTag(
                    key = a.attrAsRelativeUrl("href").removePrefix("/"),
                    title = a.text().toTitleCase(),
                    source = source,
                )
            },
            description = desc,
            altTitles = setOfNotNull(alt),
            authors = setOfNotNull(author),
            state = state,
            chapters = chaptersDeferred.await(),
        )
    }

    private suspend fun fetchChaptersApi(mangaSlug: String): List<MangaChapter> {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val chapters = mutableListOf<MangaChapter>()
        var offset = 0
        val limit = 50

        while (true) {
            val url = "https://$domain/api/manga/$mangaSlug/chapters?limit=$limit&offset=$offset"
            val json = webClient.httpGet(url).parseJson()
            val chaptersArray = json.getJSONObject("data").getJSONArray("chapters")

            if (chaptersArray.length() == 0) break

            for (i in 0 until chaptersArray.length()) {
                val ch = chaptersArray.getJSONObject(i)
                val chapterSlug = ch.getString("chapter_slug")
                val chapterUrl = "/manga/$mangaSlug/$chapterSlug"

                val date = try {
                    dateFormat.parse(ch.getString("updated_at").substringBefore("."))?.time ?: 0L
                } catch (e: Exception) {
                    0L
                }

                chapters.add(
                    MangaChapter(
                        id = generateUid(chapterUrl),
                        title = ch.getString("chapter_name"),
                        number = ch.getDouble("chapter_num").toFloat(),
                        volume = 0,
                        url = chapterUrl,
                        scanlator = null,
                        uploadDate = date,
                        branch = null,
                        source = source,
                    ),
                )
            }

            if (chaptersArray.length() < limit) break
            offset += limit
        }

        return chapters.sortedBy { it.number }
    }

    override suspend fun fetchAvailableTags(): Set<MangaTag> {
        val doc = webClient.httpGet("https://$domain/genre/all").parseHtml()
        return doc.select("ul.tag.tag-name li a").mapNotNull { a ->
            val href = a.attrAsRelativeUrl("href")
            if (href.startsWith("genre/") && !href.contains("/all")) {
                MangaTag(
                    key = href.removePrefix("/"),
                    title = a.text(),
                    source = source,
                )
            } else null
        }.toSet()
    }
}
