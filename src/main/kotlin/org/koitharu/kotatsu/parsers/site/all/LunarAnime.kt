package org.koitharu.kotatsu.parsers.site.all

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.CryptoAES
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseRaw
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.LinkedHashSet
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("LUNARANIME", "Lunar Manga")
internal class LunarAnime(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.LUNARANIME, pageSize = 30) {

	override val configKeyDomain = org.koitharu.kotatsu.parsers.config.ConfigKey.Domain("lunaranime.ru")

	override val defaultSortOrder: SortOrder = SortOrder.UPDATED

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.RELEVANCE,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isYearSupported = true,
		)

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Referer", "https://$domain/")
		.build()

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		return if (request.url.host.equals(CDN_HOST, ignoreCase = true)) {
			chain.proceed(
				request.newBuilder()
					.header("Referer", "https://$domain/")
					.header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
					.build(),
			)
		} else {
			chain.proceed(request)
		}
	}

	private val filterOptions = suspendLazy(initializer = ::fetchFilterOptions)

	override suspend fun getFilterOptions(): MangaListFilterOptions = filterOptions.get()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		return if (
			order == SortOrder.UPDATED &&
			filter.query.isNullOrBlank() &&
			filter.tags.isEmpty() &&
			filter.states.isEmpty() &&
			filter.year <= 0 &&
			filter.locale == null
		) {
			fetchRecent(page)
		} else {
			search(page, filter)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.substringAfterLast('/')
		val details = webClient.httpGet("$apiBaseUrl/api/manga/title/$slug", getRequestHeaders()).parseJson()
		val info = details.optJSONObject("manga") ?: return manga
		val passwordInfo = runCatching {
			webClient.httpGet("$apiBaseUrl/api/manga/password/info/$slug", getRequestHeaders()).parseJson()
		}.getOrNull()
		val chaptersRoot = webClient.httpGet("$apiBaseUrl/api/manga/$slug", getRequestHeaders()).parseJson()

		return parseManga(info).copy(
			id = manga.id,
			url = manga.url,
			publicUrl = manga.publicUrl,
			chapters = parseChapters(
				slug = slug,
				chapters = chaptersRoot.optJSONArray("data"),
				passwordInfo = passwordInfo,
			),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain).toHttpUrl()
		val slug = chapterUrl.pathSegments.getOrNull(1).orEmpty()
		val chapterId = chapterUrl.pathSegments.getOrNull(2).orEmpty()
		val language = chapterUrl.queryParameter("lang").orEmpty()
		if (slug.isEmpty() || chapterId.isEmpty() || language.isEmpty()) {
			return emptyList()
		}

		val secretKey = fetchSecretKey(chapter.url.substringBefore("?").toAbsoluteUrl(domain))
		val root = webClient.httpGet(
			"$apiBaseUrl/api${chapter.url}",
			getRequestHeaders(),
		).parseJson()

		val data = root.optJSONObject("data")
		val imageUrls = when {
			data == null -> emptyList()
			data.optString("session_data").isNotBlank() -> {
				if (secretKey.isNullOrBlank()) {
					emptyList()
				} else {
					decryptSessionData(data.optString("session_data"), secretKey)
				}
			}
			data.optJSONArray("images") != null -> jsonArrayToStrings(data.optJSONArray("images"))
			else -> {
				val fallback = root.optJSONArray("images")
					?: root.optJSONArray("chapter_images")
				jsonArrayToStrings(fallback)
			}
		}

		return imageUrls.mapIndexed { index, imageUrl ->
			MangaPage(
				id = generateUid("${chapter.url}#$index"),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun fetchRecent(page: Int): List<Manga> {
		val url = "$apiBaseUrl/api/manga/recent?page=$page&limit=$pageSize"
		val root = webClient.httpGet(url, getRequestHeaders()).parseJson()
		val mangas = root.optJSONArray("our_mangas") ?: root.optJSONArray("mangas")
		return List(mangas?.length() ?: 0) { index ->
			parseManga(mangas!!.getJSONObject(index))
		}
	}

	private suspend fun search(page: Int, filter: MangaListFilter): List<Manga> {
		val url = "$apiBaseUrl/api/manga/search".toHttpUrl().newBuilder()
			.addQueryParameter("page", page.toString())
			.addQueryParameter("limit", pageSize.toString())

		filter.query?.takeIf { it.isNotBlank() }?.let {
			url.addQueryParameter("query", it)
		}

		if (filter.tags.isNotEmpty()) {
			url.addQueryParameter("genres", filter.tags.joinToString(",") { it.key })
		}

		filter.states.firstOrNull()?.let { state ->
			url.addQueryParameter(
				"status",
				when (state) {
					MangaState.ONGOING -> "ongoing"
					MangaState.FINISHED -> "completed"
					MangaState.PAUSED -> "hiatus"
					MangaState.ABANDONED -> "cancelled"
					else -> return@let
				},
			)
		}

		if (filter.year > 0) {
			url.addQueryParameter("year", filter.year.toString())
		}

		filter.locale?.language
			?.takeIf { it.isNotBlank() }
			?.let { url.addQueryParameter("language", normalizeLanguageCode(it)) }

		url.addQueryParameter("sort", "relevance")

		val root = webClient.httpGet(url.build(), getRequestHeaders()).parseJson()
		val mangas = root.optJSONArray("manga") ?: JSONArray()
		return List(mangas.length()) { index ->
			parseManga(mangas.getJSONObject(index))
		}
	}

	private fun parseManga(json: JSONObject): Manga {
		val slug = json.optString("slug")
		val url = "/manga/$slug"
		val tags = LinkedHashSet<MangaTag>()
		parseStringArray(json.optString("genres")).forEach { genre ->
			tags += MangaTag(
				key = genre,
				title = genre,
				source = source,
			)
		}
		parseStringArray(json.optString("themes")).forEach { theme ->
			tags += MangaTag(
				key = theme,
				title = theme,
				source = source,
			)
		}
		json.optString("demographic").nullIfEmpty()?.let { demographic ->
			tags += MangaTag(
				key = demographic,
				title = demographic.replaceFirstChar { ch -> ch.titlecase(Locale.ROOT) },
				source = source,
			)
		}

		val authors = LinkedHashSet<String>()
		authors += splitPeople(json.optString("author"))
		authors += splitPeople(json.optString("artist"))

		return Manga(
			id = generateUid(url),
			title = json.optString("title").ifBlank { slug },
			altTitles = parseStringArray(json.optString("alternative_titles")).toSet(),
			url = url,
			publicUrl = url.toAbsoluteUrl(domain),
			rating = RATING_UNKNOWN,
			contentRating = parseContentRating(json.optString("rating")),
			coverUrl = json.optString("cover_url").nullIfEmpty(),
			tags = tags,
			state = parseState(json.optString("publication_status")),
			authors = authors,
			largeCoverUrl = json.optString("banner_url").nullIfEmpty(),
			description = json.optString("description").nullIfEmpty(),
			source = source,
		)
	}

	private fun parseChapters(
		slug: String,
		chapters: JSONArray?,
		passwordInfo: JSONObject?,
	): List<MangaChapter> {
		if (chapters == null) return emptyList()
		val hasSeriesPassword = passwordInfo?.optBoolean("has_series_password") == true
		val chapterPasswords = passwordInfo?.optJSONArray("chapter_passwords")
		return List(chapters.length()) { index ->
			val chapter = chapters.getJSONObject(index)
			val chapterId = chapter.optString("chapter").ifBlank {
				chapter.optString("chapter_number")
			}
			val language = chapter.optString("language").ifBlank { "en" }
			val locked = hasSeriesPassword || isChapterLocked(chapterPasswords, chapterId, language)
			val rawTitle = chapter.optString("chapter_title").nullIfEmpty()
			val chapterNumber = chapter.optDouble("chapter_number", 0.0).toFloat()
			val displayTitle = buildString {
				if (chapterNumber > 0f) {
					append("Chapter ")
					append(formatChapterNumber(chapterNumber))
				}
				if (!rawTitle.isNullOrBlank()) {
					if (isNotEmpty()) append(" - ")
					append(rawTitle)
				}
				if (isEmpty()) {
					append("Chapter ")
					append(chapterId)
				}
				if (locked) {
					append(" [Locked]")
				}
			}
			MangaChapter(
				id = generateUid("/$slug/$chapterId/$language"),
				title = displayTitle,
				number = chapterNumber,
				volume = 0,
				url = "/manga/$slug/$chapterId?lang=$language",
				scanlator = chapter.optJSONObject("uploader_profile")?.optString("username")?.nullIfEmpty(),
				uploadDate = parseDate(chapter.optString("uploaded_at")),
				branch = languageToTitle(language),
				source = source,
			)
		}
	}

	private fun isChapterLocked(passwords: JSONArray?, chapterId: String, language: String): Boolean {
		if (passwords == null) return false
		for (i in 0 until passwords.length()) {
			val item = passwords.optJSONObject(i) ?: continue
			val passwordChapter = item.opt("chapter_number")?.toString().orEmpty()
			val passwordLanguage = item.optString("language").nullIfEmpty()
			if (passwordChapter == chapterId && (passwordLanguage == null || passwordLanguage == language)) {
				return true
			}
		}
		return false
	}

	private suspend fun fetchFilterOptions(): MangaListFilterOptions {
		val languages = fetchLanguages()
		val tags = fetchTags()
		return MangaListFilterOptions(
			availableTags = tags,
			availableStates = EnumSet.of(
				MangaState.ONGOING,
				MangaState.FINISHED,
				MangaState.PAUSED,
				MangaState.ABANDONED,
			),
			availableLocales = languages,
		)
	}

	private suspend fun fetchLanguages(): Set<Locale> {
		val root = webClient.httpGet(
			"$apiBaseUrl/api/manga/recent?page=1&limit=1",
			getRequestHeaders(),
		).parseJson()
		return parseStringArray(root.optJSONArray("available_languages"))
			.mapTo(LinkedHashSet()) { Locale(normalizeLanguageCode(it)) }
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val tags = LinkedHashSet<MangaTag>()
		for (page in 1..3) {
			val root = webClient.httpGet(
				"$apiBaseUrl/api/manga/search?page=$page&limit=100",
				getRequestHeaders(),
			).parseJson()
			val mangas = root.optJSONArray("manga") ?: break
			for (i in 0 until mangas.length()) {
				val genres = parseStringArray(mangas.getJSONObject(i).optString("genres"))
				genres.forEach { genre ->
					tags += MangaTag(
						key = genre,
						title = genre,
						source = source,
					)
				}
			}
			if (mangas.length() < 100) {
				break
			}
		}
		return tags.sortedBy { it.title }.toCollection(LinkedHashSet())
	}

	private suspend fun fetchSecretKey(url: String): String? {
		val html = webClient.httpGet(url, getRequestHeaders()).parseRaw()
		return SECRET_KEY_REGEXES.firstNotNullOfOrNull { regex ->
			regex.find(html)?.groupValues?.getOrNull(1)?.nullIfEmpty()
		}
	}

	private fun decryptSessionData(sessionData: String, secretKey: String): List<String> {
		val decrypted = runCatching {
			CryptoAES(context).decrypt(sessionData, secretKey.sha256(), ByteArray(16))
		}.getOrNull() ?: return emptyList()
		val normalized = decrypted
			.trim()
			.trim('\u0000')
			.replace("\\/", "/")
		parseDecryptedPayload(normalized)?.let { payload ->
			return jsonArrayToStrings(
				payload.optJSONObject("data")?.optJSONArray("images")
					?: payload.optJSONArray("images")
					?: payload.optJSONArray("chapter_images"),
			)
		}
		return DECRYPTED_IMAGE_URL_REGEX.findAll(normalized)
			.map { it.value }
			.toList()
	}

	private fun parseDecryptedPayload(payload: String): JSONObject? {
		runCatching { JSONObject(payload) }.getOrNull()?.let { return it }
		if (payload.startsWith("\"") && payload.endsWith("\"")) {
			val unwrapped = runCatching {
				JSONArray("[${payload}]").getString(0)
			}.getOrNull()?.replace("\\/", "/")
			if (!unwrapped.isNullOrBlank()) {
				runCatching { JSONObject(unwrapped) }.getOrNull()?.let { return it }
			}
		}
		return null
	}

	private fun parseStringArray(raw: String?): List<String> {
		if (raw.isNullOrBlank()) return emptyList()
		return runCatching {
			val array = JSONArray(raw)
			List(array.length()) { index ->
				array.optString(index).trim()
			}.filter { it.isNotEmpty() }
		}.getOrDefault(emptyList())
	}

	private fun parseStringArray(array: JSONArray?): List<String> {
		if (array == null) return emptyList()
		return List(array.length()) { index ->
			array.optString(index).trim()
		}.filter { it.isNotEmpty() }
	}

	private fun jsonArrayToStrings(array: JSONArray?): List<String> {
		if (array == null) return emptyList()
		return List(array.length()) { index ->
			array.optString(index).trim()
		}.filter { it.isNotEmpty() }
	}

	private fun splitPeople(raw: String?): List<String> {
		return raw.orEmpty()
			.split(',', '&', '/', ';')
			.mapNotNull { it.trim().nullIfEmpty() }
	}

	private fun parseContentRating(raw: String?): ContentRating? {
		return when (raw?.trim()?.uppercase(Locale.ROOT)) {
			"G", "PG", "SAFE" -> ContentRating.SAFE
			"PG-13", "R", "R-15" -> ContentRating.SUGGESTIVE
			"R-18", "NSFW", "ADULT" -> ContentRating.ADULT
			else -> null
		}
	}

	private fun parseState(raw: String?): MangaState? {
		return when (raw?.trim()?.lowercase(Locale.ROOT)) {
			"ongoing" -> MangaState.ONGOING
			"completed", "finished" -> MangaState.FINISHED
			"hiatus", "paused" -> MangaState.PAUSED
			"cancelled", "canceled", "dropped", "abandoned" -> MangaState.ABANDONED
			else -> null
		}
	}

	private fun parseDate(raw: String?): Long {
		if (raw.isNullOrBlank()) return 0L
		return synchronized(dateFormats) {
			dateFormats.firstNotNullOfOrNull { format ->
				runCatching { format.parseSafe(raw) }.getOrNull()?.takeIf { it != 0L }
			} ?: 0L
		}
	}

	private fun formatChapterNumber(number: Float): String {
		return if (number % 1f == 0f) {
			number.toInt().toString()
		} else {
			number.toString()
		}
	}

	private fun languageToTitle(code: String): String {
		return when (normalizeLanguageCode(code)) {
			"bg" -> "Bulgarian"
			"en" -> "English"
			"fr" -> "French"
			"id" -> "Indonesian"
			"ja" -> "Japanese"
			"ko" -> "Korean"
			else -> code.uppercase(Locale.ROOT)
		}
	}

	private fun normalizeLanguageCode(code: String): String {
		return when (code.lowercase(Locale.ROOT)) {
			"in" -> "id"
			else -> code.lowercase(Locale.ROOT)
		}
	}

	private fun String.sha256(): ByteArray = MessageDigest.getInstance("SHA-256").digest(toByteArray())

	private companion object {
		private const val apiBaseUrl = "https://api.lunaranime.ru"
		private const val CDN_HOST = "storage.lunaranime.ru"
		private val SECRET_KEY_REGEXES = listOf(
			Regex("\"secretKey\":\"([^\"]+)\""),
			Regex("\\\\\"secretKey\\\\\":\\\\\"([^\\\\\"]+)\\\\\""),
			Regex("""secretKey["']?\s*:\s*["']([^"']+)["']"""),
		)
		private val DECRYPTED_IMAGE_URL_REGEX = Regex("""https://storage\.lunaranime\.ru/[^\s"\\]+""")
		private val dateFormats = listOf(
			SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US),
			SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US),
		).onEach {
			it.timeZone = TimeZone.getTimeZone("UTC")
		}
	}
}
