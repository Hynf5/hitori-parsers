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

	// Hack Tombol Sortir jadi Tipe Manga
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
			val isGenre = filter.tags.isNotEmpty()
			val isAuthor = !filter.author.isNullOrBlank()

			// 🔥 FIX 1: Format Path Wajib Pakai Trailing Slash (/)
			var basePath = ""

			if (isGenre) {
				val tagSlug = filter.tags.first().key
				basePath = "genre/$tagSlug/"
			} else if (isAuthor) {
				val authorSlug = filter.author!!.lowercase().trim().replace(Regex("\\s+"), "-")
				basePath = "author/$authorSlug/"
			} else {
				basePath = "manga/"
			}

			if (page > 1) {
				basePath += "page/$page/"
			}

			// Masukin path yang udah komplit sama garis miringnya
			addPathSegments(basePath)

			// 🔥 FIX 2: Jangan ganggu halaman Genre/Author pakai Query Parameter!
			if (!isGenre && !isAuthor) {
				if (!filter.query.isNullOrBlank()) {
					addQueryParameter("title", filter.query)
				}

				addQueryParameter(
					"type",
					when (order) {
						SortOrder.UPDATED -> "Manga"
						SortOrder.NEWEST -> "Doujinshi"
						SortOrder.POPULARITY -> "Manhwa"
						SortOrder.ALPHABETICAL -> ""
						else -> "Manga"
					}
				)

				addQueryParameter("order", "update")

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
		
		// 🔥 FIX 3: Perluas Jaring Tangkapan HTML (Biar halaman Genre gak error)
		val elements = response.select("#archives .entry, section#archives .entry, .entries .entry, .postbody .entry")

		return elements.mapNotNull {
			val href = it.selectFirst(".metadata > a, a")?.attr("href") ?: return@mapNotNull null
			Manga(
				id = generateUid(href),
				title = it.selectFirst(".metadata > a")?.attr("title") ?: it.selectFirst(".title")?.text() ?: "Unknown",
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.ADULT,
				coverUrl = it.selectFirst(".thumbnail > img, img")?.src(),
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
					key = it.attr("href").trimEnd('/').substringAfterLast('/'), // Ambil slug asli dari URL!
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
				// 🔥 FIX 4: Jangan nebak dari judul, tapi CULIK langsung dari link aslinya
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
    
