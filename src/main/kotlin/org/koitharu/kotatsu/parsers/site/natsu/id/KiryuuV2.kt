package org.koitharu.kotatsu.site.natsu.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.natsu.NatsuParser

@MangaSourceParser("KIRYUUV2", "KiryuuV2", "id")
internal class Ikiru(context: MangaLoaderContext) :
    NatsuParser(context, MangaParserSource.KIRYUUV2, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("v1.kiryuu.to")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }
}