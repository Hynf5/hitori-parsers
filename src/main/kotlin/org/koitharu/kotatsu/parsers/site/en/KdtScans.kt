package org.koitharu.kotatsu.parsers.site.en

import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
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
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.attrOrNull
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseFailed
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.selectFirstOrThrow
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.textOrNull
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.text.SimpleDateFormat
import java.util.EnumSet

@MangaSourceParser("KDTSCANS", "KdtScans", "en")
internal class KdtScans(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.KDTSCANS, 20) {

    override val configKeyDomain = ConfigKey.Domain("www.silentquill.net")

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .add("Referer", "https://$domain/")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.RELEVANCE,
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = true,
        isTagsExclusionSupported = true,
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = fetchAvailableTags(),
            availableStates = EnumSet.of(
                MangaState.ONGOING,
                MangaState.FINISHED,
                MangaState.PAUSED,
            ),
            availableContentTypes = EnumSet.of(
                ContentType.MANGA,
                ContentType.MANHWA,
                ContentType.MANHUA,
                ContentType.COMICS,
                ContentType.NOVEL,
            ),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://$domain/manga/?page=${page}")

            filter.query?.let {
                append("&s=${it.urlEncoded()}")
            }

            val sortValue = when (order) {
                SortOrder.UPDATED -> "update"
                SortOrder.POPULARITY -> "popular"
                SortOrder.NEWEST -> "latest"
                SortOrder.ALPHABETICAL -> "title"
                SortOrder.ALPHABETICAL_DESC -> "titlereverse"
                else -> "" // Default/Relevance
            }
            if (sortValue.isNotEmpty()) {
                append("&order=$sortValue")
            }

            filter.tags.forEach { tag ->
                append("&genre[]=${tag.key}")
            }

            filter.tagsExclude.forEach { tag ->
                append("&genre[]=-${tag.key}")
            }

            filter.states.oneOrThrowIfMany().let { state ->
                val stateValue = when (state) {
                    MangaState.ONGOING -> "ongoing"
                    MangaState.FINISHED -> "completed"
                    MangaState.PAUSED -> "hiatus"
                    else -> ""
                }
                if (stateValue.isNotEmpty()) {
                    append("&status=$stateValue")
                }
            }

            filter.types.oneOrThrowIfMany()?.let { type ->
                val typeValue = when (type) {
                    ContentType.MANGA -> "manga"
                    ContentType.MANHWA -> "manhwa"
                    ContentType.MANHUA -> "manhua"
                    ContentType.COMICS -> "comic"
                    ContentType.NOVEL -> "novel"
                    else -> ""
                }
                if (typeValue.isNotEmpty()) {
                    append("&type=$typeValue")
                }
            }
        }
        val doc = webClient.httpGet(url).parseHtml()
        return parseMangaList(doc)
    }

    private fun parseMangaList(doc: Document): List<Manga> {
        val elements = doc.select("div.listupd div.bs")

        if (elements.isEmpty()) {
            return emptyList()
        }

        return elements.map { div ->
            val a = div.selectFirstOrThrow("a")
            val href = a.attrAsRelativeUrl("href")
            val img = div.selectFirst("img")
            val title = a.attr("title").ifEmpty {
                div.selectFirst(".tt")?.text().orEmpty()
            }
            val rating = div.selectFirst(".numscore")?.text()?.toFloatOrNull()?.div(10f) ?: RATING_UNKNOWN

            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                coverUrl = img?.src(),
                title = title,
                altTitles = emptySet(),
                rating = rating,
                tags = emptySet(),
                authors = emptySet(),
                state = parseStatus(div.selectFirst(".status")?.text().orEmpty()),
                source = source,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val infoElement =
            doc.selectFirst(".main-info, .postbody") ?: doc.parseFailed("Cannot find manga details element")

        val statusText =
            infoElement.selectFirst(".tsinfo .imptdt:contains(Status) i, .infotable tr:contains(Status) td:last-child")
                ?.text()

        val chapters = doc.select("#chapterlist li").mapChapters(reversed = true) { i, li ->
            val a = li.selectFirstOrThrow("a")
            val href = a.attrAsRelativeUrl("href")
            MangaChapter(
                id = generateUid(href),
                url = href,
                title = a.selectFirst(".chapternum")?.text() ?: a.text(),
                number = i + 1f,
                uploadDate = parseChapterDate(li.selectFirst(".chapterdate")?.text()),
                source = source,
                volume = 0,
                scanlator = null,
                branch = null,
            )
        }

        val genres = infoElement.select(".mgen a, .seriestugenre a").mapToSet { a ->
            MangaTag(
                key = a.attr("href").substringAfterLast("/").removeSuffix("/"),
                title = a.text(),
                source = source,
            )
        }

        val typeTag = infoElement.selectFirst(".tsinfo .imptdt:contains(Type) a")?.text()?.let { typeText ->
            MangaTag(
                key = typeText.lowercase(),
                title = typeText.trim(),
                source = source,
            )
        }

        val allTags = genres.toMutableSet()
        typeTag?.let { allTags.add(it) }

        return manga.copy(
            title = infoElement.selectFirst("h1.entry-title")?.text() ?: manga.title,
            authors = infoElement.select(".tsinfo .imptdt:contains(Author) i, .infotable tr:contains(Author) td:last-child")
                .mapToSet { it.text() },
            description = infoElement.select(".desc, .entry-content[itemprop=description]")
                .joinToString("\n") { it.text() },
            state = parseStatus(statusText.orEmpty()),
            tags = allTags,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()

        // Check all script tags
        val allScripts = doc.select("script")
        println("[KdtScans] Found ${allScripts.size} script tags total")

        // Look for LD+JSON scripts specifically
        val ldJsonScripts = doc.select("script[type='application/ld+json']")
        println("[KdtScans] Found ${ldJsonScripts.size} LD+JSON script tags")

        // Extract LD+JSON schema
        val ldJsonScript = doc.selectFirst("script[type='application/ld+json'].rank-math-schema")?.html()
        if (ldJsonScript == null) {
            println("[KdtScans] ERROR: Could not find script[type='application/ld+json'].rank-math-schema")
            // Try without the class selector
            val anyLdJson = doc.selectFirst("script[type='application/ld+json']")?.html()
            if (anyLdJson == null) {
                println("[KdtScans] ERROR: Could not find any script[type='application/ld+json']")
                return emptyList()
            }
            println("[KdtScans] Found LD+JSON without class, trying to parse it")
            return parseLdJson(anyLdJson)
        }

        println("[KdtScans] Found rank-math-schema script, length: ${ldJsonScript.length}")
        return parseLdJson(ldJsonScript)
    }

    private suspend fun parseLdJson(ldJsonScript: String): List<MangaPage> {
        // Parse JSON to get primaryImageOfPage
        val jsonObject = try {
            JSONObject(ldJsonScript)
        } catch (e: Exception) {
            println("[KdtScans] ERROR: Failed to parse JSON: ${e.message}")
            return emptyList()
        }

        println("[KdtScans] Parsed JSON successfully")
        val graphArray = jsonObject.optJSONArray("@graph")
        if (graphArray == null) {
            println("[KdtScans] ERROR: No @graph array found in JSON")
            return emptyList()
        }

        println("[KdtScans] Found @graph array with ${graphArray.length()} items")

        var primaryImageUrl: String? = null
        for (i in 0 until graphArray.length()) {
            val item = graphArray.getJSONObject(i)
            if (item.has("primaryImageOfPage")) {
                val imageObj = item.getJSONObject("primaryImageOfPage")
                // Try both "@id" and "url" fields
                primaryImageUrl = imageObj.optString("@id").ifEmpty {
                    imageObj.optString("url")
                }
                println("[KdtScans] Found primaryImageOfPage at index $i: $primaryImageUrl")
                if (primaryImageUrl.isNotBlank()) break
            }
        }

        if (primaryImageUrl.isNullOrBlank()) {
            println("[KdtScans] ERROR: primaryImageOfPage URL is null or blank")
            return emptyList()
        }

        println("[KdtScans] Primary image URL: $primaryImageUrl")

        // Extract base path and file extension from the first image URL
        // Example: https://cdn.asdasdhg.com/Returned%20from%20Another%20World/Chapter%202.1/1.webp
        val lastSlashIndex = primaryImageUrl.lastIndexOf('/')
        val basePath = primaryImageUrl.substring(0, lastSlashIndex + 1)
        val fileName = primaryImageUrl.substring(lastSlashIndex + 1)
        val extension = fileName.substring(fileName.lastIndexOf('.'))

        println("[KdtScans] Base path: $basePath")
        println("[KdtScans] File name: $fileName")
        println("[KdtScans] Extension: $extension")

        // Sequential image loading with 404 detection
        val pages = mutableListOf<MangaPage>()
        var imageIndex = 1
        var consecutive404s = 0
        val maxConsecutive404s = 3

        println("[KdtScans] Starting sequential image loading...")

        while (imageIndex <= 500) {
            val imageUrl = "$basePath$imageIndex$extension"

            // Try to fetch the image
            val response = webClient.httpHead(imageUrl)
            val statusCode = response.code

            if (statusCode == 404) {
                consecutive404s++
                println("[KdtScans] Image $imageIndex: 404 (consecutive: $consecutive404s)")
                // Stop if we get too many consecutive 404s
                if (consecutive404s >= maxConsecutive404s) {
                    println("[KdtScans] Reached $maxConsecutive404s consecutive 404s, stopping")
                    break
                }
            } else if (response.isSuccessful) {
                // Reset counter on successful response
                consecutive404s = 0
                println("[KdtScans] Image $imageIndex: SUCCESS ($statusCode)")
                pages.add(
                    MangaPage(
                        id = generateUid(imageUrl),
                        url = imageUrl,
                        preview = null,
                        source = source,
                    )
                )
            } else {
                // For other errors (403, 500, etc), treat as potential image
                consecutive404s = 0
                println("[KdtScans] Image $imageIndex: Other status $statusCode (treating as valid)")
                pages.add(
                    MangaPage(
                        id = generateUid(imageUrl),
                        url = imageUrl,
                        preview = null,
                        source = source,
                    )
                )
            }

            imageIndex++
        }

        println("[KdtScans] Finished loading. Found ${pages.size} pages")
        return pages
    }

    private fun parseStatus(status: String): MangaState? {
        return when {
            status.contains("ongoing", ignoreCase = true) -> MangaState.ONGOING
            status.contains("completed", ignoreCase = true) -> MangaState.FINISHED
            status.contains("hiatus", ignoreCase = true) -> MangaState.PAUSED
            status.contains("dropped", ignoreCase = true) -> MangaState.ABANDONED
            status.contains("canceled", ignoreCase = true) -> MangaState.ABANDONED
            else -> null
        }
    }

    private fun parseChapterDate(date: String?): Long {
        return try {
            SimpleDateFormat("MMMM dd, yyyy", sourceLocale).parse(date?.trim()).time
        } catch (_: Exception) {
            0L
        }
    }

    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        val doc = webClient.httpGet("https://$domain/manga/").parseHtml()
        return doc.select("ul.genrez li").mapNotNullToSet { li ->
            val key = li.selectFirst("input")?.attrOrNull("value") ?: return@mapNotNullToSet null
            val title = li.selectFirst("label")?.textOrNull()?.toTitleCase() ?: return@mapNotNullToSet null
            MangaTag(
                key = key,
                title = title,
                source = source,
            )
        }
    }
}
