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
        // Use WebView to load the page and extract chapter data from the DOM
        val pageScript = """
            new Promise((resolveMain, rejectMain) => {
                (async function() {
                    try {
                        console.log('[Kiryuu] Starting chapter loading...');

                        // Helper function to wait for element
                        function waitForElement(selector, timeout = 10000) {
                            return new Promise((resolve, reject) => {
                                if (document.querySelector(selector)) {
                                    return resolve(document.querySelector(selector));
                                }

                                const observer = new MutationObserver(() => {
                                    if (document.querySelector(selector)) {
                                        observer.disconnect();
                                        resolve(document.querySelector(selector));
                                    }
                                });

                                observer.observe(document.body, {
                                    childList: true,
                                    subtree: true
                                });

                                setTimeout(() => {
                                    observer.disconnect();
                                    reject(new Error('Timeout waiting for ' + selector));
                                }, timeout);
                            });
                        }

                        // Wait for and click the chapters tab
                        try {
                            const tabButton = await waitForElement('button[data-key="chapters"]');
                            console.log('[Kiryuu] Found chapters tab button, clicking...');
                            tabButton.click();

                            // Wait for chapter list to load
                            await waitForElement('div#chapter-list > div[data-chapter-number]');
                            console.log('[Kiryuu] Chapter list loaded');

                            // Small delay to ensure all chapters are rendered
                            await new Promise(resolve => setTimeout(resolve, 500));

                        } catch (error) {
                            console.log('[Kiryuu] Error:', error);
                            // Try direct htmx trigger as fallback
                            const chapterList = document.querySelector('#chapter-list');
                            if (chapterList && typeof htmx !== 'undefined') {
                                htmx.trigger(chapterList, 'getChapterList');
                                await waitForElement('div#chapter-list > div[data-chapter-number]');
                            }
                        }

                        // Extract chapter data from DOM
                        const chapters = [];
                        const chapterElements = document.querySelectorAll('div#chapter-list > div[data-chapter-number]');

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
                        resolveMain(JSON.stringify(chapters));
                    } catch (error) {
                        console.log('[Kiryuu] Error in main function:', error);
                        rejectMain(error);
                    }
                })();
            });
        """.trimIndent()

        val html = context.evaluateJs(mangaAbsoluteUrl, pageScript, timeout = 30000L)
            ?.takeIf { it.isNotBlank() }
            ?: throw Exception("Failed to extract chapter data from WebView")

        // Parse the JSON response from JavaScript
        val chaptersJson = org.json.JSONArray(html)
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
