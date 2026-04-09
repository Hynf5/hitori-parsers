package org.koitharu.kotatsu.parsers.site.mangareader.tr

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.initmanga.InitMangaParser

@MangaSourceParser("MERLINSCANS", "MerlinScans", "tr")
internal class MerlinScans(context: MangaLoaderContext) :
	InitMangaParser(
		context = context,
		source = MangaParserSource.MERLINSCANS,
		domain = "merlintoon.com",
		pageSize = 20,
		searchPageSize = 20,
		latestUrlSlug = "son-guncellenenler",
	)
