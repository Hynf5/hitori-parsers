package org.koitharu.kotatsu.parsers.site.mangareader.id

import okhttp3.Interceptor
import okhttp3.Response
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

	// 🔥 FIX 1: Paksa semua link gambar yang jadul (http://) jadi aman (https://)
	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val pages = super.getPages(chapter)
		return pages.map { page ->
			page.copy(url = page.url.replace("http://", "https://"))
		}
	}

	// 🔥 FIX 2: SUNTIK PAKSA KTP (Referer) langsung ke urat nadi CDN!
	// Ini bakal ngebypass pemblokiran keamanan Kotatsu dan satpam CDN.
	override fun intercept(chain: Interceptor.Chain): Response {
		var request = chain.request()
		
		if (request.url.host.contains("manhwaland")) {
			request = request.newBuilder()
				.header("Referer", "https://$domain/")
				.header("Origin", "https://$domain")
				.header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
				.header("User-Agent", config[userAgentKey])
				.build()
		}
		
		return chain.proceed(request)
	}
    }
    
