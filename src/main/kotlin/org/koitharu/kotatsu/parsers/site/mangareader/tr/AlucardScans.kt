package org.koitharu.kotatsu.parsers.site.mangareader.tr

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
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
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("ALUCARDSCANS", "AlucardScans", "tr")
internal class AlucardScans(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.ALUCARDSCANS, 24) {

	override val configKeyDomain = ConfigKey.Domain("alucardscans.com")

	private val utcDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
		timeZone = TimeZone.getTimeZone("UTC")
	}
	private val utcDateFormatNoMillis = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
		timeZone = TimeZone.getTimeZone("UTC")
	}

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.set("Referer", "https://$domain/")
		.set("Origin", "https://$domain")
		.build()

	override val defaultSortOrder: SortOrder
		get() = SortOrder.POPULARITY

	override val availableSortOrders: Set<SortOrder>
		get() = EnumSet.of(SortOrder.POPULARITY, SortOrder.UPDATED)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		return when {
			!filter.query.isNullOrBlank() -> search(page, filter.query)
			order == SortOrder.UPDATED -> getLatestPage(page)
			else -> getPopularPage(page)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val scriptData = doc.extractScriptData()
		val series = scriptData?.extractJsonValue("initialSeries")?.let(::JSONObject)
		val chapters = scriptData?.extractJsonValue("initialChapters")
			?.let(::JSONArray)
			?.let(::parseChapters)
			.orEmpty()

		val title = series?.optString("title")
			.nullIfBlank()
			?: series?.optString("postTitle").nullIfBlank()
			?: doc.selectFirst("h1")?.text().nullIfBlank()
			?: manga.title

		return manga.copy(
			title = title,
			description = series?.optString("description")
				.nullIfBlank()
				?: series?.optString("postContent").nullIfBlank()
				?: manga.description,
			coverUrl = resolveImageUrl(
				series?.optString("coverImage")
					.nullIfBlank()
					?: series?.optString("cover").nullIfBlank(),
			) ?: doc.selectFirst("img[src], img[data-src]")?.let { img ->
				img.src().nullIfBlank() ?: img.attr("abs:data-src").nullIfBlank()
			} ?: manga.coverUrl,
			tags = series?.opt("genres")?.let(::parseTags).orEmpty(),
			state = parseState(
				series?.optString("status")
					.nullIfBlank()
					?: series?.optString("seriesStatus").nullIfBlank(),
			) ?: manga.state,
			contentRating = ContentRating.SAFE,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		return doc.select("div.w-full.flex-col.items-center img")
			.mapNotNull { img ->
				img.src().nullIfBlank() ?: img.attr("abs:data-src").nullIfBlank()
			}
			.distinct()
			.map { url ->
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			}
	}

	private suspend fun getPopularPage(page: Int): List<Manga> {
		val json = webClient.httpGet(
			"https://$domain/api/series?sort=views&order=desc&calculateTotalViews=true&page=$page&limit=24",
		).parseJson()
		return parseSeriesArray(json.optJSONArray("series") ?: json.optJSONArray("data"))
	}

	private suspend fun getLatestPage(page: Int): List<Manga> {
		val json = webClient.httpGet(
			"https://$domain/api/chapters/latest?page=$page&limit=24",
		).parseJson()
		val groupedChapters = json.optJSONArray("groupedChapters") ?: return emptyList()
		val mangas = ArrayList<Manga>(groupedChapters.length())
		for (i in 0 until groupedChapters.length()) {
			val series = groupedChapters.optJSONObject(i)?.optJSONObject("series") ?: continue
			parseSeries(series)?.let(mangas::add)
		}
		return mangas.distinctBy { it.url }
	}

	private suspend fun search(page: Int, query: String): List<Manga> {
		val json = webClient.httpGet(
			"https://$domain/api/series?search=${query.urlEncoded()}&page=$page&limit=24",
		).parseJson()
		return parseSeriesArray(json.optJSONArray("series") ?: json.optJSONArray("data"))
	}

	private fun parseSeriesArray(array: JSONArray?): List<Manga> {
		if (array == null) return emptyList()
		val mangas = ArrayList<Manga>(array.length())
		for (i in 0 until array.length()) {
			parseSeries(array.optJSONObject(i))?.let(mangas::add)
		}
		return mangas
	}

	private fun parseSeries(json: JSONObject?): Manga? {
		json ?: return null
		val slug = json.optString("slug").nullIfBlank() ?: return null
		val url = "/manga/$slug"
		return Manga(
			id = generateUid(url),
			title = json.optString("title")
				.nullIfBlank()
				?: json.optString("postTitle").nullIfBlank()
				?: slug.toTitleCase(sourceLocale),
			altTitles = emptySet(),
			url = url,
			publicUrl = url.toAbsoluteUrl(domain),
			rating = RATING_UNKNOWN,
			contentRating = ContentRating.SAFE,
			coverUrl = resolveImageUrl(
				json.optString("coverImage")
					.nullIfBlank()
					?: json.optString("cover").nullIfBlank(),
			),
			tags = json.opt("genres")?.let(::parseTags).orEmpty(),
			state = parseState(
				json.optString("status")
					.nullIfBlank()
					?: json.optString("seriesStatus").nullIfBlank(),
			),
			authors = emptySet(),
			source = source,
		)
	}

	private fun parseChapters(array: JSONArray): List<MangaChapter> {
		val chapters = ArrayList<MangaChapter>(array.length())
		for (i in 0 until array.length()) {
			val chapter = array.optJSONObject(i) ?: continue
			val slug = chapter.optString("slug").nullIfBlank() ?: continue
			val numberText = chapter.optString("number").nullIfBlank()
				?: chapter.opt("number")?.toString()?.nullIfEmpty()
			chapters += MangaChapter(
				id = generateUid(slug),
				title = chapter.optString("title").nullIfBlank(),
				number = numberText?.toFloatOrNull()
					?: chapter.optDouble("number", 0.0).takeIf { it > 0.0 }?.toFloat()
					?: 0f,
				volume = 0,
				url = "/$slug",
				scanlator = null,
				uploadDate = parseDate(chapter.optString("releaseDate").nullIfBlank()),
				branch = null,
				source = source,
			)
		}
		return chapters.sortedWith(
			compareBy<MangaChapter> { it.number <= 0f }
				.thenBy { it.number }
				.thenBy { it.uploadDate.takeIf { date -> date > 0L } ?: Long.MAX_VALUE },
		)
	}

	private fun parseTags(raw: Any?): Set<MangaTag> {
		val result = LinkedHashSet<MangaTag>()
		when (raw) {
			is JSONArray -> {
				for (i in 0 until raw.length()) {
					when (val item = raw.opt(i)) {
						is JSONObject -> {
							val title = item.optString("name")
								.nullIfBlank()
								?: item.optString("title").nullIfBlank()
								?: continue
							val key = item.optString("slug")
								.nullIfBlank()
								?: title.lowercase(sourceLocale).replace(' ', '-')
							result += MangaTag(
								key = key,
								title = title.toTitleCase(sourceLocale),
								source = source,
							)
						}

						is String -> {
							val title = item.trim().nullIfEmpty() ?: continue
							result += MangaTag(
								key = title.lowercase(sourceLocale).replace(' ', '-'),
								title = title.toTitleCase(sourceLocale),
								source = source,
							)
						}
					}
				}
			}

			is String -> {
				raw.split(',', ';')
					.mapNotNull { it.trim().nullIfEmpty() }
					.forEach { title ->
						result += MangaTag(
							key = title.lowercase(sourceLocale).replace(' ', '-'),
							title = title.toTitleCase(sourceLocale),
							source = source,
						)
					}
			}
		}
		return result
	}

	private fun parseState(raw: String?): MangaState? {
		return when (raw?.trim()?.lowercase(sourceLocale)) {
			"ongoing", "devam ediyor" -> MangaState.ONGOING
			"complete", "completed", "tamamlandi", "tamamlandı", "bitti" -> MangaState.FINISHED
			else -> null
		}
	}

	private fun parseDate(value: String?): Long {
		value ?: return 0L
		return utcDateFormat.parseSafe(value)
			?: utcDateFormatNoMillis.parseSafe(value)
			?: 0L
	}

	private fun resolveImageUrl(url: String?): String? {
		val value = url.nullIfBlank() ?: return null
		return when {
			value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true) -> value
			value.startsWith("//") -> "https:$value"
			value.startsWith("/") -> "https://$domain$value"
			else -> "https://$domain/$value"
		}
	}

	private fun Document.extractScriptData(): String? {
		return select("script")
			.firstOrNull { script ->
				script.data().contains("initialSeries") || script.data().contains("initialChapters")
			}
			?.data()
			?.nullIfEmpty()
	}

	private fun String.extractJsonValue(key: String): String? {
		val keyIndex = indexOf(key)
		if (keyIndex == -1) return null

		var start = -1
		var opening = '\u0000'
		for (i in keyIndex + key.length until length) {
			when (this[i]) {
				'{', '[' -> {
					start = i
					opening = this[i]
					break
				}
			}
		}
		if (start == -1) return null

		val closing = if (opening == '{') '}' else ']'
		var depth = 0
		for (i in start until length) {
			when (this[i]) {
				opening -> depth++
				closing -> {
					depth--
					if (depth == 0) {
						return substring(start, i + 1).cleanupEscapedJson()
					}
				}
			}
		}
		return null
	}

	private fun String.cleanupEscapedJson(): String {
		return replace("\\/", "/")
			.replace("\\\"", "\"")
			.replace("\\n", "\n")
			.replace("\\t", "\t")
	}

	private fun String?.nullIfBlank(): String? = this?.trim()?.nullIfEmpty()
}
