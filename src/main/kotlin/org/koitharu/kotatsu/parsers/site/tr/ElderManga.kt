package org.koitharu.kotatsu.parsers.site.tr

import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("ELDERMANGA", "Elder Manga", "tr")
internal class ElderManga(context: MangaLoaderContext):
    PagedMangaParser(context, MangaParserSource.ELDERMANGA, 25) {

    override val configKeyDomain = ConfigKey.Domain("eldermanga.com")
    private val cdnSuffix = "https://eldermangacdn2.efsaneler.can.re"

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC,
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
		SortOrder.UPDATED,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true,
            isSearchWithFiltersSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchTags(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.ABANDONED, MangaState.PAUSED),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
            ContentType.COMICS,
        ),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
			append(domain)
            append("/search")
            append("?page=")
			append(page.toString())

            if (!filter.query.isNullOrEmpty()) {
                append("&search=")
                append(filter.query.urlEncoded())
            }

            if (filter.tags.isNotEmpty()) {
                append("&categories=")
				filter.tags.joinTo(this, ",") { it.key }
			}

            if (filter.states.isNotEmpty()) {
                append("&publicStatus=")
				filter.states.oneOrThrowIfMany()?.let {
					append(
						when (it) {
							MangaState.ONGOING -> "1"
							MangaState.FINISHED -> "2"
                            MangaState.ABANDONED -> "3"
                            MangaState.PAUSED -> "4"
							else -> ""
						},
					)
				}
			}

            if (filter.types.isNotEmpty()) {
                append("&country=")
				filter.types.oneOrThrowIfMany()?.let {
					append(
						when (it) {
							ContentType.MANHUA -> "1"
							ContentType.MANHWA -> "2"
							ContentType.MANGA -> "3"
							ContentType.COMICS -> "4"
							else -> ""
						},
					)
				}
			}

            append("&order=")
            append(
                when (order) {
                    SortOrder.ALPHABETICAL -> "1"
                    SortOrder.ALPHABETICAL_DESC -> "2"
                    SortOrder.NEWEST -> "3"
                    SortOrder.POPULARITY -> "4"
					SortOrder.UPDATED -> "5"
                    else -> "1"
                }
            )
        }

        val doc = loadSiteDocument(url)
        return doc.select("section[aria-label='series area'] .card").map { card ->
            val href = card.selectFirstOrThrow("a").attrAsRelativeUrl("href")
            Manga(
                id = generateUid(href),
                title = card.selectFirst("h2")?.text().orEmpty(),
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = card.selectFirst("img")?.attrAsAbsoluteUrlOrNull("src"),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = loadSiteDocument(manga.url.toAbsoluteUrl(domain))
        val statusText = doc.selectFirst("span:contains(Durum) + span")?.text().orEmpty()
        return manga.copy(
            tags = doc.select("a[href^='search?categories']").mapToSet {
                val key = it.attr("href").substringAfter("?categories=")
                MangaTag(
                    key = key,
                    title = it.text(),
                    source = source,
                )
            },
            description = doc.selectFirst("div.grid h2 + p")?.text(),
            state = when (statusText) {
                "Devam Ediyor" -> MangaState.ONGOING
                "Birakildi" -> MangaState.ONGOING
                "Tamamlandi" -> MangaState.FINISHED
                else -> null
            },
            chapters = doc.select("div.list-episode a").mapChapters(reversed = true) { i, el ->
                val href = el.attrAsRelativeUrl("href")
                val dateFormat = SimpleDateFormat("MMM d ,yyyy", Locale("tr"))
                MangaChapter(
                    id = generateUid(href),
                    title = el.selectFirstOrThrow("h3").text(),
                    number = (i + 1).toFloat(),
                    volume = 0,
                    url = href,
                    scanlator = null,
                    uploadDate = el.selectFirst("span")?.text()?.let { dateFormat.parseSafe(it) } ?: 0L,
                    branch = null,
                    source = source,
                )
            },
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = loadSiteDocument(chapter.url.toAbsoluteUrl(domain))
        val pageRegex = Regex("\\\\\"path\\\\\":\\\\\"([^\"]+)\\\\\"")
        val script = doc.select("script").find { it.html().contains(pageRegex) }?.html() ?: return emptyList()
        return pageRegex.findAll(script).mapNotNull { result ->
            result.groups[1]?.value?.let { url ->
                MangaPage(
                    id = generateUid(url),
                    url = "${cdnSuffix.trimEnd('/')}/$url",
                    preview = null,
                    source = source,
                )
            }
        }.toList()
    }

    private suspend fun fetchTags(): Set<MangaTag> {
        val doc = loadSiteDocument("https://$domain/search")
        val script = doc.select("script").find { it.html().contains("self.__next_f.push([1,\"10:[\\\"\\$,\\\"section") }?.html()
            ?: return emptySet()

        val jsonStr = script.substringAfter("\"category\":[")
            .substringBefore("],\"searchParams\":{}")
            .replace("\\", "")

        val jsonArray = JSONArray("[$jsonStr]")
        return jsonArray.mapJSONToSet { jo ->
            MangaTag(
                key = jo.getString("id"),
                title = jo.getString("name"),
                source = source
            )
        }
    }

    private suspend fun loadSiteDocument(url: String): Document {
        when (val result = tryHttpDocument(url)) {
            is HttpDocumentResult.Success -> return result.document
            HttpDocumentResult.SecondaryVerification,
            HttpDocumentResult.CloudflareChallenge,
            HttpDocumentResult.Failed,
            null -> {
                context.requestBrowserAction(this, url)
                throw ParseException("Interactive action is required to load page", url)
            }
        }
    }

    private suspend fun tryHttpDocument(url: String): HttpDocumentResult? {
        val response = runCatching { webClient.httpGet(url) }.getOrNull() ?: return null
        return response.use { res ->
            val doc = runCatching { res.parseHtml() }.getOrNull() ?: return HttpDocumentResult.Failed
            if (hasValidElderContent(doc)) {
                return HttpDocumentResult.Success(doc)
            }
            if (isShieldVerificationPage(doc)) {
                return HttpDocumentResult.SecondaryVerification
            }
            HttpDocumentResult.CloudflareChallenge
        }
    }

    private fun isShieldVerificationPage(doc: Document): Boolean {
        val html = doc.outerHtml().lowercase(Locale.ROOT)
        return doc.selectFirst("#container.verified") != null ||
            html.contains("verification complete") ||
            html.contains("protected by waf security shield") ||
            html.contains("access granted!") ||
            html.contains("computing challenge") ||
            html.contains("solving proof of work")
    }

    private fun isCloudflareChallengePage(doc: Document): Boolean {
        if (hasValidElderContent(doc)) {
            return false
        }
        val title = doc.title().lowercase(Locale.ROOT)
        if (title.contains("access denied") || title.contains("just a moment")) return true
        if (doc.getElementById("challenge-error-title") != null) return true
        if (doc.getElementById("challenge-error-text") != null) return true
        if (doc.selectFirst("form[action*=__cf_chl]") != null) return true
        return isCloudflareChallengePage(doc.outerHtml())
    }

    private fun isCloudflareChallengePage(html: String): Boolean {
        val lower = html.lowercase(Locale.ROOT)
        return lower.contains("<title>access denied") ||
            lower.contains("<title>just a moment") ||
            isClassicCloudflareChallenge(lower) ||
            (lower.contains("/cdn-cgi/challenge-platform/") &&
                (lower.contains("enable javascript and cookies to continue") ||
                    lower.contains("window._cf_chl_opt"))) ||
            lower.contains("form action=\"/cdn-cgi/challenge-platform/") ||
            lower.contains("form action=\"/cdn-cgi/l/chk_captcha")
    }

    private fun isClassicCloudflareChallenge(lower: String): Boolean {
        return (lower.contains("just a moment") && lower.contains("cloudflare")) ||
            (lower.contains("checking your browser") && lower.contains("cloudflare")) ||
            lower.contains("cf-browser-verification") ||
            lower.contains("cf-chl-opt")
    }

    private fun hasValidElderContent(doc: Document): Boolean {
        val hasDirectoryCards = doc.select("section[aria-label='series area'] .card").isNotEmpty() ||
            doc.select("section[aria-label='series area'] a[href*='/manga/'] h2").isNotEmpty()
        return hasDirectoryCards ||
            doc.select("div.list-episode a").isNotEmpty() ||
            doc.select("#content h1").isNotEmpty() ||
            doc.select("a[href*='search?categories=']").isNotEmpty()
    }

    private companion object {
        private sealed interface HttpDocumentResult {
            data class Success(val document: Document) : HttpDocumentResult
            data object CloudflareChallenge : HttpDocumentResult
            data object SecondaryVerification : HttpDocumentResult
            data object Failed : HttpDocumentResult
        }

    }
}
