package org.koitharu.kotatsu.parsers.site.mangareader.ar

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.EnumSet

@MangaSourceParser("THUNDERSCANS", "ThunderScans", "ar")
internal class ThunderScans(context: MangaLoaderContext) :
	MangaReaderParser(
		context,
		MangaParserSource.THUNDERSCANS,
		"lavascans.com",
		pageSize = 32,
		searchPageSize = 10,
	) {
	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.ALPHABETICAL,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = super.filterCapabilities.copy(
			isTagsExclusionSupported = false,
		)

	override val listUrl = "/browse-manga"
	override val selectMangaList = "article.legend-card"
	override val selectMangaListImg = "img.legend-img"
	override val selectMangaListTitle = "h3.legend-title a"
	override val selectChapter = ".ch-list-grid .ch-item"
	override val datePattern = "yyyy/MM/dd"
	override val detailsDescriptionSelector = ".lh-story-content, #manga-story"

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = emptySet(),
		availableContentTypes = emptySet(),
	)

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain$listUrl/").parseHtml()
		return doc.select(".genres-selection-grid button.genre-select-item").mapNotNullToSet { button ->
			val slug = button.attr("data-slug").ifEmpty { return@mapNotNullToSet null }
			val title = button.text().trim().ifEmpty { return@mapNotNullToSet null }
			MangaTag(
				key = slug,
				title = title,
				source = source,
			)
		}
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)

			when {
				!filter.query.isNullOrEmpty() -> {
					append("/page/")
					append(page.toString())
					append("/?s=")
					append(filter.query.urlEncoded())
				}

				else -> {
					append(listUrl)
					append("/?order=")
					append(
						when (order) {
							SortOrder.ALPHABETICAL -> "title"
							SortOrder.POPULARITY -> "popular"
							SortOrder.NEWEST -> "new"
							else -> "new"
						},
					)

					filter.tags.forEach {
						append("&genre=")
						append(it.key.urlEncoded())
					}

					append("&page=")
					append(page.toString())
				}
			}
		}
		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	override fun parseMangaList(docs: Document): List<Manga> {
		return docs.select(selectMangaList).mapNotNull {
			val a = it.selectFirst("a.legend-poster") ?: return@mapNotNull null
			val relativeUrl = a.attrAsRelativeUrl("href")

			// Rating in format "9.0" from div.legend-rating
			val ratingText = it.selectFirst(".legend-rating")?.text()?.trim()
			val rating = ratingText?.substringAfterLast(" ")?.toFloatOrNull()?.div(10f) ?: RATING_UNKNOWN

			// Status from div.legend-ribbon class
			val statusDiv = it.selectFirst(".legend-ribbon")
			val state = when {
				statusDiv?.hasClass("ongoing") == true -> MangaState.ONGOING
				statusDiv?.text()?.contains("مستمر", ignoreCase = true) == true -> MangaState.ONGOING
				statusDiv?.text()?.contains("مكتمل", ignoreCase = true) == true -> MangaState.FINISHED
				statusDiv?.text()?.contains("completed", ignoreCase = true) == true -> MangaState.FINISHED
				else -> null
			}

			Manga(
				id = generateUid(relativeUrl),
				url = relativeUrl,
				title = it.selectFirst(selectMangaListTitle)?.text() ?: a.attr("title"),
				altTitles = emptySet(),
				publicUrl = a.attrAsAbsoluteUrl("href"),
				rating = rating,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
				coverUrl = it.selectFirst(selectMangaListImg)?.src(),
				tags = emptySet(),
				state = state,
				authors = emptySet(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)

		// Parse chapters from new structure
		val chapters = doc.select(selectChapter).mapChapters(reversed = true) { index, element ->
			// Skip locked/premium chapters
			if (element.hasClass("locked")) return@mapChapters null

			val a = element.selectFirst("a.ch-main-anchor") ?: return@mapChapters null
			val url = a.attrAsRelativeUrlOrNull("href") ?: return@mapChapters null
			val chapterTitle = element.selectFirst(".ch-num")?.text()
			val chapterDate = element.selectFirst(".ch-date")?.text()

			MangaChapter(
				id = generateUid(url),
				title = chapterTitle,
				url = url,
				number = index + 1f,
				volume = 0,
				scanlator = null,
				uploadDate = dateFormat.parseSafe(chapterDate),
				branch = null,
				source = source,
			)
		}

		// Parse manga info from new structure
		val title = doc.selectFirst("h1.lh-title")?.text() ?: manga.title
		val coverUrl = doc.selectFirst(".lh-poster img")?.src() ?: manga.coverUrl
		val description = doc.selectFirst(detailsDescriptionSelector)?.text()

		// Parse rating from meta items
		val ratingText = doc.select(".lh-meta-item").find { it.text().contains("★") || it.selectFirst("i.fa-star") != null }?.text()
		val rating = ratingText?.replace("[^0-9.]".toRegex(), "")?.toFloatOrNull()?.div(10f) ?: RATING_UNKNOWN

		// Parse status
		val statusText = doc.selectFirst(".lh-meta-item.status-badge-lux")?.text()
		val state = when {
			statusText?.contains("مستمر", ignoreCase = true) == true -> MangaState.ONGOING
			statusText?.contains("مكتمل", ignoreCase = true) == true -> MangaState.FINISHED
			statusText?.contains("متوقف", ignoreCase = true) == true -> MangaState.PAUSED
			else -> null
		}

		// Parse genres/tags
		val tags = doc.select(".lh-genres a.lh-genre-tag").mapNotNullToSet { tag ->
			val tagTitle = tag.text().trim()
			if (tagTitle.isNotEmpty()) {
				MangaTag(
					key = tag.attr("href").substringAfterLast("/").removeSuffix("/"),
					title = tagTitle,
					source = source,
				)
			} else {
				null
			}
		}

		return manga.copy(
			title = title,
			coverUrl = coverUrl,
			description = description,
			rating = rating,
			state = state,
			tags = tags,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()


		// Select all images in the reader area, regardless of class
        return doc.select(".reader-area img, #readerarea img").mapNotNull { img ->
        val imageUrl = img.src()
			if (imageUrl.isNullOrBlank() || imageUrl.startsWith("data:")) {
				return@mapNotNull null
			}
			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}
	}
}
