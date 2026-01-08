package org.dokiteam.doki.parsers.site.natsu.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.natsu.NatsuParser
import org.koitharu.kotatsu.parsers.util.generateUid

@MangaSourceParser("KIRYUU", "Kiryuu", "id")
internal class Kiryuu(context: MangaLoaderContext) :
    NatsuParser(context, MangaParserSource.KIRYUU, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("kiryuu03.com")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(ConfigKey.DisableUpdateChecking(defaultValue = true))
    }

    override suspend fun loadChapters(
        mangaId: String,
        mangaAbsoluteUrl: String,
    ): List<MangaChapter> {
        // Use WebView with polling-based script to extract chapter data
        val pageScript = """
            (() => {
                // Initialize retry counter
                if (typeof window.__kiryuuRetryCount === 'undefined') {
                    window.__kiryuuRetryCount = 0;
                }

                // Check if we need to click the chapters tab first
                const tabButton = document.querySelector('button[data-key="chapters"]');
                if (tabButton && !window.__kiryuuTabClicked) {
                    console.log('[Kiryuu] Found chapters tab button, clicking...');
                    tabButton.click();
                    window.__kiryuuTabClicked = true;
                    window.__kiryuuRetryCount = 0;
                    return null; // Keep polling
                }

                // Check if chapter list has loaded
                const chapterElements = document.querySelectorAll('div#chapter-list > div[data-chapter-number]');
                if (chapterElements.length === 0) {
                    window.__kiryuuRetryCount++;
                    console.log('[Kiryuu] Waiting for chapters to load... (attempt ' + window.__kiryuuRetryCount + ')');

                    // If we've waited for 5 polls (about 5 seconds) without chapters loading, reload the page
                    if (window.__kiryuuRetryCount >= 5) {
                        if (!window.__kiryuuReloaded) {
                            console.log('[Kiryuu] Chapters not loading after 5 seconds, reloading page...');
                            window.__kiryuuReloaded = true;
                            window.location.reload();
                            return null;
                        } else {
                            // Already reloaded once, give up
                            console.log('[Kiryuu] Already reloaded once, timing out...');
                        }
                    }

                    return null; // Keep polling
                }

                // Extract chapter data from DOM
                console.log('[Kiryuu] Chapter list loaded with ' + chapterElements.length + ' chapters');
                const chapters = [];

                chapterElements.forEach(element => {
                    const a = element.querySelector('a');
                    if (!a) return;

                    const href = a.getAttribute('href');
                    if (!href) return;

                    const titleSpan = element.querySelector('div.font-medium span');
                    const title = titleSpan ? titleSpan.textContent.trim() : '';

                    const timeElement = element.querySelector('time');
                    const dateText = timeElement ? timeElement.textContent.trim() : null;
                    const dateTime = timeElement ? timeElement.getAttribute('datetime') : null;

                    const chapterNumber = element.getAttribute('data-chapter-number');

                    chapters.push({
                        url: href,
                        title: title,
                        number: chapterNumber,
                        dateText: dateText,
                        dateTime: dateTime
                    });
                });

                console.log('[Kiryuu] Extracted ' + chapters.length + ' chapters');
                return JSON.stringify(chapters);
            })();
        """.trimIndent()

        val rawResult = context.evaluateJs(mangaAbsoluteUrl, pageScript, timeout = 30000L)
            ?.takeIf { it.isNotBlank() }
            ?: throw Exception("Failed to extract chapter data from WebView")

        val jsonString = if (rawResult.startsWith("\"") && rawResult.endsWith("\"")) {
            rawResult.substring(1, rawResult.length - 1)
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace(Regex("""\\u([0-9A-Fa-f]{4})""")) { match ->
                    val hexValue = match.groupValues[1]
                    hexValue.toInt(16).toChar().toString()
                }
        } else {
            rawResult
        }

        // Parse the JSON response from JavaScript
        val chaptersJson = org.json.JSONArray(jsonString)
        val chapters = mutableListOf<MangaChapter>()

        for (i in 0 until chaptersJson.length()) {
            val chapterObj = chaptersJson.getJSONObject(i)
            val url = chapterObj.getString("url")
            val title = chapterObj.getString("title")
            val number = chapterObj.optString("number", "-1").toFloatOrNull() ?: -1f
            val dateText = chapterObj.optString("dateText", null)

            chapters.add(
                MangaChapter(
                    id = generateUid(url),
                    title = title,
                    url = url,
                    number = number,
                    volume = 0,
                    scanlator = null,
                    uploadDate = parseDate(dateText),
                    branch = null,
                    source = source,
                )
            )
        }

        return chapters.reversed()
    }
    }
