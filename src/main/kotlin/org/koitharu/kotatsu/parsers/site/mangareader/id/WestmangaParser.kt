package org.koitharu.kotatsu.parsers.site.mangareader.id

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
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

	override val configKeyDomain = ConfigKey.Domain("westmanga.cc")

	private val apiDomain: String
		get() = "data.mantweh.online" 

	private var genresCache: Set<MangaTag>? = null
	private val mutex = Mutex()

	// Variabel untuk menyimpan (cache) visit token baru
	private val visitTokenMutex = Mutex()
	private var visitTokenCache: String? = null
	private var visitTokenExpiresAt = 0L

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
				publicUrl = "https://$domain/comic/$slug",
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
			val imageUrl = images.getString(i)
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
		val response = apiGet(url)
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

	/**
	 * Mengambil visit token baru. Jika forceRefresh true, abaikan cache lama.
	 */
	private suspend fun getVisitToken(forceRefresh: Boolean = false): String? = visitTokenMutex.withLock {
		val now = System.currentTimeMillis()
		
		// Gunakan cache jika belum kedaluwarsa (beri jarak 5 menit / 300,000 ms)
		if (!forceRefresh && visitTokenCache != null && now < visitTokenExpiresAt - 300_000L) {
			return@withLock visitTokenCache
		}

		val url = "https://$apiDomain/api/visit-token".toHttpUrl()

		try {
			val headers = Headers.Builder()
				.add("User-Agent", config[userAgentKey])
				.add("Referer", "https://$domain/")
				.build()

			val response = webClient.httpGet(url, headers)
			
			if (response.isSuccessful) {
				val json = response.parseJson()
				if (json.optBoolean("status", false)) {
					val token = json.optString("token", "")
					if (token.isNotEmpty()) {
						visitTokenCache = token
						val expiresAtSec = json.optLong("expiresAt", 0L)
						visitTokenExpiresAt = if (expiresAtSec > 0) {
							expiresAtSec * 1000L
						} else {
							now + 3600_000L // Default expire 1 jam jika gagal diparsing
						}
						return@withLock visitTokenCache
					}
				}
			} else {
				response.close()
			}
		} catch (e: Exception) {
			e.printStackTrace()
		}

		// Reset token jika gagal atau force refresh dipanggil
		if (forceRefresh) {
			visitTokenCache = null
			visitTokenExpiresAt = 0L
		}
		
		return@withLock visitTokenCache
	}

	/**
	 * Helper untuk melakukan HTTP GET request dengan otomatis memasang X-WM-Visit token.
	 * Jika server mengembalikan 403 atau 404 karena token hangus, fungsi ini akan otomatis merefresh token 1x.
	 */
	private suspend fun apiGet(url: okhttp3.HttpUrl, forceRefresh: Boolean = false): JSONObject {
		var token = getVisitToken(forceRefresh)
		
		var headers = Headers.Builder()
			.add("User-Agent", config[userAgentKey])
			.add("Referer", "https://$domain/")
			.apply { token?.let { add("X-WM-Visit", it) } }
			.build()

		var response = webClient.httpGet(url, headers)

		// Jika ditolak (HTTP 403/404), token lama mungkin expired atau tidak valid. Refresh token dan coba lagi.
		if (!response.isSuccessful && (response.code == 403 || response.code == 404) && !forceRefresh) {
			response.close() // Tutup body respon lama agar tidak memory leak
			token = getVisitToken(forceRefresh = true)
			
			headers = Headers.Builder()
				.add("User-Agent", config[userAgentKey])
				.add("Referer", "https://$domain/")
				.apply { token?.let { add("X-WM-Visit", it) } }
				.build()
				
			response = webClient.httpGet(url, headers)
		}

		return response.parseJson()
	}
}
