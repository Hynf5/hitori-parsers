package org.koitharu.kotatsu.parsers.site.en

import okhttp3.Headers
import okhttp3.HttpUrl
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.getBooleanOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.LinkedHashMap
import java.util.Locale

@MangaSourceParser("RINKOCOMICS", "Rinko Comics", "en", ContentType.COMICS)
internal class RinkoComics(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.RINKOCOMICS, 20) {

	override val configKeyDomain = ConfigKey.Domain("rinkocomics.com")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Referer", "https://$domain/")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.NEWEST_ASC,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
		)

	private var genresList: Set<MangaTag> = emptySet()

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		if (genresList.isEmpty()) {
			genresList = fetchGenres()
		}
		return MangaListFilterOptions(availableTags = genresList)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = comicsUrl(page).apply {
			addQueryParameter("post_type", "comic")
			if (!filter.query.isNullOrEmpty()) {
				addQueryParameter("s", filter.query)
			}
			for (tag in filter.tags) {
				addQueryParameter("genres[]", tag.key)
			}
			sortQuery(order)?.let { addQueryParameter(SORT_PARAM, it) }
		}.build()

		val document = webClient.httpGet(url).parseHtml()
		if (genresList.isEmpty()) {
			genresList = parseGenres(document)
		}
		return parseComicsPage(document)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val document = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val title = document.selectFirst(".comic-info-upper h1")?.textOrNull()?.trim()?.nullIfEmpty()
			?: document.selectFirst("h1")?.textOrNull()?.trim()?.nullIfEmpty()
			?: manga.title

		val authors = document.select(".comic-graph > span").mapNotNullToSet { span ->
			val name = span.text().trim()
			if (name.isEmpty() || name == "•") null else name
		}

		return manga.copy(
			title = title,
			coverUrl = document.selectFirst("meta[property=og:image]")?.attrOrNull("content") ?: manga.coverUrl,
			authors = authors,
			state = parseStatus(document.selectFirst(".comic-status span:last-child")?.textOrNull()),
			tags = parseMangaTags(document),
			description = document.selectFirst(".comic-synopsis")?.textOrNull()?.trim()?.nullIfEmpty(),
			chapters = parseChapters(document),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val document = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val pages = document.select("img.chapter-image").mapNotNull { image ->
			val imageUrl = imageFromElement(image) ?: return@mapNotNull null
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}

		return pages
	}

	private fun comicsUrl(page: Int): HttpUrl.Builder = urlBuilder().apply {
		addPathSegment("comic")
		if (page > 1) {
			addPathSegment("page")
			addPathSegment(page.toString())
		}
	}

	private fun parseComicsPage(document: Document): List<Manga> {
		return document.select("article.ac-card").mapNotNull { card ->
			val link = card.selectFirst(".ac-title a") ?: return@mapNotNull null
			val url = link.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val title = link.text().trim().nullIfEmpty() ?: return@mapNotNull null
			Manga(
				id = generateUid(url),
				url = url,
				publicUrl = url.toAbsoluteUrl(domain),
				coverUrl = card.selectFirst(".ac-thumb img")?.let(::imageFromElement),
				title = title,
				altTitles = emptySet(),
				description = null,
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				authors = emptySet(),
				state = null,
				source = source,
				contentRating = null,
			)
		}
	}

	private suspend fun fetchGenres(): Set<MangaTag> {
		val document = webClient.httpGet(comicsUrl(1).build()).parseHtml()
		return parseGenres(document)
	}

	private fun parseGenres(document: Document): Set<MangaTag> {
		return document.select(".ac-filter-group.ac-genre input[name='genres[]']").mapNotNullToSet { input ->
			val slug = input.attr("value").trim()
			val name = input.parent()?.selectFirst(".ac-option-text")?.textOrNull()?.trim().orEmpty()
			if (slug.isBlank() || name.isBlank()) null else MangaTag(
				key = slug,
				title = name,
				source = source,
			)
		}
	}

	private fun parseMangaTags(document: Document): Set<MangaTag> {
		return document.select(".comic-genres .genres .genre").mapNotNullToSet { genre ->
			val title = genre.text().trim().nullIfEmpty() ?: return@mapNotNullToSet null
			val key = genre.attr("href")
				.substringAfterLast('/')
				.substringBefore('?')
				.ifBlank { title.lowercase(Locale.ROOT).replace(' ', '-') }
			MangaTag(
				key = key,
				title = title,
				source = source,
			)
		}
	}

	private suspend fun parseChapters(document: Document): List<MangaChapter> {
		val chapters = LinkedHashMap<String, MangaChapter>()

		fun addAll(items: List<MangaChapter>) {
			for (chapter in items) {
				if (!chapters.containsKey(chapter.url)) {
					chapters[chapter.url] = chapter
				}
			}
		}

		addAll(parseChapterElements(document.select(CHAPTER_SELECTOR)))

		val loadMoreBtn = document.selectFirst("#loadMoreChaptersBtn")
		val comicId = loadMoreBtn?.attrOrNull("data-comic-id").orEmpty()
		val nonce = extractNonce(document).orEmpty()
		var offset = loadMoreBtn?.attrOrNull("data-offset")?.toIntOrNull() ?: 0

		if (offset <= 0) {
			offset = chapters.size
		} else if (chapters.isNotEmpty() && offset > chapters.size) {
			offset = chapters.size
		}

		if (comicId.isNotBlank() && nonce.isNotBlank()) {
			while (true) {
				val items = fetchMoreChapters(comicId, offset, nonce)
				if (items.isEmpty()) break
				addAll(items)
				offset += CHAPTERS_PER_PAGE
			}
		}

		return chapters.values.toList().reversed()
	}

	private fun parseChapterElements(elements: List<Element>): List<MangaChapter> {
		return elements.mapNotNull { element ->
			val permalink = element.attr("data-permalink").trim()
			val href = element.selectFirst("a")?.attr("abs:href").orEmpty().trim()
			val chapterUrlRaw = permalink.ifBlank { href }
			if (chapterUrlRaw.isBlank()) return@mapNotNull null
			val chapterUrl = chapterUrlRaw.toRelativeUrl(domain)

			val chapterTitle = element.selectFirst(".chapter-number")?.textOrNull()?.trim()?.nullIfEmpty()
				?: element.attr("data-title").trim().nullIfEmpty()
				?: return@mapNotNull null

			val locked = isLocked(element)
			if (locked) return@mapNotNull null

			MangaChapter(
				id = generateUid(chapterUrl),
				title = chapterTitle,
				number = parseChapterNumber(chapterTitle),
				volume = 0,
				url = chapterUrl,
				scanlator = null,
				uploadDate = parseDate(element.selectFirst(".chapter-date")?.textOrNull()),
				branch = null,
				source = source,
			)
		}
	}

	private fun isLocked(element: Element): Boolean {
		val reason = element.attr("data-reason").lowercase(Locale.ROOT)
		if (reason.isNotBlank() && reason != "free") return true
		if (element.hasClass("locked-chapter")) return true

		val href = element.selectFirst("a")?.attr("href").orEmpty()
		if (href.isBlank() || href == "#") return true

		return element.selectFirst(".chapter_price") != null
	}

	private suspend fun fetchMoreChapters(
		comicId: String,
		offset: Int,
		nonce: String,
	): List<MangaChapter> {
		val response = webClient.httpPost(
			urlBuilder().addPathSegments("wp-admin/admin-ajax.php").build(),
			mapOf(
				"action" to "load_more_chapters",
				"nonce" to nonce,
				"comic_id" to comicId,
				"offset" to offset.toString(),
			),
			ajaxHeaders,
		).parseJson()

		if (!response.getBooleanOrDefault("success", false)) return emptyList()

		val html = when (val data = response.opt("data")) {
			is JSONObject -> data.getStringOrNull("html")
			is String -> data
			else -> null
		}.orEmpty()

		if (html.isBlank()) return emptyList()

		val document = Jsoup.parseBodyFragment(html)
		return parseChapterElements(document.select(CHAPTER_SELECTOR))
	}

	private fun extractNonce(document: Document): String? {
		return NONCE_REGEX.find(document.html())?.groupValues?.getOrNull(1)
	}

	private fun parseStatus(status: String?): MangaState? {
		return when (status?.trim()?.lowercase(Locale.ROOT)) {
			"ongoing" -> MangaState.ONGOING
			"completed" -> MangaState.FINISHED
			"hiatus" -> MangaState.PAUSED
			"cancelled", "canceled" -> MangaState.ABANDONED
			else -> null
		}
	}

	private fun parseDate(date: String?): Long {
		date ?: return 0L
		return dateFormats.firstNotNullOfOrNull { formatter ->
			formatter.parseSafe(date).takeIf { it != 0L }
		} ?: 0L
	}

	private fun parseChapterNumber(chapterTitle: String): Float {
		return CHAPTER_NUMBER_REGEX.find(chapterTitle)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0f
	}

	private fun imageFromElement(element: Element): String? {
		return element.attrAsAbsoluteUrlOrNull("data-src")
			?: element.attrAsAbsoluteUrlOrNull("data-lazy-src")
			?: element.attrAsAbsoluteUrlOrNull("src")
	}

	private fun sortQuery(order: SortOrder): String? = when (order) {
		SortOrder.UPDATED, SortOrder.NEWEST -> "newest"
		SortOrder.UPDATED_ASC, SortOrder.NEWEST_ASC -> "oldest"
		SortOrder.ALPHABETICAL -> "az"
		SortOrder.ALPHABETICAL_DESC -> "za"
		else -> null
	}

	private val dateFormats = listOf(
		SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH),
		SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH),
	)

	companion object {
		private const val SORT_PARAM = "sort"
		private const val CHAPTER_SELECTOR = "li.chapter"
		private const val CHAPTERS_PER_PAGE = 10
		private val NONCE_REGEX = Regex(
			"""comicworld_ajax\s*=\s*\{[^}]*["']nonce["']\s*:\s*["']([^"']+)["']""",
		)
		private val CHAPTER_NUMBER_REGEX = Regex("""(\d+(?:\.\d+)?)""")
		private val ajaxHeaders = Headers.headersOf("X-Requested-With", "XMLHttpRequest")
	}
}
