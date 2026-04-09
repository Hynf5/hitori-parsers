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
import java.util.LinkedHashSet

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

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()
        val pages = extractPageUrls(doc)
            .map { pageUrl ->
                MangaPage(
                    id = generateUid(pageUrl),
                    url = pageUrl,
                    preview = null,
                    source = source,
                )
            }

        if (pages.isEmpty()) {
            doc.parseFailed("Cannot find chapter pages")
        }
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

    private fun extractPageUrls(doc: Document): List<String> {
        val readerScript = doc.select("script").firstNotNullOfOrNull { script ->
            script.data().takeIf { it.contains("ts_reader.run(") }
        }
        val readerConfig = readerScript?.let(::parseReaderConfig)
        val fallbackUrls = doc.select("#readerarea img")
            .mapNotNull { img ->
                img.attrOrNull("data-src")
                    ?.takeIf { it.isNotBlank() }
                    ?: img.src()?.takeIf { it.isNotBlank() }
            }

        return normalizePageUrls(
            rawUrls = readerConfig?.first ?: fallbackUrls,
            isLastChapter = readerConfig?.second ?: false,
        )
    }

    private fun parseReaderConfig(scriptData: String): Pair<List<String>, Boolean>? {
        val jsonText = READER_CONFIG_REGEX.find(scriptData)?.groupValues?.get(1) ?: return null
        val json = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        val urls = mutableListOf<String>()
        val sources = json.optJSONArray("sources")
        if (sources != null) {
            for (i in 0 until sources.length()) {
                val sourceItem = sources.optJSONObject(i) ?: continue
                val images = sourceItem.optJSONArray("images") ?: continue
                for (j in 0 until images.length()) {
                    images.optString(j)
                        .takeIf { it.isNotBlank() }
                        ?.let(urls::add)
                }
            }
        }
        return urls to json.optString("nextUrl").isBlank()
    }

    private fun normalizePageUrls(rawUrls: List<String>, isLastChapter: Boolean): List<String> {
        val urls = LinkedHashSet<String>(rawUrls.size)
        var hasCreditsPage = false

        rawUrls.forEach { rawUrl ->
            val url = rawUrl.trim()
            if (url.isEmpty()) {
                return@forEach
            }
            val lowercaseUrl = url.lowercase()
            when {
                "cdn.asdasdhg.com" !in lowercaseUrl -> return@forEach
                "readerarea.svg" in lowercaseUrl -> return@forEach
                "reen_wes.webp" in lowercaseUrl -> return@forEach
                "credits-page" in lowercaseUrl -> {
                    hasCreditsPage = true
                    return@forEach
                }
                else -> urls += url
            }
        }

        val normalized = urls.toList()
        if (!isLastChapter || !hasCreditsPage) {
            return normalized
        }

        val numberedPages = normalized.map { url ->
            PAGE_NUMBER_REGEX.find(url.substringAfterLast('/').substringBefore('?'))
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
        }
        val isSequential = numberedPages.none { it == null } &&
            numberedPages.mapNotNull { it } == (1..numberedPages.size).toList()

        // SilentQuill latest chapters regularly expose one trailing numbered image in the
        // serialized reader payload that does not belong to the actual chapter.
        return if (isSequential && normalized.size > 1) normalized.dropLast(1) else normalized
    }

    private companion object {
        val READER_CONFIG_REGEX = Regex("""ts_reader\.run\((\{.*?\})\);?""", RegexOption.DOT_MATCHES_ALL)
        val PAGE_NUMBER_REGEX = Regex("""^(\d+)_out\.(?:webp|jpg|jpeg|png)$""", RegexOption.IGNORE_CASE)
    }
}
