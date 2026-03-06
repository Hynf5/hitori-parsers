package org.dokiteam.doki.parsers.site.natsu.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.natsu.NatsuParser

@MangaSourceParser("KIRYUUT", "KiryuuT", "id")
internal class KiryuuT(context: MangaLoaderContext) :
    NatsuParser(context, MangaParserSource.KIRYUUT, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("v1.kiryuu.to")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(ConfigKey.DisableUpdateChecking(defaultValue = true))
    }
}

