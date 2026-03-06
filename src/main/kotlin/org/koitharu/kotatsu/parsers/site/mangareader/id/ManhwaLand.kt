package org.koitharu.kotatsu.parsers.site.mangareader.id

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser

@MangaSourceParser("MANHWALAND", "ManhwaLand.vip", "id", ContentType.HENTAI)
internal class ManhwaLand(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.MANHWALAND, "www.manhwaland.baby", pageSize = 20, searchPageSize = 10) {
	
	override val filterCapabilities: MangaListFilterCapabilities
		get() = super.filterCapabilities.copy(
			isTagsExclusionSupported = false,
		)
		
	override val datePattern = "MMM d, yyyy"

	// 🔥 FIX 1: Perkuat Headers! Tambahin Origin & Accept biar 100% mirip Browser Asli
	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("Referer", "https://$domain/")
		.add("Origin", "https://$domain")
		.add("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
		.add("User-Agent", config[userAgentKey])
		.build()

	// 🔥 FIX 2: Cegat URL gambarnya dan PAKSA ganti dari http:// jadi https://
	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val pages = super.getPages(chapter)
		return pages.map { page ->
			// Ubah link paksa ke jalur aman biar nggak kena blokir 403
			page.copy(url = page.url.replace("http://", "https://"))
		}
	}
    }
    
