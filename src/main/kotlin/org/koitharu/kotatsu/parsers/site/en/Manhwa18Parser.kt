package org.koitharu.kotatsu.parsers.site.en

import androidx.collection.ArrayMap
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANHWA18", "Manhwa18.net", "en", type = ContentType.HENTAI)
internal class Manhwa18Parser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANHWA18, pageSize = 18, searchPageSize = 18), Interceptor {

	override val configKeyDomain: ConfigKey.Domain = ConfigKey.Domain("manhwa18.net")

	override val userAgentKey: ConfigKey.UserAgent = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Referer", "https://$domain/")
		.build()

	override val availableSortOrders: Set<SortOrder>
		get() = EnumSet.of(
			SortOrder.UPDATED,
			SortOrder.POPULARITY,
			SortOrder.ALPHABETICAL,
			SortOrder.NEWEST,
			SortOrder.RATING,
		)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = tagsMap.get().values.toSet(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
		),
	)

	override suspend fun getFavicons(): Favicons {
		return Favicons(
			listOf(
				Favicon("https://$domain/favicon.ico", 32, null),
			),
			domain,
		)
	}

	private val dateFormat by lazy {
		SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.ENGLISH)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			append("/tim-kiem?page=")
			append(page.toString())

			filter.query?.let {
				append("&q=")
				append(filter.query.urlEncoded())
			}

			if (filter.tags.isNotEmpty()) {
				append("&accept_genres=")
				append(filter.tags.joinToString(",") { it.key })
			}

			if (filter.tagsExclude.isNotEmpty()) {
				append("&reject_genres=")
				append(filter.tagsExclude.joinToString(",") { it.key })
			}

			append("&sort=")
			append(
				when (order) {
					SortOrder.ALPHABETICAL -> "az"
					SortOrder.ALPHABETICAL_DESC -> "za"
					SortOrder.POPULARITY -> "top"
					SortOrder.UPDATED -> "update"
					SortOrder.NEWEST -> "new"
					SortOrder.RATING -> "like"
					else -> "update"
				},
			)

			filter.states.oneOrThrowIfMany()?.let {
				append("&status=")
				append(
					when (it) {
						MangaState.ONGOING -> "1"
						MangaState.FINISHED -> "3"
						MangaState.PAUSED -> "2"
						else -> ""
					},
				)
			}
		}

		val doc = webClient.httpGet(url).parseHtml()
		val dataPage = doc.requireElementById("app").attr("data-page")
		val json = JSONObject(dataPage)
		val props = json.getJSONObject("props")
		val mangasData = props.getJSONObject("mangas")
		val mangas = mangasData.getJSONArray("data")

		return mangas.mapJSON { mangaJson ->
			val slug = mangaJson.getString("slug")
			val mangaUrl = "/manga/$slug"
			val cover = mangaJson.optString("cover_url").nullIfEmpty() ?: mangaJson.optString("thumb_url").nullIfEmpty()
			Manga(
				id = generateUid(mangaUrl),
				title = mangaJson.getString("name"),
				altTitles = setOfNotNull(mangaJson.optString("other_name").nullIfEmpty()),
				url = mangaUrl,
				publicUrl = "https://$domain$mangaUrl",
				rating = mangaJson.optDouble("rating_average", 0.0).toFloat() / 5f,
				contentRating = ContentRating.ADULT,
				coverUrl = cover,
				largeCoverUrl = cover,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				description = null,
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val dataPage = doc.requireElementById("app").attr("data-page")
		val json = JSONObject(dataPage)
		val props = json.getJSONObject("props")
		val mangaData = props.getJSONObject("manga")
		val availableTags = tagsMap.get()

		val artistsArray = mangaData.optJSONArray("artists")
		val artists = artistsArray?.mapJSON { it.getString("name") }?.toSet() ?: emptySet()
		val genres = mangaData.optJSONArray("genres")?.mapJSONNotNull { availableTags[it.getString("name").lowercase(Locale.ENGLISH)] }?.toSet() ?: emptySet()

		val statusId = mangaData.optInt("status_id", -1)
		val state = when (statusId) {
			0 -> MangaState.ONGOING
			2 -> MangaState.FINISHED
			else -> null
		}

		val chaptersArray = props.optJSONArray("chapters") ?: JSONArray()
		val chapters = chaptersArray.mapJSON { chapterJson ->
			val chapterSlug = chapterJson.getString("slug")
			val chapterUrl = "${manga.url}/$chapterSlug"
			MangaChapter(
				id = generateUid(chapterUrl),
				title = chapterJson.getString("name"),
				number = chapterJson.optDouble("order", 0.0).toFloat(),
				volume = 0,
				url = chapterUrl,
				scanlator = chapterJson.optString("translator_group").nullIfEmpty(),
				uploadDate = dateFormat.parseSafe(chapterJson.optString("created_at")),
				branch = null,
				source = source,
			)
		}.reversed()

		val cover = mangaData.optString("cover_url").nullIfEmpty() ?: mangaData.optString("thumb_url").nullIfEmpty()

		return manga.copy(
			title = mangaData.getString("name"),
			altTitles = setOfNotNull(mangaData.optString("other_name").nullIfEmpty()),
			authors = artists,
			description = mangaData.optString("pilot").nullIfEmpty(),
			tags = genres,
			state = state,
			chapters = chapters,
			coverUrl = cover ?: manga.coverUrl,
			largeCoverUrl = cover ?: manga.largeCoverUrl,
			rating = mangaData.optDouble("rating_average", 0.0).toFloat() / 5f,
		)
	}

	override suspend fun resolveLink(resolver: LinkResolver, link: HttpUrl): Manga? {
		val path = link.encodedPath
		if (!path.startsWith("/manga/")) return null
		val slug = path.removePrefix("/manga/").substringBefore("/")
		if (slug.isEmpty()) return null
		val mangaUrl = "/manga/$slug"
		return resolver.resolveManga(this, mangaUrl)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val dataPage = doc.requireElementById("app").attr("data-page")
		val json = JSONObject(dataPage)
		val props = json.getJSONObject("props")
		val content = props.getString("chapterContent")
		
		return Jsoup.parse(content, "https://$domain").select("img").mapNotNull {
			val url = it.src() ?: return@mapNotNull null
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val url = request.url.toString()
		if (url.contains(domain) || url.contains("cdn.pornwa.") || url.contains("min.manhwa18.net")) {
			val newRequest = request.newBuilder()
				.header("Referer", "https://$domain/")
				.build()
			return chain.proceed(newRequest)
		}
		return chain.proceed(request)
	}

	private val tagsMap = suspendLazy(initializer = ::parseTags)

	private suspend fun parseTags(): Map<String, MangaTag> {
		val doc = webClient.httpGet("https://$domain/tim-kiem").parseHtml()
		val dataPage = doc.selectFirst("div#app")?.attr("data-page") ?: return emptyMap()
		val json = JSONObject(dataPage)
		val props = json.getJSONObject("props")
		val genres = props.optJSONArray("genres") ?: return emptyMap()
		
		val result = ArrayMap<String, MangaTag>(genres.length())
		for (i in 0 until genres.length()) {
			val item = genres.getJSONObject(i)
			val id = item.getInt("id").toString()
			val name = item.getString("name")
			result[name.lowercase(Locale.ENGLISH)] = MangaTag(
				title = name,
				key = id,
				source = source,
			)
		}
		return result
	}
}
