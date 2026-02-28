package org.koitharu.kotatsu.parsers.site.zeistmanga.id

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.site.zeistmanga.ZeistMangaParser
import org.koitharu.kotatsu.parsers.util.*

@MangaSourceParser("MIKOROKU", "Mikoroku", "id", ContentType.HENTAI)
internal class Mikoroku(context: MangaLoaderContext) :
	ZeistMangaParser(context, MangaParserSource.MIKOROKU, "www.mikoroku.my.id") {

	override suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain").parseHtml()
		return doc.requireElementById("Genre").select("div.items-center").mapToSet {
			MangaTag(
				key = it.selectFirstOrThrow("input").attr("value"),
				title = it.selectFirstOrThrow("label").text().substringBefore('('),
				source = source,
			)
		}
	}

	override suspend fun loadChapters(mangaUrl: String, doc: Document): List<MangaChapter> {
		val elements = doc.select("div#chapterContainer a.chap-btn")
		
		return elements.map { element ->
			val chapUrl = element.attr("href") 

			MangaChapter(
				id = generateUid(chapUrl),
				title = element.selectFirst(".chap-num")?.text() ?: element.text().trim(),
				number = -1f,
				volume = 0,
				scanlator = null,
				uploadDate = 0L,
				branch = null,
				url = chapUrl,
				source = source
			)
		}.reversed()
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		var doc = webClient.httpGet(fullUrl).parseHtml()

		val redirectScript = doc.selectFirst("script:containsData(window.location.replace), script:containsData(window.location.href)")
		if (redirectScript != null) {
			val match = Regex("""window\.location\.(?:replace|href)\s*=\s*['"]([^'"]+)['"]""").find(redirectScript.data())
			if (match != null) {
				doc = webClient.httpGet(match.groupValues[1]).parseHtml()
			}
		}

		return doc.select("div.separator img, div.max-w img").map { img ->
			val url = img.attr("data-src").ifEmpty { img.attr("data-lazy-src") }.ifEmpty { img.attr("src") }
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = url,
				source = source
			)
		}
	}
    }
    
