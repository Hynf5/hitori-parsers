package org.koitharu.kotatsu.parsers.site.madara.es

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.model.WordSet
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.findGroupValue
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl
import org.koitharu.kotatsu.parsers.util.urlDecode
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.EnumSet

@MangaSourceParser("JEAZTWOBLUESCANS", "Jeaz Scans", "es")
internal class JeazTwoBlueScans(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.JEAZTWOBLUESCANS, pageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain("lectorhub.j5z.xyz")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	private val absoluteDateFormat by lazy {
		SimpleDateFormat("dd MMM, yyyy", sourceLocale)
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Referer", "https://$domain/")
		.build()

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = if (filter.query.isNullOrEmpty()) {
			"https://$domain/directorio.php?page=$page"
		} else {
			"https://$domain/buscar.php".toHttpUrl().newBuilder()
				.addQueryParameter("q", filter.query)
				.addQueryParameter("page", page.toString())
				.build()
				.toString()
		}
		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain), getRequestHeaders()).parseHtml()
		val chapters = doc.select("a[href*='/leer/'][href*='/capitulo-']")
			.mapNotNull(::parseChapter)
			.distinctBy { it.url }
			.sortedWith(compareBy<MangaChapter> { it.number }.thenBy { it.uploadDate })

		return manga.copy(
			title = parseTitle(doc) ?: manga.title,
			coverUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.nullIfEmpty() ?: manga.coverUrl,
			description = parseDescription(doc),
			tags = doc.select("a[href*='directorio.php?genero=']").mapNotNullToSet { a ->
				val href = a.attr("href")
				val key = href.substringAfter("genero=", "").urlDecode().nullIfEmpty() ?: return@mapNotNullToSet null
				val title = a.textOrNull() ?: return@mapNotNullToSet null
				MangaTag(
					key = key,
					title = title,
					source = source,
				)
			},
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain), getRequestHeaders()).parseHtml()
		return doc.select("img.protected-img[data-verify], img[data-verify]")
			.mapNotNull { img ->
				val encoded = img.attr("data-verify").nullIfEmpty() ?: return@mapNotNull null
				val decoded = runCatching {
					context.decodeBase64(encoded).toString(Charsets.UTF_8)
				}.getOrNull() ?: return@mapNotNull null
				val url = decoded.reversed().toRelativeUrl(domain)
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			}
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select("a[href*='manga.php?id=']:has(img)")
			.mapNotNull(::parseCard)
			.distinctBy { it.url }
	}

	private fun parseCard(element: Element): Manga? {
		val href = element.attrAsRelativeUrl("href")
		val title = element.selectFirst("h3, h2")?.textOrNull()?.trim()?.nullIfEmpty() ?: return null
		return Manga(
			id = generateUid(href),
			title = title,
			altTitles = emptySet(),
			url = href,
			publicUrl = href.toAbsoluteUrl(domain),
			rating = RATING_UNKNOWN,
			contentRating = ContentRating.SAFE,
			coverUrl = element.selectFirst("img")?.src(),
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source,
		)
	}

	private fun parseTitle(doc: Document): String? {
		return doc.selectFirst("h1")?.textOrNull()?.trim()?.nullIfEmpty()
			?: doc.selectFirst("meta[property=og:title]")?.attr("content")
				?.substringBefore(" - JeazScans")
				?.trim()
				?.nullIfEmpty()
	}

	private fun parseDescription(doc: Document): String? {
		val synopsisSection = doc.selectFirst("section:has(h2:matchesOwn((?i)SINOPSIS:?)), div:has(h2:matchesOwn((?i)SINOPSIS:?))")
		val paragraphs = synopsisSection?.select("p")?.eachText()?.filter { it.isNotBlank() }.orEmpty()
		return paragraphs.joinToString("\n").nullIfEmpty()
			?: doc.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim()?.nullIfEmpty()
	}

	private fun parseChapter(element: Element): MangaChapter? {
		val href = element.attrAsRelativeUrl("href")
		val chapterNumber = parseChapterNumber(href, element.text())
		return MangaChapter(
			id = generateUid(href),
			title = null,
			number = chapterNumber,
			volume = 0,
			url = href,
			scanlator = null,
			uploadDate = parseChapterDate(absoluteDateFormat, element.text()),
			branch = null,
			source = source,
		)
	}

	private fun parseChapterNumber(url: String, text: String): Float {
		val fromUrl = url.findGroupValue(CHAPTER_NUMBER_REGEX)
			?.replace(Regex("(?<=\\d)-(?=\\d)"), ".")
			?.toFloatOrNull()
		if (fromUrl != null) {
			return fromUrl
		}
		return text.findGroupValue(TEXT_CHAPTER_NUMBER_REGEX)
			?.replace(',', '.')
			?.toFloatOrNull()
			?: 0f
	}

	private fun parseChapterDate(dateFormat: DateFormat, text: String): Long {
		val relativeText = text.findGroupValue(RELATIVE_DATE_REGEX)
		if (!relativeText.isNullOrEmpty()) {
			return parseRelativeDate(relativeText)
		}
		val absoluteText = text.findGroupValue(ABSOLUTE_DATE_REGEX)
		return dateFormat.parseSafe(absoluteText)
	}

	private fun parseRelativeDate(text: String): Long {
		val number = NUMBER_REGEX.find(text)?.value?.toIntOrNull() ?: return 0L
		val calendar = Calendar.getInstance()
		return when {
			WordSet("segundo", "segundos").anyWordIn(text) -> calendar.apply { add(Calendar.SECOND, -number) }.timeInMillis
			WordSet("minuto", "minutos", "min").anyWordIn(text) -> calendar.apply { add(Calendar.MINUTE, -number) }.timeInMillis
			WordSet("hora", "horas", "hr", "hrs").anyWordIn(text) -> calendar.apply { add(Calendar.HOUR, -number) }.timeInMillis
			WordSet("dia", "dias", "día", "días").anyWordIn(text) -> calendar.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis
			WordSet("semana", "semanas").anyWordIn(text) -> calendar.apply { add(Calendar.WEEK_OF_YEAR, -number) }.timeInMillis
			WordSet("mes", "meses").anyWordIn(text) -> calendar.apply { add(Calendar.MONTH, -number) }.timeInMillis
			WordSet("año", "años").anyWordIn(text) -> calendar.apply { add(Calendar.YEAR, -number) }.timeInMillis
			else -> 0L
		}
	}

	private companion object {
		private val CHAPTER_NUMBER_REGEX = Regex("""/capitulo-([0-9]+(?:[.-][0-9]+)?)""", RegexOption.IGNORE_CASE)
		private val TEXT_CHAPTER_NUMBER_REGEX = Regex("""cap(?:[íi]tulo)?\s*([0-9]+(?:[.,][0-9]+)?)""", RegexOption.IGNORE_CASE)
		private val RELATIVE_DATE_REGEX = Regex("""(?i)(hace\s+\d+\s+(?:segundos?|min(?:utos?)?|horas?|hrs?|d[ií]as?|semanas?|mes(?:es)?|años?))""")
		private val ABSOLUTE_DATE_REGEX = Regex("""(\d{1,2}\s+[[:alpha:]ÁÉÍÓÚáéíóúñÑ]{3,}\s*,\s*\d{4})""")
		private val NUMBER_REGEX = Regex("""\d+""")
	}
}
