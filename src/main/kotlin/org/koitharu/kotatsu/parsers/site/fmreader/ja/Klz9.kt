package org.koitharu.kotatsu.parsers.site.fmreader.ja

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.fmreader.FmreaderParser
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("KLZ9", "Klz9", "ja")
internal class Klz9(context: MangaLoaderContext) :
	FmreaderParser(context, MangaParserSource.KLZ9, "klz9.com") {

	override val selectDesc = "div.row:contains(Description) p"
	override val selectState = "ul.manga-info li:contains(Status) a"
	override val selectAlt = "ul.manga-info li:contains(Other name (s))"
	override val selectTag = "ul.manga-info li:contains(Genre(s)) a"
	override val selectChapter = "tr"
	override val selectDate = "td i"
	override val selectPage = "img"
	override val selectBodyTag = "div.panel-body a"

	private val clientSecret = "KL9K40zaSyC9K40vOMLLbEcepIFBhUKXwELqxlwTEF"

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = "https://$domain/api/manga/list".toHttpUrl().newBuilder().apply {
			addQueryParameter("page", page.toString())
			addQueryParameter("limit", "36")

			// Handle search query
			if (!filter.query.isNullOrEmpty()) {
				addQueryParameter("search", filter.query)
			}

			// Handle sort order
			when (order) {
				SortOrder.POPULARITY -> {
					addQueryParameter("sort", "Popular")
					addQueryParameter("order", "desc")
				}
				SortOrder.UPDATED -> {
					addQueryParameter("sort", "last_update")
					addQueryParameter("order", "desc")
				}
				SortOrder.ALPHABETICAL -> {
					addQueryParameter("sort", "name")
					addQueryParameter("order", "asc")
				}
				SortOrder.ALPHABETICAL_DESC -> {
					addQueryParameter("sort", "name")
					addQueryParameter("order", "desc")
				}
				else -> {
					addQueryParameter("sort", "Popular")
					addQueryParameter("order", "desc")
				}
			}
		}.build()

		val json = webClient.httpGet(url, createApiHeaders()).parseJson()
		val itemsArray = json.optJSONArray("items") ?: return emptyList()

		return itemsArray.mapJSON { jo ->
			parseMangaFromJson(jo)
		}
	}

	private fun parseMangaFromJson(jo: JSONObject): Manga {
		val slug = jo.getString("slug")
		val title = jo.getString("name")
		val coverUrl = jo.optString("cover", "")

		return Manga(
			id = generateUid(slug),
			url = slug,
			publicUrl = "https://$domain/$slug",
			coverUrl = coverUrl,
			title = title,
			altTitles = emptySet(),
			rating = RATING_UNKNOWN,
			tags = emptySet(),
			authors = emptySet(),
			state = null,
			source = source,
			contentRating = ContentRating.SAFE,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val slug = manga.url
		val url = "https://$domain/api/manga/slug/$slug"

		val json = webClient.httpGet(url, createApiHeaders()).parseJson()
		val chaptersDeferred = async { getChaptersFromJson(json) }

		// Parse tags/genres - genres is a comma-separated string
		val tags = json.optString("genres", "").split(",").mapNotNullToSet { genre ->
			val trimmed = genre.trim()
			if (trimmed.isNotEmpty()) {
				MangaTag(
					key = trimmed.lowercase().replace(" ", "-"),
					title = trimmed,
					source = source,
				)
			} else {
				null
			}
		}

		// Parse state - m_status is a number (1=completed, 2=ongoing, 3=hiatus?)
		val state = when (json.optInt("m_status", 0)) {
			1 -> MangaState.FINISHED
			2 -> MangaState.ONGOING
			3 -> MangaState.PAUSED
			else -> null
		}

		// Combine authors and artists
		val authors = buildSet {
			json.optString("authors", "").nullIfEmpty()?.let { add(it) }
			json.optString("artists", "").nullIfEmpty()?.let { add(it) }
		}

		manga.copy(
			title = json.optString("name", manga.title),
			description = json.optString("description", "").nullIfEmpty(),
			coverUrl = json.optString("cover", manga.coverUrl),
			altTitles = setOfNotNull(json.optString("other_name", "").nullIfEmpty()),
			authors = authors,
			tags = tags,
			state = state,
			chapters = chaptersDeferred.await(),
		)
	}

	private suspend fun getChaptersFromJson(data: JSONObject): List<MangaChapter> {
		val chaptersArray = data.optJSONArray("chapters") ?: return emptyList()

		return chaptersArray.mapJSON { chapterObj ->
			val chapterId = chapterObj.getLong("id")
			val chapterNumber = chapterObj.optDouble("chapter", 0.0).toFloat()
			val chapterTitle = chapterObj.optString("name", "").nullIfEmpty()
			val uploadDate = parseChapterDate(chapterObj.optString("last_update", ""))

			// Format chapter number to remove .0 if it's a whole number
			val formattedNumber = if (chapterNumber % 1 == 0f) {
				chapterNumber.toInt().toString()
			} else {
				chapterNumber.toString()
			}

			val title = if (chapterTitle != null) {
				"Chapter $formattedNumber: $chapterTitle"
			} else {
				"Chapter $formattedNumber"
			}

			MangaChapter(
				id = generateUid(chapterId),
				title = title,
				number = chapterNumber,
				volume = 0,
				url = chapterId.toString(),
				uploadDate = uploadDate,
				source = source,
				scanlator = null,
				branch = null,
			)
		}.reversed() // Reverse to get ascending order
	}

	private fun parseChapterDate(dateString: String): Long {
		if (dateString.isEmpty()) return 0L
		return try {
			// Parse ISO 8601 date format: "2026-01-04T08:20:01.000Z"
			val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
			format.timeZone = TimeZone.getTimeZone("UTC")
			format.parse(dateString)?.time ?: 0L
		} catch (e: Exception) {
			0L
		}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		// chapter.url is now a chapter ID
		val chapterId = chapter.url
		val url = "https://$domain/api/chapter/$chapterId"

		val json = webClient.httpGet(url, createApiHeaders()).parseJson()
		val content = json.optString("content", "")

		if (content.isNotEmpty()) {
			// Content is newline-separated list of image URLs
			val imageUrls = content.split("\n", "\r\n", "\r")
				.map { it.trim() }
				.filter { it.isNotEmpty() && it.startsWith("http") }

			return imageUrls.mapIndexed { index, imageUrl ->
				MangaPage(
					id = generateUid(imageUrl),
					url = imageUrl,
					preview = null,
					source = source,
				)
			}
		}

		// Fallback: try HTML page if API fails
		val fullUrl = "https://$domain/chapter/$chapterId"
		val doc = webClient.httpGet(fullUrl).parseHtml()
		val cid = doc.selectFirst("#chapter")?.attr("value")

		// If we can find the chapter ID, use the dynamic image loading method
		if (!cid.isNullOrEmpty()) {
			val dynamicPath = generateRandomStr()
			val imageUrlListUrl = "https://$domain/$dynamicPath.iog?cid=$cid"
			val headers = Headers.headersOf("Referer", fullUrl)
			val docLoad = webClient.httpGet(imageUrlListUrl, headers).parseHtml()

			val allImages = docLoad.select(selectPage)
			//remove page with ads
			val actualPages = allImages.filter { element ->
				element.attr("alt").startsWith("Page", ignoreCase = true)
			}

			return actualPages.map { img ->
				val url = img.requireSrc().toRelativeUrl(domain)
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			}
		}

		// Last fallback: try to extract images directly from the page
		val images = doc.select("img[alt^=Page]")
		return images.mapIndexed { index, img ->
			val url = img.requireSrc().toRelativeUrl(domain)
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private fun generateRandomStr(): String {
		return (1..30).map { toPathCharacters.random() }.joinToString("")
	}

	private val toPathCharacters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

	private fun createApiHeaders(): Headers {
		val timestamp = (System.currentTimeMillis() / 1000).toString()
		val message = "$timestamp.$clientSecret"

		val digest = MessageDigest.getInstance("SHA-256")
		val hash = digest.digest(message.toByteArray(Charsets.UTF_8))
		val signature = hash.joinToString("") { "%02x".format(it) }

		return Headers.Builder()
			.add("Content-Type", "application/json")
			.add("x-client-ts", timestamp)
			.add("x-client-sig", signature)
			.build()
	}
}
