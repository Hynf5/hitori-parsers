package org.koitharu.kotatsu.parsers.site.madara.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("HOTOON", "Holotoon", "id")
internal class Holotoon(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.HOTOON, "01.holotoon.site") {
    
    override val tagPrefix = "komik-genre/"
    override val listUrl = "komik/"
}
