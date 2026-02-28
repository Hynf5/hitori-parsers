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

    override suspend fun loadChapters(mangaUrl: String, doc: Document): List<MangaChapter> {
        val cleanUrl = mangaUrl.substringBefore("?m=1")
        val fullUrl = if (cleanUrl.startsWith("http")) cleanUrl else "https://$domain$cleanUrl"
        val desktopDoc = webClient.httpGet(fullUrl).parseHtml()

        val chapters = mutableListOf<MangaChapter>()

        // 1. Coba cari kalau ada list chapter HTML biasa
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
            return chapters.reversed()
        }

        var exactLabel = ""
        
        // 🔥 TARGET 1: Paling Akurat - Ambil dari link label di dalam H1 (Sesuai HTML lu!)
        val h1Label = desktopDoc.selectFirst("h1 a[href*=/search/label/]")
        if (h1Label != null) {
            val href = h1Label.attr("href")
            exactLabel = href.substringAfter("/search/label/").substringBefore("?").substringBefore("&")
            exactLabel = URLDecoder.decode(exactLabel, "UTF-8")
        }

        // 🔥 TARGET 2: Fallback ke data-label bawaan Zeist
        if (exactLabel.isEmpty()) {
            val dataLabelElement = desktopDoc.selectFirst("[data-label]")
            if (dataLabelElement != null) {
                exactLabel = dataLabelElement.attr("data-label")
            }
        }

        // 🔥 TARGET 3: Fallback mentah ke teks H1
        if (exactLabel.isEmpty()) {
            val h1Text = desktopDoc.selectFirst("h1")?.text()?.trim() ?: ""
            // Bersihin dari kata " Chapter XX" dll
            exactLabel = h1Text.substringBefore(" Chapter").substringBefore(" -").trim()
        }

        // Cek kalau masih kosong atau dapet kode JS aneh
        if (exactLabel.isEmpty() || exactLabel.contains("\${")) {
            throw ParseException("Gagal total nge-ekstrak label valid dari web", fullUrl)
        }

        val encodedLabel = URLEncoder.encode(exactLabel, "UTF-8").replace("+", "%20")
        
        // Tembak API
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

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = if (chapter.url.startsWith("http")) {
            chapter.url 
        } else {
            "https://$domain${chapter.url}" 
        }

        var doc = webClient.httpGet(fullUrl).parseHtml()

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
    
