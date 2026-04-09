package org.koitharu.kotatsu.parsers.site.en

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
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
import org.koitharu.kotatsu.parsers.util.attrAsAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.selectFirstOrThrow
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("STONESCAPE", "StoneScape", "en")
internal class StoneScape(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.STONESCAPE, 24) {

	override val configKeyDomain = ConfigKey.Domain("stonescape.xyz")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.set("Referer", "https://$domain/")
		.set("Origin", "https://$domain")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RELEVANCE,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchGenres(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
			MangaState.ABANDONED,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = "https://$domain/api/series".toHttpUrl().newBuilder()
			.addQueryParameter("page", page.toString())
			.addQueryParameter("limit", "24")
			.addQueryParameter("contentType", "manhwa")

		if (!filter.query.isNullOrBlank()) {
			url.addQueryParameter("search", filter.query)
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

		if (filter.tags.isNotEmpty()) {
			url.addQueryParameter("genres", filter.tags.joinToString(",") { it.key })
		}

		when {
			filter.query.isNullOrBlank() && order == SortOrder.POPULARITY -> {
				url.addQueryParameter("sort", "views")
			}

			filter.query.isNullOrBlank() -> {
				url.addQueryParameter("sort", "latest")
			}

			order == SortOrder.POPULARITY -> {
				url.addQueryParameter("sort", "views")
			}

			order == SortOrder.UPDATED -> {
				url.addQueryParameter("sort", "latest")
			}
		}

		val root = webClient.httpGet(url.build()).parseJson()
		val items = root.optJSONArray("data") ?: JSONArray()
		return List(items.length()) { i ->
			parseSeries(items.getJSONObject(i))
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.substringAfterLast('/')
		val root = webClient.httpGet("https://$domain/api/series/by-slug/$slug").parseJson()
		val series = unwrapSeries(root)
		return manga.copy(
			title = series.optString("postTitle")
				.ifBlank { series.optString("title") }
				.ifBlank { manga.title },
			url = "/series/$slug",
			publicUrl = "https://$domain/series/$slug",
			coverUrl = resolveImageUrl(
				series.optString("coverUrl")
					.ifBlank { series.optString("featuredImage") }
					.ifBlank { series.optString("cover") }
					.ifBlank { manga.coverUrl.orEmpty() },
			).nullIfEmpty() ?: manga.coverUrl,
			largeCoverUrl = resolveImageUrl(
				series.optString("bannerUrl")
					.ifBlank { series.optString("banner") },
			).nullIfEmpty(),
			description = series.optString("postContent")
				.ifBlank { series.optString("description") }
				.nullIfEmpty(),
			altTitles = parseAltTitles(series),
			tags = parseGenres(series.optJSONArray("genres")),
			state = parseStatus(
				series.optString("publicationStatus")
					.ifBlank { series.optString("seriesStatus") }
					.ifBlank { series.optString("status") },
			),
			authors = parseAuthors(series),
			rating = parseRating(series),
			contentRating = ContentRating.SAFE,
			chapters = fetchChapters(slug),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterId = chapter.url.substringAfter('#', "")
		check(chapterId.isNotEmpty()) { "Chapter id is missing: ${chapter.url}" }

		val root = webClient.httpGet("https://$domain/api/chapters/$chapterId/pages").parseJson()
		val pagesArray = root.optJSONArray("allPages")
			?: root.optJSONObject("chapter")?.optJSONArray("allPages")
			?: root.optJSONArray("pages")
			?: JSONArray()

		return List(pagesArray.length()) { index ->
			val page = pagesArray.getJSONObject(index)
			val imageUrl = resolveImageUrl(page.optString("url"))
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun fetchChapters(slug: String): List<MangaChapter> {
		val root = webClient.httpGet("https://$domain/api/series/by-slug/$slug/chapters").parseJson()
		val chapters = root.optJSONArray("chapters")
			?: root.optJSONObject("data")?.optJSONArray("chapters")
			?: JSONArray()

		return List(chapters.length()) { i ->
			val chapter = chapters.getJSONObject(i)
			val chapterNumber = chapter.optString("chapterNumber")
				.ifBlank { chapter.optString("number") }
			val chapterSlug = chapter.optString("slug").ifBlank {
				"chapter-${chapterNumber.ifBlank { (i + 1).toString() }}"
			}
			val chapterId = chapter.optString("chapterId")
				.ifBlank { chapter.optString("id") }
			val url = "/series/$slug/$chapterSlug#$chapterId"
			val number = chapterNumber.toFloatOrNull()
				?: chapter.optDouble("number", (i + 1).toDouble()).toFloat()
			val title = chapter.optString("title")
				.takeUnless { it.equals("null", ignoreCase = true) }
				?.nullIfEmpty()
			MangaChapter(
				id = generateUid(url),
				title = when {
					!title.isNullOrBlank() && number > 0f -> "Chapter ${formatNumber(number)} - $title"
					!title.isNullOrBlank() -> title
					number > 0f -> "Chapter ${formatNumber(number)}"
					else -> chapterSlug
				},
				number = number,
				volume = 0,
				url = url,
				scanlator = null,
				uploadDate = parseDate(
					chapter.optString("createdAt")
						.ifBlank { chapter.optString("updatedAt") },
				),
				branch = null,
				source = source,
			)
		}
	}

	private suspend fun fetchGenres(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/series/").parseHtml()
		val tags = doc.select("a[href*='/series-genre/']").mapNotNullToSet { a ->
			val href = a.attr("href")
			val slug = href.removeSuffix("/").substringAfterLast("/")
			val title = a.text().trim().nullIfEmpty()
			if (slug.isEmpty() || title == null) {
				null
			} else {
				MangaTag(
					key = slug,
					title = title.toTitleCase(sourceLocale),
					source = source,
				)
			}
		}
		if (tags.isNotEmpty()) {
			return tags
		}

		val fallback = webClient.httpGet(
			"https://$domain/api/series?page=1&limit=24&sort=views&contentType=manhwa",
		).parseJson()
		val data = fallback.optJSONArray("data") ?: JSONArray()
		val result = LinkedHashSet<MangaTag>()
		for (i in 0 until data.length()) {
			result.addAll(parseGenres(data.getJSONObject(i).optJSONArray("genres")))
		}
		return result
	}

	private fun parseSeries(json: JSONObject): Manga {
		val slug = json.optString("slug")
		val url = "/series/$slug"
		return Manga(
			id = generateUid(url),
			url = url,
			publicUrl = "https://$domain$url",
			title = json.optString("postTitle")
				.ifBlank { json.optString("title") }
				.ifBlank { slug },
			coverUrl = resolveImageUrl(
				json.optString("coverUrl")
					.ifBlank { json.optString("featuredImage") }
					.ifBlank { json.optString("cover") },
			).nullIfEmpty(),
			altTitles = emptySet(),
			rating = parseRating(json),
			tags = parseGenres(json.optJSONArray("genres")),
			description = null,
			state = parseStatus(
				json.optString("publicationStatus")
					.ifBlank { json.optString("seriesStatus") }
					.ifBlank { json.optString("status") },
			),
			authors = emptySet(),
			contentRating = ContentRating.SAFE,
			source = source,
		)
	}

	private fun unwrapSeries(root: JSONObject): JSONObject {
		return root.optJSONObject("data")
			?: root.optJSONObject("series")
			?: root
	}

	private fun parseGenres(array: JSONArray?): Set<MangaTag> {
		if (array == null) return emptySet()
		val result = LinkedHashSet<MangaTag>(array.length())
		for (i in 0 until array.length()) {
			when (val genre = array.opt(i)) {
				is JSONObject -> {
					val title = genre.optString("name").nullIfEmpty() ?: continue
					val key = genre.optString("slug")
						.ifBlank { genre.opt("id")?.toString().orEmpty() }
						.ifBlank { title.lowercase(sourceLocale).replace(' ', '-') }
					result += MangaTag(key = key, title = title, source = source)
				}

				is String -> {
					val title = genre.trim().nullIfEmpty() ?: continue
					result += MangaTag(key = title, title = title.toTitleCase(sourceLocale), source = source)
				}
			}
		}
		return result
	}

	private fun parseAltTitles(series: JSONObject): Set<String> {
		val raw = series.opt("alternativeTitles") ?: series.opt("alternative_titles") ?: return emptySet()
		return when (raw) {
			is JSONArray -> buildSet(raw.length()) {
				for (i in 0 until raw.length()) {
					raw.optString(i).nullIfEmpty()?.let(::add)
				}
			}

			is String -> raw.split(',', ';', '\n')
				.mapNotNull { it.trim().nullIfEmpty() }
				.toSet()

			else -> emptySet()
		}
	}

	private fun parseAuthors(series: JSONObject): Set<String> {
		val authors = LinkedHashSet<String>(2)
		series.optString("author").split(',', '/', '&')
			.mapNotNull { it.trim().nullIfEmpty() }
			.forEach(authors::add)
		series.optString("artist").split(',', '/', '&')
			.mapNotNull { it.trim().nullIfEmpty() }
			.forEach(authors::add)
		return authors
	}

	private fun parseStatus(raw: String?): MangaState? {
		return when (raw?.trim()?.lowercase(sourceLocale)) {
			"ongoing", "releasing" -> MangaState.ONGOING
			"completed", "complete", "finished" -> MangaState.FINISHED
			"hiatus", "on hiatus" -> MangaState.PAUSED
			"cancelled", "canceled", "discontinued", "dropped" -> MangaState.ABANDONED
			else -> null
		}
	}

	private fun parseRating(series: JSONObject): Float {
		val value = series.optDouble("averageRating", Double.NaN).takeUnless { it.isNaN() }
			?: series.optDouble("rating", Double.NaN).takeUnless { it.isNaN() }
			?: return RATING_UNKNOWN
		return when {
			value <= 0.0 -> RATING_UNKNOWN
			value > 5.0 -> (value / 2.0).toFloat()
			else -> value.toFloat()
		}
	}

	private fun resolveImageUrl(url: String?): String {
		if (url.isNullOrBlank()) return ""
		return if (url.startsWith("http://") || url.startsWith("https://")) {
			url
		} else {
			url.toAbsoluteUrl(domain)
		}
	}

	private fun parseDate(value: String?): Long {
		if (value.isNullOrBlank()) return 0L
		return synchronized(dateFormats) {
			dateFormats.firstNotNullOfOrNull { format ->
				runCatching { format.parseSafe(value) }.getOrNull()?.takeIf { it != 0L }
			} ?: 0L
		}
	}

	private fun formatNumber(number: Float): String {
		return if (number % 1f == 0f) {
			number.toInt().toString()
		} else {
			number.toString()
		}
	}

	private companion object {
		private val dateFormats = listOf(
			SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US),
			SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSSSSXXX", Locale.US),
		).onEach {
			it.timeZone = TimeZone.getTimeZone("UTC")
		}
	}
}
