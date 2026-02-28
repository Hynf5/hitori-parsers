package org.koitharu.kotatsu.parsers.site.zeistmanga.id

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
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
        val cleanUrl = mangaUrl.substringBefore("?m=1")
        val fullUrl = if (cleanUrl.startsWith("http")) cleanUrl else "https://$domain$cleanUrl"
        
        val desktopDoc = webClient.httpGet(fullUrl).parseHtml()

        val chapterElements = desktopDoc.select("div#chapterContainer a.chap-btn, #chapterlist li a, .eplister li a")
        
        if (chapterElements.isEmpty()) {
            throw ParseException("Gagal menemukan chapter list", fullUrl)
        }

        val chapters = chapterElements.mapIndexed { index, element ->
            val title = element.selectFirst(".chap-num")?.text() ?: element.text()
            
            var chapUrl = element.attr("href").substringBefore("?m=1")
            
            // PERBAIKAN: HANYA hapus domain mikoroku. 
            // Kalau linknya mikodrive, biarin aja biar Kotatsu langsung nembak ke sana!
            chapUrl = chapUrl.removePrefix("https://www.mikoroku.my.id")
                             .removePrefix("http://www.mikoroku.my.id")

            MangaChapter(
                id = index.toLong(),
                title = title.trim(),
                number = -1f,
                volume = 0,
                scanlator = "",
                uploadDate = 0L,
                branch = "",
                url = chapUrl,
                source = source
            )
        }

        return chapters.reversed() 
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        // Karena chapUrl Mikodrive nggak kita hapus, dia bakal masuk ke kondisi startsWith("http") ini
        val fullUrl = if (chapter.url.startsWith("http")) {
            chapter.url 
        } else {
            "https://$domain${chapter.url}" 
        }

        var doc = webClient.httpGet(fullUrl).parseHtml()

        // Jaga-jaga kalau ada JS Redirect
        val scripts = doc.select("script")
        for (script in scripts) {
            val data = script.data()
            if (data.contains("window.location.replace") || data.contains("window.location.href")) {
                val match = Regex("""window\.location\.(?:replace|href)\s*=\s*['"]([^'"]+)['"]""").find(data)
                if (match != null) {
                    val redirectUrl = match.groupValues[1]
                    if (redirectUrl.startsWith("http")) {
                        doc = webClient.httpGet(redirectUrl).parseHtml()
                        break
                    }
                }
            }
        }

        // Ambil gambar
        val images = doc.select("div.separator img, div.max-w img, div#readerarea img, div.post-body img")
        
        if (images.isEmpty()) {
            throw ParseException("Gagal menemukan gambar komik", fullUrl)
        }

        return images.filter { 
            val src = it.attr("src")
            !src.contains("icon") && !src.contains("logo") && !src.contains("profile")
        }.mapIndexed { index, element ->
            val url = element.attr("data-src")
                .ifEmpty { element.attr("data-lazy-src") }
                .ifEmpty { element.attr("src") }

            MangaPage(
                id = index.toLong(),
                url = url,
                preview = url,
                source = source
            )
        }
    }
    }
    
