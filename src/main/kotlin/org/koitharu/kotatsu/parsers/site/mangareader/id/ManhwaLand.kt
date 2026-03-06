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

	// 🔥 FIX 1: Paksa semua link gambar jadi HTTPS
	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val pages = super.getPages(chapter)
		return pages.map { page ->
			page.copy(url = page.url.replace("http://", "https://"))
		}
	}

	// 🔥 FIX 2: Interceptor "Preman" - Nyuntik Referer & UA ke semua request ManhwaLand
	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val host = request.url.host
		
		// Cek kalau request lari ke domain manhwaland (baby atau email)
		if (host.contains("manhwaland")) {
			val newRequest = request.newBuilder()
				.removeHeader("Referer") // Hapus referer lama biar gak bentrok
				.addHeader("Referer", "https://www.manhwaland.baby/")
				// Pakai User-Agent Chrome PC biar gak dicurigai bot mobile
				.removeHeader("User-Agent")
				.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
				.build()
			return chain.proceed(newRequest)
		}
		
		return chain.proceed(request)
	}
    }
    
