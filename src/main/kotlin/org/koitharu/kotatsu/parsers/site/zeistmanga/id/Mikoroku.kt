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

    // 🔥 HACK ULTIMATE: Adaptasi Logika Tachiyomi (Direct HTML Parsing)
    override suspend fun loadChapters(mangaUrl: String, doc: Document): List<MangaChapter> {
        val cleanUrl = mangaUrl.substringBefore("?m=1")
        val fullUrl = if (cleanUrl.startsWith("http")) cleanUrl else "https://$domain$cleanUrl"
        
        // Ambil halaman HTML manga
        val desktopDoc = webClient.httpGet(fullUrl).parseHtml()

        // SIKAT LANGSUNG pakai selector Tachiyomi!
        val chapterElements = desktopDoc.select("div#chapterContainer a.chap-btn")
        
        if (chapterElements.isEmpty()) {
            throw ParseException("Gagal menemukan chapter list dengan selector div#chapterContainer a.chap-btn", fullUrl)
        }

        val chapters = chapterElements.mapIndexed { index, element ->
            // Ambil nomor chapter atau teksnya (Sama persis kayak Tachiyomi)
            val title = element.selectFirst(".chap-num")?.text() ?: element.text()
            
            // Bersihin URL biar Kotatsu nyimpen path-nya dengan bener
            var chapUrl = element.attr("href").substringBefore("?m=1")
            chapUrl = chapUrl.removePrefix("https://www.mikoroku.my.id")
                             .removePrefix("http://www.mikoroku.my.id")
                             .removePrefix("https://www.mikodrive.my.id")
                             .removePrefix("http://www.mikodrive.my.id")

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

        // Kotatsu butuh dibalik (reversed) karena biasanya HTML nampilin dari yang terbaru di atas
        return chapters.reversed() 
    }

    // 🔥 HACK GAMBAR: Gabungan JS Redirect Kotatsu & Selector Tachiyomi
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = if (chapter.url.startsWith("http")) {
            chapter.url 
        } else {
            // Kita coba akses ke mikoroku dulu, karena biasanya JS redirect ada di sana
            "https://$domain${chapter.url}" 
        }

        var doc = webClient.httpGet(fullUrl).parseHtml()

        // Tangani kalau ada JS Redirect ke Mikodrive
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

        // Selector gabungan: Penemuan kita (.max-w) + Penemuan Tachiyomi (div.separator)
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
    
