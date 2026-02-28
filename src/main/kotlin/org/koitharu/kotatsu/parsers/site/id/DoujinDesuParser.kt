package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("DOUJINDESU", "DoujinDesu.tv", "id", ContentType.HENTAI)
internal class DoujinDesuParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.DOUJINDESU, pageSize = 18) {

	override val configKeyDomain: ConfigKey.Domain
		get() = ConfigKey.Domain("doujindesu.tv")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder>
		get() = EnumSet.of(SortOrder.UPDATED, SortOrder.NEWEST, SortOrder.POPULARITY, SortOrder.ALPHABETICAL)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isAuthorSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		availableContentTypes = EnumSet.noneOf(ContentType::class.java),
	)

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("X-Requested-With", "XMLHttpRequest")
		.add("Referer", "https://$domain/")
		.build()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = urlBuilder().apply {
			val isSearch = !filter.query.isNullOrBlank()
			val isGenre = filter.tags.isNotEmpty()
			val isAuthor = !filter.author.isNullOrBlank()

			if (isSearch) {
				if (page > 1) {
					addPathSegment("page")
					addPathSegment(page.toString())
					addPathSegment("") 
				}
				addQueryParameter("s", filter.query)
				
				// Cuma masukin tipe kalau BUKAN Abjad
				if (order != SortOrder.ALPHABETICAL) {
					addQueryParameter(
						"type",
						when (order) {
							SortOrder.UPDATED -> "Manga"
							SortOrder.NEWEST -> "Doujinshi"
							SortOrder.POPULARITY -> "Manhwa"
							else -> "Manga"
						}
					)
				}
			} else if (isGenre) {
				val tagSlug = filter.tags.first().key
				addPathSegment("genre")
				addPathSegment(tagSlug)
				addPathSegment("")
				if (page > 1) {
					addPathSegment("page")
					addPathSegment(page.toString())
					addPathSegment("")
				}
			} else if (isAuthor) {
				val authorSlug = filter.author!!.lowercase().trim().replace(Regex("\\s+"), "-")
				addPathSegment("author")
				addPathSegment(authorSlug)
				addPathSegment("")
				if (page > 1) {
					addPathSegment("page")
					addPathSegment(page.toString())
					addPathSegment("")
				}
			} else {
				addPathSegment("manga")
				addPathSegment("")
				if (page > 1) {
					addPathSegment("page")
					addPathSegment(page.toString())
					addPathSegment("")
				}

				// 🔥 FIX: Kalau milih Nama/Abjad, biarkan URL bersih (doujindesu.tv/manga/)
				if (order != SortOrder.ALPHABETICAL) {
					addQueryParameter(
						"type",
						when (order) {
							SortOrder.UPDATED -> "Manga"
							SortOrder.NEWEST -> "Doujinshi"
							SortOrder.POPULARITY -> "Manhwa"
							else -> "Manga"
						}
					)
					addQueryParameter("order", "update")
				}

				if (filter.states.isNotEmpty()) {
					filter.states.firstOrNull()?.let {
						addQueryParameter(
							"status",
							when (it) {
								MangaState.ONGOING -> "Publishing"
								MangaState.FINISHED -> "Finished"
								else -> ""
							},
						)
					}
				}
			}
		}.build()

		val response = webClient.httpGet(url).parseHtml()
		
		val elements = response.select("#archives .entry, section#archives .entry, .entries .entry, .postbody .entry, .bsx, .animepost")

		return elements.mapNotNull {
			val aTag = it.selectFirst(".metadata > a") ?: it.selectFirst("a") ?: return@mapNotNull null
			val href = aTag.attr("href")
			
			val title = it.selectFirst(".metadata > a")?.attr("title") 
				?: it.selectFirst(".title, .tt")?.text() 
				?: aTag.attr("title") 
				?: "Unknown"
				
			val coverUrl = it.selectFirst(".thumbnail > img")?.attr("src") 
				?: it.selectFirst("img")?.attr("src")

			Manga(
				id = generateUid(href),
				title = title.trim(),
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.ADULT,
				coverUrl = coverUrl,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				largeCoverUrl = null,
				description = null,
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val docs = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml().selectFirstOrThrow("#archive")
		val chapterDateFormat = SimpleDateFormat("EEEE, dd MMMM yyyy", sourceLocale)
		val metadataEl = docs.selectFirst(".wrapper > .metadata tbody")
		val state = when (metadataEl?.selectFirst("tr:contains(Status)")?.selectLast("td")?.text()) {
			"Finished" -> MangaState.FINISHED
			"Publishing" -> MangaState.ONGOING
			else -> null
		}
		val author = metadataEl?.selectFirst("tr:contains(Author)")?.selectLast("td")?.text()
		
		return manga.copy(
			authors = setOfNotNull(author),
			description = docs.selectFirst(".wrapper > .metadata > .pb-2")?.selectFirst("p")?.html(),
			state = state,
			rating = metadataEl?.selectFirst(".rating-prc")?.ownText()?.toFloatOrNull()?.div(10f) ?: RATING_UNKNOWN,
			tags = docs.select(".tags > a").mapToSet {
				MangaTag(
					key = it.attr("href").trimEnd('/').substringAfterLast('/'),
					title = it.text().trim(),
					source = source,
				)
			},
			chapters = docs.requireElementById("chapter_list")
				.select("ul > li")
				.mapChapters(reversed = true) { index, element ->
					val titleTag = element.selectFirstOrThrow(".epsleft > .lchx > a")
					val url = titleTag.attrAsRelativeUrl("href")
					MangaChapter(
						id = generateUid(url),
						title = titleTag.text(),
						number = index + 1f,
						volume = 0,
						url = url,
						scanlator = null,
						uploadDate = chapterDateFormat.parseSafe(element.select(".epsleft > .date").text()),
						branch = null,
						source = source,
					)
				},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val id = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
			.requireElementById("reader")
			.attr("data-id")
		
		return webClient.httpPost("/themes/ajax/ch.php".toAbsoluteUrl(domain), "id=$id").parseHtml()
			.select("img")
			.map {
				val url = it.attrAsRelativeUrl("src")
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		return webClient.httpGet("/genre/".toAbsoluteUrl(domain)).parseHtml()
			.requireElementById("taxonomy")
			.selectFirstOrThrow(".entries")
			.select(".entry > a")
			.mapToSet {
				val href = it.attr("href")
				val slug = href.trimEnd('/').substringAfterLast('/')
				MangaTag(
					key = slug,
					title = it.text().trim(),
					source = source,
				)
			}
	}
    }
    
