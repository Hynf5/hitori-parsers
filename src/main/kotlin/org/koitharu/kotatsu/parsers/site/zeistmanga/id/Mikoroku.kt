package org.koitharu.kotatsu.parsers.site.zeistmanga.id

import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.zeistmanga.ZeistMangaParser
import org.koitharu.kotatsu.parsers.util.*
import java.net.URLDecoder

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

    // 🔥 KITA BUAT PARSER CHAPTER SENDIRI (Membunuh fungsi bawaan Kotatsu)
    override suspend fun loadChapters(mangaUrl: String, doc: Document): List<MangaChapter> {
        val cleanUrl = mangaUrl.substringBefore("?m=1")
        val fullUrl = if (cleanUrl.startsWith("http")) cleanUrl else "https://$domain$cleanUrl"
        val desktopDoc = webClient.httpGet(fullUrl).parseHtml()

        // 1. Kumpulin semua label yang ada di halaman
        val tagElements = desktopDoc.select("a[href*=/search/label/], a[rel=tag]")
        val labels = tagElements.mapNotNull {
            val href = it.attr("href")
            if (href.contains("/search/label/")) {
                href.substringAfter("/search/label/").substringBefore("?").substringBefore("&")
            } else null
        }

        // 2. Buang tag sampah biar kejadian error "R18" kemarin nggak terulang
        val ignoreList = listOf(
            "manga", "manhwa", "manhua", "completed", "ongoing", "project", 
            "series", "hentai", "doujinshi", "webtoon", "color", "uncensored", 
            "r18", "r-18", "18+", "18", "adult", "mature", "smut", "gore"
        )

        // 3. Ambil sisa label yang paling panjang (ini pasti judul komiknya)
        val exactLabel = labels.filter { encodedLabel ->
            val decoded = URLDecoder.decode(encodedLabel, "UTF-8").lowercase()
            ignoreList.none { ignore -> decoded == ignore }
        }.maxByOrNull { it.length } 
        ?: throw ParseException("Gagal menemukan label judul komik dari HTML", fullUrl)

        // 4. Tembak API JSON langsung ke MIKODRIVE
        val apiUrl = "https://www.mikodrive.my.id/feeds/posts/default/-/$exactLabel?alt=json&max-results=999"
        val jsonResponse = webClient.httpGet(apiUrl).body?.string()
            ?: throw ParseException("Gagal narik API Mikodrive", apiUrl)

        val json = JSONObject(jsonResponse)
        val feed = json.optJSONObject("feed")
        val entries = feed?.optJSONArray("entry")

        if (entries == null || entries.length() == 0) {
            throw ParseException("API Mikodrive kosong untuk label: $exactLabel", apiUrl)
        }

        val chapters = mutableListOf<MangaChapter>()
        
        // 5. Ubah JSON jadi list Chapter
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val titleObj = entry.optJSONObject("title")
            val title = titleObj?.optString("$" + "t") ?: "Chapter ${i + 1}"
            
            var chapterUrl = ""
            val links = entry.optJSONArray("link")
            if (links != null) {
                for (j in 0 until links.length()) {
                    val link = links.getJSONObject(j)
                    if (link.optString("rel") == "alternate") {
                        chapterUrl = link.optString("href")
                        break
                    }
                }
            }

            // Bersihin URL dari semua domain
            chapterUrl = chapterUrl.substringBefore("?m=1")
                .removePrefix("https://www.mikodrive.my.id")
                .removePrefix("http://www.mikodrive.my.id")
                .removePrefix("https://www.mikoroku.my.id")
                .removePrefix("http://www.mikoroku.my.id")

            chapters.add(
                MangaChapter(
                    id = i.toLong(),
                    title = title,
                    number = -1f,
                    volume = 0,
                    scanlator = "",
                    uploadDate = 0L,
                    branch = "",
                    url = chapterUrl,
                    source = source
                )
            )
        }

        return chapters
    }

    // 🔥 AMBIL GAMBAR DARI MIKODRIVE
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = if (chapter.url.startsWith("http")) {
            chapter.url 
        } else {
            "https://www.mikodrive.my.id${chapter.url}"
        }.substringBefore("?m=1")

        val doc = webClient.httpGet(fullUrl).parseHtml()

        val images = doc.select("div.max-w img, div#readerarea img, div.post-body img")
        
        if (images.isEmpty()) {
            throw ParseException("Gagal menemukan gambar komik di Mikodrive", fullUrl)
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
    
