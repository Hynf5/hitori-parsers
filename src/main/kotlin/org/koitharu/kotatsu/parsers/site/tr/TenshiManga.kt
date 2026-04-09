package org.koitharu.kotatsu.parsers.site.tr

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.uzaymanga.UzayMangaParser

@MangaSourceParser("TENSHIMANGA", "Tenshi Manga", "tr")
internal class TenshiManga(context: MangaLoaderContext) :
	UzayMangaParser(
		context = context,
		source = MangaParserSource.TENSHIMANGA,
		domain = "tenshimanga.com",
		cdnUrl = "https://tenshimangacdn4.efsaneler.can.re",
	)
