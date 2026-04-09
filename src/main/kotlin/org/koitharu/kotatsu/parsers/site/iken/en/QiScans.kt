package org.koitharu.kotatsu.parsers.site.iken.en

import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("QISCANS", "Qi Scans", "en")
internal class QiScans(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.QISCANS, 20) {

	override val configKeyDomain = ConfigKey.Domain("qimanhwa.com")

	private val apiUrl = "https://api.qimanhwa.com/api/v1"

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
	)

	override suspend fun getFilterOptions() = MangaListFilterOptions()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = if (!filter.query.isNullOrEmpty()) {
			"$apiUrl/series/search?q=${filter.query.urlEncoded()}&page=$page&perPage=$pageSize"
		} else {
			val sort = when (order) {
				SortOrder.POPULARITY -> "popular"
				SortOrder.NEWEST -> "newest"
				else -> "latest"
			}
			"$apiUrl/series?page=$page&perPage=$pageSize&sort=$sort"
		}
		val response = webClient.httpGet(url).parseJson()
		val data = response.getJSONArray("data")
		return (0 until data.length()).map { parseMangaFromList(data.getJSONObject(it)) }
	}

	private fun parseMangaFromList(json: JSONObject): Manga {
		val slug = json.getString("slug")
		val altTitles = json.optString("alternativeTitles", "").nullIfEmpty()
			?.split(" • ")?.filter { it.isNotBlank() }?.toSet().orEmpty()
		val avgRating = json.optDouble("avgRating", -1.0)

		return Manga(
			id = generateUid(slug),
			url = slug,
			publicUrl = "https://qimanhwa.com/series/$slug",
			coverUrl = json.optString("cover", "").nullIfEmpty(),
			title = json.getString("title"),
			altTitles = altTitles,
			rating = if (avgRating > 0) (avgRating / 5.0).toFloat() else RATING_UNKNOWN,
			contentRating = null,
			tags = emptySet(),
			state = parseState(json.optString("status", "")),
			authors = emptySet(),
			source = source,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url
		val response = webClient.httpGet("$apiUrl/series/$slug").parseJson()

		val altTitles = response.optString("alternativeTitles", "").nullIfEmpty()
			?.split(" • ")?.filter { it.isNotBlank() }?.toSet().orEmpty()
		val author = response.optString("author", "").nullIfEmpty()
		val artist = response.optString("artist", "").nullIfEmpty()
		val authors = setOfNotNull(author, artist)

		// Fetch all chapters with pagination (max 30 per page)
		val allChapters = mutableListOf<JSONObject>()
		var page = 1
		while (true) {
			val chaptersResponse = webClient.httpGet(
				"$apiUrl/series/$slug/chapters?page=$page&perPage=30&sort=desc"
			).parseJson()
			val data = chaptersResponse.getJSONArray("data")
			if (data.length() == 0) break
			for (i in 0 until data.length()) {
				allChapters.add(data.getJSONObject(i))
			}
			if (page >= chaptersResponse.optInt("totalPages", 1)) break
			page++
		}

		val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
		val chapters = allChapters.map { ch ->
			val chapterSlug = ch.getString("slug")
			val number = ch.optDouble("number", 0.0).toFloat()
			val chTitle = ch.optString("title", "").nullIfEmpty()
			val title = buildString {
				append("Chapter ")
				append(number.toString().substringBefore(".0"))
				if (!chTitle.isNullOrBlank()) {
					append(" - ")
					append(chTitle)
				}
			}
			MangaChapter(
				id = ch.getLong("id"),
				title = title,
				number = number,
				volume = 0,
				url = JSONObject().apply {
					put("slug", slug)
					put("chapterSlug", chapterSlug)
				}.toString(),
				uploadDate = ch.optString("createdAt", "").nullIfEmpty()
					?.let { dateFormat.parseSafe(it.substringBefore("T")) } ?: 0L,
				source = source,
				scanlator = null,
				branch = null,
			)
		}.reversed()

		return manga.copy(
			title = response.getString("title"),
			coverUrl = response.optString("cover", "").nullIfEmpty() ?: manga.coverUrl,
			description = response.optString("description", "").nullIfEmpty(),
			altTitles = altTitles,
			state = parseState(response.optString("status", "")),
			authors = authors,
			chapters = chapters,
		)
	}

	override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterData = JSONObject(chapter.url)
		val slug = chapterData.getString("slug")
		val chapterSlug = chapterData.getString("chapterSlug")

		val response = webClient.httpGet("$apiUrl/series/$slug/chapters/$chapterSlug").parseJson()
		val images = response.getJSONArray("images")

		return (0 until images.length()).map { i ->
			val img = images.getJSONObject(i)
			MangaPage(
				id = generateUid(img.getString("url")),
				url = img.getString("url"),
				preview = null,
				source = source,
			)
		}
	}

	private fun parseState(status: String?): MangaState? = when (status) {
		"ONGOING" -> MangaState.ONGOING
		"COMPLETED" -> MangaState.FINISHED
		"HIATUS" -> MangaState.PAUSED
		"MASS_RELEASED" -> MangaState.ONGOING
		"DROPPED", "CANCELLED" -> MangaState.ABANDONED
		"COMING_SOON" -> MangaState.UPCOMING
		else -> null
	}
}
