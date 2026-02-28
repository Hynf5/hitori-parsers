package org.koitharu.kotatsu.parsers.site.zeistmanga.id

import org.json.JSONObject
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

    // 🔥 HACK ULTIMATE: Bikin JSON Fetcher Sendiri Tembak ke Mikodrive!
    override suspend fun loadChapters(mangaUrl: String, doc: Document): List<MangaChapter> {
        val cleanUrl = mangaUrl.substringBefore("?m=1")
        val fullUrl = if (cleanUrl.startsWith("http")) cleanUrl else "https://$domain$cleanUrl"
        val desktopDoc = webClient.httpGet(fullUrl).parseHtml()

        // 1. Ambil URL Label asli dari HTML
        val tagElement = desktopDoc.selectFirst("a[href*=/search/label/], a[rel=tag]")
            ?: throw ParseException("Gagal menemukan link label Mikodrive", fullUrl)

        // 2. Ekstrak Label Persis
        val rawLabelUrl = tagElement.attr("href")
        val exactLabel = rawLabelUrl.substringAfter("/search/label/").substringBefore("?").substringBefore("&")

        // 3. Tembak API JSON langsung ke MIKODRIVE
        val apiUrl = "https://www.mikodrive.my.id/feeds/posts/default/-/$exactLabel?alt=json&max-results=999"
        val jsonResponse = webClient.httpGet(apiUrl).body?.string()
            ?: throw ParseException("Gagal narik API Mikodrive", apiUrl)

        val json = JSONObject(jsonResponse)
        val feed = json.optJSONObject("feed")
        val entries = feed?.optJSONArray("entry")

        if (entries == null || entries.length() == 0) {
            throw ParseException("API Mikodrive beneran kosong untuk label $exactLabel", apiUrl)
        }

        val chapters = mutableListOf<MangaChapter>()
        
        // 4. Parse JSON Manual dan bikin list Chapter
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

            chapterUrl = chapterUrl.substringBefore("?m=1")
                .removePrefix("https://www.mikodrive.my.id")
                .removePrefix("http://www.mikodrive.my.id")

            chapters.add(
                MangaChapter(
                    id = i.toLong(),
                    title = title,
                    number = -1f,
                    volume = 0,
                    scanlator = "",     // <-- FIXED: Parameter yang ketinggalan
                    uploadDate = 0L,    // <-- FIXED: Parameter yang ketinggalan
                    branch = "",        // <-- FIXED: Parameter yang ketinggalan
                    url = chapterUrl,
                    source = source
                )
            )
        }

        return chapters
    }

    // 🔥 HACK GAMBAR: Nyedot langsung dari div.max-w
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = if (chapter.url.startsWith("http")) {
            chapter.url 
        } else {
            "https://www.mikodrive.my.id${chapter.url}"
        }.substringBefore("?m=1")

        val doc = webClient.httpGet(fullUrl).parseHtml()

        val images = doc.select("div.max-w img")
        
        if (images.isEmpty()) {
            throw ParseException("Gagal menemukan gambar di kontainer .max-w Mikodrive", fullUrl)
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
    
