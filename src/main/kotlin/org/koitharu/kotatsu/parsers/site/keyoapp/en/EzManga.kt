package org.koitharu.kotatsu.parsers.site.keyoapp.en

import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.site.iken.IkenParser
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.parseJsonArray
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.urlEncoded
import org.koitharu.kotatsu.parsers.util.json.asTypedList
import org.koitharu.kotatsu.parsers.util.json.getBooleanOrDefault
import org.koitharu.kotatsu.parsers.util.json.getFloatOrDefault
import org.koitharu.kotatsu.parsers.util.json.getLongOrDefault
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("EZMANGA", "EzManga", "en")
internal class EzManga(context: MangaLoaderContext) :
	IkenParser(context, MangaParserSource.EZMANGA, "ezmanga.org", 18, useAPI = true) {

	private val apiDomain = "vapi.ezmanga.org"

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL_DESC,
	)

	override suspend fun getFilterOptions(): MangaListFilterOptions = super.getFilterOptions().copy(
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.PAUSED,
			MangaState.FINISHED,
			MangaState.ABANDONED,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(apiDomain)
			append("/api/query?page=")
			append(page)
			append("&perPage=18")
			append("&orderBy=")
			append(
				when (order) {
					SortOrder.POPULARITY -> "totalViews"
					SortOrder.UPDATED -> "updatedAt"
					SortOrder.NEWEST -> "createdAt"
					SortOrder.ALPHABETICAL_DESC -> "postTitle"
					else -> "totalViews"
				},
			)
			append("&searchTerm=")
			filter.query?.let { append(it.urlEncoded()) }

			if (filter.tags.isNotEmpty()) {
				append("&genreIds=")
				filter.tags.joinTo(this, ",") { it.key }
			}

			filter.types.firstOrNull()?.toApiSeriesType()?.let {
				append("&seriesType=")
				append(it)
			}

			filter.states.firstOrNull()?.toApiSeriesStatus()?.let {
				append("&seriesStatus=")
				append(it)
			}
		}
		return parseMangaList(webClient.httpGet(url).parseJson())
	}

	override fun parseMangaList(json: JSONObject): List<Manga> {
		val posts = json.optJSONArray("posts") ?: return emptyList()
		return posts.mapJSONNotNull { post ->
			val slug = post.getStringOrNull("slug") ?: return@mapJSONNotNull null
			val url = "/series/$slug"
			val isAdult = post.getBooleanOrDefault("hot", false)
			val state = when (post.getStringOrNull("seriesStatus")?.uppercase(Locale.ROOT)) {
				"ONGOING" -> MangaState.ONGOING
				"HIATUS" -> MangaState.PAUSED
				"COMPLETED" -> MangaState.FINISHED
				"DROPPED", "CANCELLED" -> MangaState.ABANDONED
				"COMING_SOON" -> MangaState.UPCOMING
				else -> null
			}
			Manga(
				id = post.getLongOrDefault("id", generateUid(url)),
				url = url,
				publicUrl = url.toAbsoluteUrl(domain),
				coverUrl = post.getStringOrNull("featuredImage"),
				title = post.getStringOrNull("postTitle")
					?: post.getStringOrNull("title")
					?: slug,
				altTitles = setOfNotNull(post.getStringOrNull("alternativeTitles")),
				description = post.getStringOrNull("postContent"),
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				authors = setOfNotNull(post.getStringOrNull("author")),
				state = state,
				source = source,
				contentRating = if (isAdult) ContentRating.ADULT else null,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val url = "https://$apiDomain/api/chapters?postId=${manga.id}&skip=0&take=900&order=desc&userid="
		val post = webClient.httpGet(url).parseJson().optJSONObject("post") ?: return manga
		val dateFormat = SimpleDateFormat(datePattern, Locale.ENGLISH)
		val seriesSlug = post.getStringOrNull("slug")
		val chapters = post.optJSONArray("chapters")
			?.asTypedList<JSONObject>()
			.orEmpty()
			.mapChapters(reversed = true) { i, ch ->
				val slug = seriesSlug ?: ch.optJSONObject("mangaPost")?.getStringOrNull("slug") ?: return@mapChapters null
				val chapterSlug = ch.getStringOrNull("slug") ?: return@mapChapters null
				val chapterUrl = "/series/$slug/$chapterSlug"
				MangaChapter(
					id = ch.getLongOrDefault("id", generateUid(chapterUrl)),
					title = ch.getStringOrNull("title"),
					number = ch.getFloatOrDefault("number", i + 1f),
					volume = 0,
					url = chapterUrl,
					scanlator = null,
					uploadDate = dateFormat.parseSafe(ch.getStringOrNull("createdAt")?.substringBefore("T")),
					branch = null,
					source = source,
				)
			}
		return manga.copy(chapters = chapters)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> = super.getPages(chapter)

	override suspend fun fetchAvailableTags(): Set<MangaTag> {
		val fromArray = runCatching {
			webClient.httpGet("https://$apiDomain/api/genres")
				.parseJsonArray()
				.toGenreTags()
		}.getOrNull().orEmpty()
		if (fromArray.isNotEmpty()) {
			return fromArray
		}

		val fromObject = runCatching {
			val obj = webClient.httpGet("https://$apiDomain/api/genres").parseJson()
			(obj.optJSONArray("genres") ?: obj.optJSONArray("data") ?: JSONArray()).toGenreTags()
		}.getOrNull().orEmpty()
		if (fromObject.isNotEmpty()) {
			return fromObject
		}

		return super.fetchAvailableTags()
	}

	private fun JSONArray.toGenreTags(): Set<MangaTag> = mapJSONNotNull { item ->
		val key = item.opt("id")?.toString()?.trim().orEmpty()
		val title = item.getStringOrNull("name")
			?: item.getStringOrNull("title")
		if (key.isEmpty() || title.isNullOrBlank()) {
			null
		} else {
			MangaTag(
				key = key,
				title = title,
				source = source,
			)
		}
	}.toSet()

	private fun MangaState.toApiSeriesStatus(): String? = when (this) {
		MangaState.ONGOING -> "ONGOING"
		MangaState.PAUSED -> "HIATUS"
		MangaState.FINISHED -> "COMPLETED"
		MangaState.ABANDONED -> "DROPPED"
		else -> null
	}

	private fun ContentType.toApiSeriesType(): String? = when (this) {
		ContentType.MANGA -> "MANGA"
		ContentType.MANHWA -> "MANHWA"
		ContentType.MANHUA -> "MANHUA"
		ContentType.OTHER -> "RUSSIAN"
		else -> null
	}
}
