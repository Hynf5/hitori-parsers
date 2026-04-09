package org.koitharu.kotatsu.parsers.site.madara.tr

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.initmanga.InitMangaParser

@MangaSourceParser("RAGNARSCANS", "Ragnarscans", "tr")
internal class Ragnarscans(context: MangaLoaderContext) :
	InitMangaParser(
		context = context,
		source = MangaParserSource.RAGNARSCANS,
		domain = "ragnarscans.com",
		pageSize = 20,
		searchPageSize = 20,
		mangaUrlDirectory = "manga",
		popularUrlSlug = "en-cok-takip-edilenler",
		isCloudflareProtected = true,
	)
