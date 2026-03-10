package org.koitharu.kotatsu.parsers.site.mangareader.id

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
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
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.attrOrNull
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("BACAKOMIK", "BacaKomik", "id")
internal class BacaKomik(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.BACAKOMIK, "bacakomik.my", pageSize = 30, searchPageSize = 30) {

	override val listUrl = "/daftar-komik"
	override val selectMangaList = "div.animepost"
	override val selectMangaListImg = "div.limit img"
	override val selectMangaListTitle = ".animposx .tt h4"
	override val selectChapter = "#chapter_list li"

	private val chapterDateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isYearSupported = true,
			isAuthorSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
			ContentType.COMICS,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val base = buildString {
			append("https://")
			append(domain)
			append(listUrl)
			append('/')
			if (page > 1) {
				append("page/")
				append(page)
				append('/')
			}
		}
		val url = base.toHttpUrl().newBuilder().apply {
			addQueryParameter(
				"order",
				when (order) {
					SortOrder.ALPHABETICAL -> "title"
					SortOrder.ALPHABETICAL_DESC -> "titlereverse"
					SortOrder.UPDATED -> "update"
					SortOrder.NEWEST -> "latest"
					SortOrder.POPULARITY -> "popular"
					else -> ""
				},
			)
			filter.query?.let { addQueryParameter("title", it) }
			filter.author?.let { addQueryParameter("author", it) }
			if (filter.year != 0) {
				addQueryParameter("yearx", filter.year.toString())
			}
			filter.states.oneOrThrowIfMany()?.let {
				addQueryParameter(
					"status",
					when (it) {
						MangaState.FINISHED -> "completed"
						MangaState.ONGOING -> "ongoing"
						else -> ""
					},
				)
			}
			filter.types.oneOrThrowIfMany()?.let {
				addQueryParameter(
					"type",
					when (it) {
						ContentType.MANGA -> "Manga"
						ContentType.MANHWA -> "Manhwa"
						ContentType.MANHUA -> "Manhua"
						ContentType.COMICS -> "Comic"
						else -> ""
					},
				)
			}
			filter.tags.forEach { tag ->
				addQueryParameter("genre[]", tag.key)
			}
		}.build()

		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val docs = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

		val infoElement = docs.selectFirst("div.infoanime")
		val descElement = docs.selectFirst("div.desc > .entry-content.entry-content-single")

		val tags = infoElement?.select(".infox > .genre-info > a, .infox .spe span:contains(Jenis Komik) a")
			.orEmpty()
			.mapTo(mutableSetOf()) { a ->
				MangaTag(
					key = a.attr("href").substringAfterLast('/').substringBefore('?'),
					title = a.text(),
					source = source,
				)
			}

		val statusText = docs.selectFirst(".infox .spe span:contains(Status)")?.text().orEmpty()
		val state = when {
			statusText.contains("berjalan", ignoreCase = true) -> MangaState.ONGOING
			statusText.contains("tamat", ignoreCase = true) -> MangaState.FINISHED
			else -> null
		}

		val author = docs.selectFirst(".infox .spe span:contains(Author) :not(b)")?.text()
		val artist = docs.selectFirst(".infox .spe span:contains(Artis) :not(b)")?.text()

		val chapters = docs.select(selectChapter).mapChapters(reversed = true) { i, element ->
			val a = element.selectFirst("span.lchx > a") ?: element.selectFirst("a") ?: return@mapChapters null
			val chapterUrl = a.attrAsRelativeUrl("href")
			val chapterName = a.text()
			val parsedNumber = Regex("""chapter\s+([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)
				.find(chapterName)
				?.groupValues
				?.getOrNull(1)
				?.toFloatOrNull()

			MangaChapter(
				id = generateUid(chapterUrl),
				title = chapterName,
				url = chapterUrl,
				number = parsedNumber ?: (i + 1f),
				volume = 0,
				scanlator = null,
				uploadDate = parseChapterDate(element.selectFirst("span.dt a, span.dt")?.text()),
				branch = null,
				source = source,
			)
		}

		val descriptionText = descElement?.select("p")?.text()?.trim().orEmpty()
		val description = descriptionText.substringAfter("bercerita tentang ", descriptionText)

		return manga.copy(
			title = docs.select("#breadcrumbs li:last-child span").text().ifBlank { manga.title },
			description = description.ifBlank { null },
			state = state,
			authors = setOfNotNull(author, artist).filter { it.isNotBlank() }.toSet(),
			tags = tags,
			coverUrl = docs.selectFirst(".thumb > img:nth-child(1)")?.src() ?: manga.coverUrl,
			chapters = chapters,
			contentRating = if (isNsfwSource) ContentRating.ADULT else manga.contentRating,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val docs = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		return docs.select("div:has(>img[alt*=Chapter]) img")
			.filter { element -> element.parent()?.tagName() != "noscript" }
			.mapNotNull { element ->
				val fallbackFromError = element.attrOrNull("onError")?.substringAfter("src='")?.substringBefore("';")
					?: element.attrOrNull("onerror")?.substringAfter("src='")?.substringBefore("';")
				val pageUrl = (fallbackFromError?.takeIf { it.isNotBlank() } ?: element.src())
					?.takeIf { it.isNotBlank() }
					?: return@mapNotNull null
				MangaPage(
					id = generateUid(pageUrl),
					url = pageUrl.toRelativeUrl(domain),
					preview = null,
					source = source,
				)
			}
	}

	private fun parseChapterDate(date: String?): Long {
		date ?: return 0
		val normalized = date.lowercase()
		return when {
			normalized.contains("yang lalu") -> parseRelativeDate(normalized)
			normalized.contains("hari ini") -> Calendar.getInstance().timeInMillis
			normalized.contains("kemarin") -> Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -1) }.timeInMillis
			else -> chapterDateFormat.parseSafe(date)
		}
	}

	private fun parseRelativeDate(date: String): Long {
		val value = Regex("""(\d+)""").find(date)?.value?.toIntOrNull() ?: return 0
		return when {
			"detik" in date -> Calendar.getInstance().apply { add(Calendar.SECOND, -value) }.timeInMillis
			"menit" in date -> Calendar.getInstance().apply { add(Calendar.MINUTE, -value) }.timeInMillis
			"jam" in date -> Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, -value) }.timeInMillis
			"hari" in date -> Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -value) }.timeInMillis
			"minggu" in date -> Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -(value * 7)) }.timeInMillis
			"bulan" in date -> Calendar.getInstance().apply { add(Calendar.MONTH, -value) }.timeInMillis
			"tahun" in date -> Calendar.getInstance().apply { add(Calendar.YEAR, -value) }.timeInMillis
			else -> 0
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain$listUrl").parseHtml()
		return doc.select("ul.dropdown-menu.c4 li input[name='genre[]']").mapNotNull { input ->
			val value = input.attr("value")
			val label = input.nextElementSibling()?.text()
			if (value.isNotBlank() && !label.isNullOrBlank()) {
				MangaTag(
					key = value,
					title = label,
					source = source,
				)
			} else {
				null
			}
		}.toSet()
	}
}
