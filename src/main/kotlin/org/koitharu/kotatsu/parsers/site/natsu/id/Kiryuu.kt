package org.koitharu.kotatsu.parsers.site.natsu.id

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.natsu.NatsuParser
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.parseHtml

@MangaSourceParser("KIRYUU", "Kiryuu", "id")
internal class Kiryuu(context: MangaLoaderContext) :
    NatsuParser(context, MangaParserSource.KIRYUU, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("v1.kiryuu.to", "kiryuu03.com")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(ConfigKey.DisableUpdateChecking(defaultValue = true))
    }

    override suspend fun loadChapters(
        mangaId: String,
        mangaAbsoluteUrl: String,
    ): List<MangaChapter> {
        val headers = Headers.headersOf(
            "hx-request", "true",
            "hx-target", "chapter-list",
            "hx-trigger", hxTrigger,
            "Referer", mangaAbsoluteUrl,
        )
        val url = "https://${domain}/wp-admin/admin-ajax.php?manga_id=$mangaId&page=1&action=chapter_list"
        val doc = webClient.httpGet(url, headers).parseHtml()

        return doc.select("div#chapter-list > div[data-chapter-number]").mapNotNull { element ->
            val a = element.selectFirst("a") ?: return@mapNotNull null
            val href = a.attrAsRelativeUrl("href")
            if (href.isBlank()) return@mapNotNull null

            MangaChapter(
                id = generateUid(href),
                title = element.selectFirst("div.font-medium span")?.text()?.trim().orEmpty(),
                url = href,
                number = element.attr("data-chapter-number").toFloatOrNull() ?: -1f,
                volume = 0,
                scanlator = null,
                uploadDate = parseDate(element.selectFirst("time")?.text()),
                branch = null,
                source = source,
            )
        }.reversed()
    }
    }
