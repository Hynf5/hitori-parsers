package org.koitharu.kotatsu.parsers.site.zeistmanga.id

import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.zeistmanga.ZeistMangaParser
import org.koitharu.kotatsu.parsers.util.*
import java.net.URLDecoder
import java.net.URLEncoder

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

    // 🔥 HACK ULTIMATE: Parser Chapter Mandiri (Membunuh fungsi bawaan ZeistManga)
    override suspend fun loadChapters(mangaUrl: String, doc: Document): List<MangaChapter> {
        val cleanUrl = mangaUrl.substringBefore("?m=1")
        val fullUrl = if (cleanUrl.startsWith("http")) cleanUrl else "https://$domain$cleanUrl"
        val desktopDoc = webClient.httpGet(fullUrl).parseHtml()

        val chapters = mutableListOf<MangaChapter>()

        // STRATEGI 1: Nyolong dari HTML DOM (Siapa tau adminnya ngerender chapter di server)
        val domChapters = desktopDoc.select("#chapterlist li a, .eplister li a, .clstyle li a, .list-manga li a")
        if (domChapters.isNotEmpty()) {
            domChapters.forEachIndexed { index, element ->
                var chapUrl = element.attr("href").substringBefore("?m=1")
                chapUrl = chapUrl.removePrefix("https://www.mikoroku.my.id").removePrefix("http://www.mikoroku.my.id")
                                 .removePrefix("https://www.mikodrive.my.id").removePrefix("http://www.mikodrive.my.id")
                chapters.add(MangaChapter(
                    id = index.toLong(),
                    title = element.text().trim(),
                    number = -1f,
                    volume = 0,
                    scanlator = "",
                    uploadDate = 0L,
                    branch = "",
                    url = chapUrl,
                    source = source
                ))
            }
            return chapters.reversed() // Kotatsu butuh urutan yang benar
        }

        // STRATEGI 2 & 3: Ekstrak Label Pasti (Anti-Bodoh R18/School Life)
        var exactLabel = ""
        
        // Target 1: Curi dari atribut data-label bawaan ZeistManga
        val dataLabelElement = desktopDoc.selectFirst("[data-label]")
        if (dataLabelElement != null) {
            exactLabel = dataLabelElement.attr("data-label")
        }

        // Target 2: Curi langsung dari URL API JSON yang nyempil di script
        if (exactLabel.isEmpty()) {
            val scripts = desktopDoc.select("script")
            for (script in scripts) {
                val match = Regex("""/feeds/posts/default/-/([^?'"&]+)""").find(script.data())
                if (match != null) {
                    val found = match.groupValues[1]
                    if (!found.equals("Series", true) && !found.equals("Manga", true)) {
                        exactLabel = URLDecoder.decode(found, "UTF-8")
                        break
                    }
                }
            }
        }

        // Target 3: Fallback ke Judul (H1)
        if (exactLabel.isEmpty()) {
            exactLabel = desktopDoc.selectFirst("h1")?.text()?.trim() ?: ""
        }

        if (exactLabel.isEmpty()) {
            throw ParseException("Gagal total nge-ekstrak label dari web Mikoroku", fullUrl)
        }

        val encodedLabel = URLEncoder.encode(exactLabel, "UTF-8").replace("+", "%20")
        
        // 🔥 EKSEKUSI API: Coba Mikoroku dulu, kalau kosong kita dobrak Mikodrive!
        var apiUrl = "https://www.mikoroku.my.id/feeds/posts/default/-/$encodedLabel?alt=json&max-results=999"
        var jsonResponse = webClient.httpGet(apiUrl).body?.string()
        var entries = JSONObject(jsonResponse ?: "{}").optJSONObject("feed")?.optJSONArray("entry")

        if (entries == null || entries.length() == 0) {
            apiUrl = "https://www.mikodrive.my.id/feeds/posts/default/-/$encodedLabel?alt=json&max-results=999"
            jsonResponse = webClient.httpGet(apiUrl).body?.string()
            entries = JSONObject(jsonResponse ?: "{}").optJSONObject("feed")?.optJSONArray("entry")
        }

        if (entries == null || entries.length() == 0) {
            throw ParseException("API beneran kosong di kedua web untuk label: $exactLabel", apiUrl)
        }

        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val title = entry.optJSONObject("title")?.optString("$" + "t") ?: "Chapter ${i + 1}"
            
            var chapterUrl = ""
            val links = entry.optJSONArray("link")
            if (links != null) {
                for (j in 0 until links.length()) {
                    val linkObj = links.getJSONObject(j)
                    if (linkObj.optString("rel") == "alternate") {
                        chapterUrl = linkObj.optString("href")
                        break
                    }
                }
            }

            chapterUrl = chapterUrl.substringBefore("?m=1")
                .removePrefix("https://www.mikoroku.my.id")
                .removePrefix("http://www.mikoroku.my.id")
                .removePrefix("https://www.mikodrive.my.id")
                .removePrefix("http://www.mikodrive.my.id")

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

    // 🔥 HACK GAMBAR: Tangani Javascript Redirect ke Mikodrive
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = if (chapter.url.startsWith("http")) {
            chapter.url 
        } else {
            "https://$domain${chapter.url}" 
        }

        var doc = webClient.httpGet(fullUrl).parseHtml()

        // Bypass kalau Mikoroku pake Javascript buat pindah ke Mikodrive
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

        // Cari gambar dari berbagai kemungkinan class/id
        val images = doc.select("div.max-w img, div#readerarea img, div.post-body img")
        
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
    
