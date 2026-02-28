package org.koitharu.kotatsu.parsers.site.zeistmanga.id

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

    // KITA HAPUS override loadChapters! 
    // Biarkan ZeistMangaParser bawaan yang ngerjain tugasnya, karena sistem aslinya udah bener!

    // 🔥 FOKUS DI getPages: Menangani redirect Javascript dari Mikoroku ke Mikodrive
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = if (chapter.url.startsWith("http")) {
            chapter.url
        } else {
            "https://$domain${chapter.url}"
        }

        var doc = webClient.httpGet(fullUrl).parseHtml()

        // 1. Deteksi JavaScript Redirect ke Mikodrive!
        val scripts = doc.select("script")
        for (script in scripts) {
            val data = script.data()
            if (data.contains("window.location.replace") || data.contains("window.location.href")) {
                // Ekstrak URL Mikodrive dari dalam script menggunakan Regex
                val match = Regex("""window\.location\.(?:replace|href)\s*=\s*['"]([^'"]+)['"]""").find(data)
                if (match != null) {
                    val redirectUrl = match.groupValues[1]
                    // Temu! Download ulang halaman tapi sekarang dari URL Mikodrive
                    doc = webClient.httpGet(redirectUrl).parseHtml()
                    break
                }
            }
        }

        // 2. Sekarang kita udah ada di Mikodrive, tinggal comot gambarnya dari div.max-w
        val images = doc.select("div.max-w img, div#readerarea img, div.post-body img")
        
        if (images.isEmpty()) {
            throw ParseException("Gagal menemukan gambar komik, pastikan redirect berhasil", fullUrl)
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
    
