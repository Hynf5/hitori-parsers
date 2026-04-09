package org.koitharu.kotatsu.parsers.site.madara.en

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
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
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.attrAsAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseRaw
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.text.SimpleDateFormat
import java.util.EnumSet

@MangaSourceParser("PHILIASCANS", "Philia Scans", "en")
internal class PhiliaScans(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.PHILIASCANS, "philiascans.org") {

	private var liveSearchNonce: String? = null

	override val listUrl = "series"

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.RELEVANCE,
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		if (!filter.query.isNullOrEmpty()) {
			return if (page <= 1) {
				searchByAjax(filter.query)
			} else {
				emptyList()
			}
		}

		val url = buildString {
			append("https://")
			append(domain)

			when (order) {
				SortOrder.UPDATED -> {
					append("/recently-updated/?page=")
					append(page)
				}

				else -> {
					append("/?post_type=wp-manga&s=&paged=")
					append(page)
					when (order) {
						SortOrder.POPULARITY -> append("&sort=most_viewed")
						SortOrder.NEWEST -> append("&sort=recently_added")
						SortOrder.ALPHABETICAL -> append("&sort=title_az")
						else -> Unit
					}
				}
			}
		}
		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	override fun parseMangaList(doc: Document): List<Manga> {
		return doc.select("div.original.card-lg div.unit").mapNotNull { unit ->
			val titleLink = unit.selectFirst("a.c-title") ?: return@mapNotNull null
			val relativeUrl = titleLink.attrAsRelativeUrl("href")
			Manga(
				id = generateUid(relativeUrl),
				url = relativeUrl,
				publicUrl = titleLink.attrAsAbsoluteUrl("href"),
				coverUrl = normalizeCoverUrl(unit.selectFirst("img:not(.flag-icon)")?.let(::imageUrlFromElement)),
				title = titleLink.text().trim(),
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

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
		val chapters = doc.select("div#free-list li.item.free-chapter, div#free-list li.item.free-chap").mapIndexedNotNull { index, element ->
			parseChapter(element, index, dateFormat)
		}.reversed()

		return manga.copy(
			title = doc.selectFirst("h1.serie-title")?.textOrNull()?.trim()?.nullIfEmpty() ?: manga.title,
			altTitles = doc.selectFirst("h6.alternative-title")?.textOrNull()
				?.split('•')
				?.mapNotNull { it.trim().nullIfEmpty() }
				?.toSet()
				.orEmpty(),
			description = doc.selectFirst("div.description-content")?.html(),
			coverUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.nullIfEmpty()
				?: doc.selectFirst(".main-cover img:not(.flag-icon), .main-cover .cover img:not(.flag-icon), .summary_image img:not(.flag-icon)")
					?.let(::imageUrlFromElement)
				?: manga.coverUrl,
			tags = doc.select("div.genre-list a").mapNotNullToSet(::createTag),
			authors = setOfNotNull(
				findStatValue(doc, "Author"),
				findStatValue(doc, "Artist"),
			),
			state = parseState(findStatValue(doc, "Status")),
			chapters = chapters,
		)
	}

	private suspend fun searchByAjax(query: String): List<Manga> {
		val response = webClient.httpPost(
			url = "https://$domain/wp-admin/admin-ajax.php".toHttpUrl(),
			payload = buildString {
				append("action=live_search")
				append("&security=")
				append(getLiveSearchNonce())
				append("&search_query=")
				append(query.urlEncoded())
			},
			extraHeaders = Headers.Builder()
				.add("Referer", "https://$domain/")
				.add("X-Requested-With", "XMLHttpRequest")
				.build(),
		).parseRaw()
		val results = JSONObject(response).optJSONArray("results") ?: return emptyList()
		return buildList(results.length()) {
			for (i in 0 until results.length()) {
				val html = results.optString(i).nullIfEmpty() ?: continue
				val card = Jsoup.parseBodyFragment(html, "https://$domain").body().selectFirst("a.search-result-card")
					?: continue
				parseSearchCard(card)?.let(::add)
			}
		}
	}

	private suspend fun getLiveSearchNonce(): String {
		liveSearchNonce?.let { return it }
		val html = webClient.httpGet("https://$domain/").parseRaw()
		val nonceJson = LIVE_SEARCH_DATA_REGEX.find(html)?.groupValues?.getOrNull(1)
		val nonce = nonceJson
			?.let(::JSONObject)
			?.optString("nonce")
			?.nullIfEmpty()
			?: error("Missing live search nonce")
		liveSearchNonce = nonce
		return nonce
	}

	private fun parseSearchCard(card: Element): Manga? {
		val relativeUrl = card.attrAsRelativeUrl("href")
		val title = card.selectFirst(".search-result-title")?.textOrNull()?.trim()?.nullIfEmpty()
			?: card.selectFirst("img")?.attr("alt")?.trim()?.nullIfEmpty()
			?: return null
		val cover = card.selectFirst("img:not(.flag-icon)")?.let(::imageUrlFromElement)
		return Manga(
			id = generateUid(relativeUrl),
			url = relativeUrl,
			publicUrl = card.attrAsAbsoluteUrl("href"),
			coverUrl = normalizeCoverUrl(cover),
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

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		return doc.select("div#ch-images img").mapNotNull { img ->
			val url = imageUrlFromElement(img)?.substringBefore('#')?.nullIfEmpty() ?: return@mapNotNull null
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private fun parseChapter(element: Element, index: Int, dateFormat: SimpleDateFormat): MangaChapter? {
		val link = element.selectFirst("a") ?: return null
		val relativeUrl = link.attrAsRelativeUrl("href")
		val title = element.selectFirst("zebi")?.textOrNull()
			?.replace(Regex("""\s+"""), " ")
			?.substringBefore(':')
			?.trim()
			?.nullIfEmpty()
			?: link.textOrNull()?.trim()?.nullIfEmpty()
			?: return null
		val chapterText = element.selectFirst(".time")?.textOrNull()
		return MangaChapter(
			id = generateUid(relativeUrl),
			url = relativeUrl,
			title = title,
			number = CHAPTER_NUMBER_REGEX.find(title)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: (index + 1).toFloat(),
			volume = 0,
			branch = null,
			uploadDate = parseChapterDate(dateFormat, chapterText),
			scanlator = null,
			source = source,
		)
	}

	private fun findStatValue(doc: Document, label: String): String? {
		return doc.select(".stat-item").firstNotNullOfOrNull { item ->
			val key = item.selectFirst(".stat-label")?.textOrNull()?.trim()?.removeSuffix(":")
			if (!key.equals(label, ignoreCase = true)) {
				return@firstNotNullOfOrNull null
			}
			item.selectFirst(".stat-value, .manga")?.textOrNull()?.trim()?.nullIfEmpty()
		}
	}

	private fun parseState(value: String?): MangaState? = when (value?.trim()?.lowercase(sourceLocale)) {
		"releasing", "ongoing" -> MangaState.ONGOING
		"completed", "complete" -> MangaState.FINISHED
		"on hold", "hiatus" -> MangaState.PAUSED
		"canceled", "cancelled", "dropped" -> MangaState.ABANDONED
		else -> null
	}

	private fun createTag(a: Element): MangaTag? {
		val href = a.attr("href").removeSuffix("/")
		val key = href.substringAfterLast('/').nullIfEmpty() ?: return null
		val title = a.textOrNull()?.trim()?.nullIfEmpty() ?: return null
		return MangaTag(
			key = key,
			title = title,
			source = source,
		)
	}

	private fun imageUrlFromElement(element: Element): String? {
		return element.attr("abs:data-src").ifBlank {
			element.attr("abs:src")
		}.nullIfEmpty()
	}

	private fun normalizeCoverUrl(url: String?): String? {
		return url?.replace(Regex("""-\d+x\d+(?=\.)"""), "")
	}

	private companion object {
		private val CHAPTER_NUMBER_REGEX = Regex("""(\d+(?:\.\d+)?)""")
		private val LIVE_SEARCH_DATA_REGEX = Regex("""var\s+liveSearchData\s*=\s*(\{.*?\});""")
	}
}
