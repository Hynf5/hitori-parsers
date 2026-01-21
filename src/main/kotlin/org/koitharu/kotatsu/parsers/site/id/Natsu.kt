package org.koitharu.kotatsu.parsers.site.id

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("NATSU", "Natsu", "id")
internal class Natsu(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.NATSU, 24) {

	override val configKeyDomain = ConfigKey.Domain("natsu.tv")

	private val multipartHttpClient by lazy {
		OkHttpClient.Builder()
			.build()
	}

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

	override fun getRequestHeaders() = Headers.Builder()
		.add("Referer", "https://natsu.tv/library/")
		.add("Origin", "https://natsu.tv")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED
		),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
			ContentType.COMICS,
			ContentType.NOVEL
		),
	)

	private var cachedNonce: String? = null
	private val nonceMutex = Mutex()

	private suspend fun getNonce(): String {
		if (cachedNonce == null) {
			nonceMutex.withLock {
				if (cachedNonce == null) {
					val doc = webClient.httpGet(
						"https://${domain}/wp-admin/admin-ajax.php?type=search_form&action=get_nonce"
					).parseHtml()
					cachedNonce = doc.selectFirst("input[name=search_nonce]")?.attr("value") ?: ""
				}
			}
		}
		return cachedNonce!!
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = "https://${domain}/wp-admin/admin-ajax.php?action=advanced_search"
		val nonce = getNonce()

		val formBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)

		formBuilder.addFormDataPart("nonce", nonce)
		formBuilder.addFormDataPart("inclusion", "OR")
		formBuilder.addFormDataPart("exclusion", "OR")
		formBuilder.addFormDataPart("page", page.toString())
		formBuilder.addFormDataPart("project", "0")
		formBuilder.addFormDataPart("order", "desc")
		formBuilder.addFormDataPart("orderby", when(order) {
			SortOrder.UPDATED -> "updated"
			SortOrder.POPULARITY -> "popular"
			SortOrder.RATING -> "rating"
			SortOrder.ALPHABETICAL -> "title"
			else -> "popular"
		})

		if (filter.tags.isNotEmpty()) {
			formBuilder.addFormDataPart("genre", JSONArray(filter.tags.map { it.key }).toString())
		} else {
			formBuilder.addFormDataPart("genre", "[]")
		}

		if (filter.tagsExclude.isNotEmpty()) {
			formBuilder.addFormDataPart("genre_exclude", JSONArray(filter.tagsExclude.map { it.key }).toString())
		} else {
			formBuilder.addFormDataPart("genre_exclude", "[]")
		}

		if (!filter.author.isNullOrEmpty()) {
			formBuilder.addFormDataPart("author", JSONArray(listOf(filter.author)).toString())
		} else {
			formBuilder.addFormDataPart("author", "[]")
		}

		formBuilder.addFormDataPart("artist", "[]")

		if (filter.types.isNotEmpty()) {
			val typeArray = JSONArray()
			filter.types.forEach { type ->
				when (type) {
					ContentType.MANGA -> typeArray.put("manga")
					ContentType.MANHWA -> typeArray.put("manhwa")
					ContentType.MANHUA -> typeArray.put("manhua")
					ContentType.COMICS -> typeArray.put("comic")
					ContentType.NOVEL -> typeArray.put("novel")
					else -> {}
				}
			}
			formBuilder.addFormDataPart("type", typeArray.toString())
		} else {
			formBuilder.addFormDataPart("type", "[]")
		}

		if (filter.states.isNotEmpty()) {
			val statusArray = JSONArray()
			filter.states.forEach { state ->
				when (state) {
					MangaState.ONGOING -> statusArray.put("ongoing")
					MangaState.FINISHED -> statusArray.put("completed")
					MangaState.PAUSED -> statusArray.put("on-hiatus")
					else -> {}
				}
			}
			formBuilder.addFormDataPart("status", statusArray.toString())
		} else {
			formBuilder.addFormDataPart("status", "[]")
		}

		formBuilder.addFormDataPart("query", filter.query ?: "")

		val request = Request.Builder()
			.url(url)
			.post(formBuilder.build())
			.addHeader("Referer", "https://natsu.tv/library/")
			.addHeader("Origin", "https://natsu.tv")
			.build()

		val response = context.httpClient.newCall(request).await()
		val doc = response.parseHtml()
		return parseMangaList(doc)
	}

	protected open fun parseMangaList(doc: Document): List<Manga> {
		return doc.select("div").mapNotNull { div ->
			val link = div.selectFirst("a[href*='/manga/']") ?: return@mapNotNull null
			val href = link.attrAsRelativeUrl("href")

			if (href.contains("/chapter-")) return@mapNotNull null

			val title = div.selectFirst("a.text-base, a.text-white, h1")?.text()?.trim()
				?: link.attr("title").ifEmpty { link.text() }

			val coverUrl = div.selectFirst("img")?.src()

			val ratingText = div.selectFirst(".numscore, span.text-yellow-400")?.text()
			val rating = ratingText?.toFloatOrNull()?.let {
				if (it > 5) it / 10f else it / 5f
			} ?: RATING_UNKNOWN

			val stateText = div.selectFirst("span.bg-accent, p:contains(Ongoing), p:contains(Completed)")
				?.text()?.lowercase()
			val state = when {
				stateText?.contains("ongoing") == true -> MangaState.ONGOING
				stateText?.contains("completed") == true -> MangaState.FINISHED
				stateText?.contains("hiatus") == true -> MangaState.PAUSED
				else -> null
			}

			Manga(
				id = generateUid(href),
				url = href,
				title = title,
				altTitles = emptySet(),
				publicUrl = link.attrAsAbsoluteUrl("href"),
				rating = rating,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
				coverUrl = coverUrl,
				tags = emptySet(),
				state = state,
				authors = emptySet(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

		val mangaId = doc.selectFirst("[hx-get*='manga_id=']")
			?.attr("hx-get")
			?.substringAfter("manga_id=")
			?.substringBefore("&")
			?.trim()
			?: doc.selectFirst("input#manga_id, [data-manga-id]")
				?.let { it.attr("value").ifEmpty { it.attr("data-manga-id") } }
			?: manga.url.substringAfterLast("/manga/").substringBefore("/")

		val titleElement = doc.selectFirst("h1[itemprop=name]")
		val title = titleElement?.text() ?: manga.title

		val altTitles = titleElement?.nextElementSibling()?.text()
			?.split(',')
			?.mapNotNull { it.trim().takeIf(String::isNotBlank) }
			?.toSet() ?: emptySet()

		val description = doc.select("div[itemprop=description]")
			.joinToString("\n\n") { it.text() }
			.trim()
			.takeIf { it.isNotBlank() }

		val coverUrl = doc.selectFirst("div[itemprop=image] > img")?.src()
			?: manga.coverUrl

		val tags = doc.select("a[itemprop=genre]").mapNotNullToSet { a ->
			MangaTag(
				key = a.attr("href").substringAfterLast("/genre/").removeSuffix("/"),
				title = a.text().toTitleCase(),
				source = source,
			)
		}

		fun findInfoText(key: String): String? {
			return doc.select("div.space-y-2 > .flex:has(h4)")
				.find { it.selectFirst("h4")?.text()?.contains(key, ignoreCase = true) == true }
				?.selectFirst("p.font-normal")?.text()
		}

		val stateText = findInfoText("Status")?.lowercase()
		val state = when {
			stateText?.contains("ongoing") == true -> MangaState.ONGOING
			stateText?.contains("completed") == true -> MangaState.FINISHED
			stateText?.contains("hiatus") == true -> MangaState.PAUSED
			else -> manga.state
		}

		val authors = findInfoText("Author")
			?.split(",")
			?.map { it.trim() }
			?.toSet() ?: emptySet()

		val chapters = loadChapters(mangaId, manga.url.toAbsoluteUrl(domain))

		return manga.copy(
			title = title,
			altTitles = altTitles,
			description = description,
			coverUrl = coverUrl,
			tags = tags,
			state = state,
			authors = authors,
			chapters = chapters,
		)
	}

	protected open suspend fun loadChapters(
		mangaId: String,
		mangaAbsoluteUrl: String,
	): List<MangaChapter> {
		val chapters = mutableListOf<MangaChapter>()
		var page = 1

		while (true) {
			val url = "https://${domain}/wp-admin/admin-ajax.php?manga_id=$mangaId&page=$page&action=chapter_list"
			val doc = webClient.httpGet(url).parseHtml()

			val chapterElements = doc.select("div#chapter-list > div[data-chapter-number]")
			if (chapterElements.isEmpty()) break

			chapterElements.forEach { element ->
				val a = element.selectFirst("a") ?: return@forEach
				val href = a.attrAsRelativeUrl("href")
				if (href.isBlank()) return@forEach

				val chapterTitle = element.selectFirst("div.font-medium span")?.text()?.trim() ?: ""
				val dateText = element.selectFirst("time")?.text()
				val number = element.attr("data-chapter-number").toFloatOrNull() ?: -1f

				chapters.add(
					MangaChapter(
						id = generateUid(href),
						title = chapterTitle,
						url = href,
						number = number,
						volume = 0,
						scanlator = null,
						uploadDate = parseDate(dateText),
						branch = null,
						source = source,
					)
				)
			}
			page++
			if (page > 100) break
		}
		return chapters.reversed()
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

		val imgElements = doc.select("main section section > img")
			.ifEmpty { doc.select("section[data-image-data] > img") }

		return imgElements.map { img ->
			val url = img.requireSrc().toRelativeUrl(domain)
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	protected open suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://${domain}/library/").parseHtml()

		return doc.select("button[data-genre]").mapNotNullToSet { button ->
			val key = button.attr("data-genre").takeIf { it.isNotBlank() } ?: return@mapNotNullToSet null
			val title = button.text().takeIf { it.isNotBlank() } ?: return@mapNotNullToSet null

			MangaTag(
				key = key,
				title = title.toTitleCase(),
				source = source
			)
		}
	}

	protected open fun parseDate(dateStr: String?): Long {
		if (dateStr.isNullOrEmpty()) return 0

		return try {
			when {
				dateStr.contains("ago") -> {
					val number = Regex("""(\d+)""").find(dateStr)?.value?.toIntOrNull() ?: return 0
					val cal = Calendar.getInstance()
					when {
						dateStr.contains("min") -> cal.add(Calendar.MINUTE, -number)
						dateStr.contains("hour") -> cal.add(Calendar.HOUR, -number)
						dateStr.contains("day") -> cal.add(Calendar.DAY_OF_MONTH, -number)
						dateStr.contains("week") -> cal.add(Calendar.WEEK_OF_YEAR, -number)
						dateStr.contains("month") -> cal.add(Calendar.MONTH, -number)
						dateStr.contains("year") -> cal.add(Calendar.YEAR, -number)
						else -> {}
					}
					cal.timeInMillis
				}
				else -> {
					SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH).parse(dateStr)?.time ?: 0
				}
			}
		} catch (_: Exception) {
			0
		}
	}
}
