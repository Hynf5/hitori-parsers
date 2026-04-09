package org.koitharu.kotatsu.parsers.site.en

import java.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.net.URLEncoder
import java.util.*

@MangaSourceParser("COMIX", "Comix", "en", ContentType.MANGA)
internal class Comix(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.COMIX, 28) {

    override val configKeyDomain = ConfigKey.Domain("comix.to")

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = false,
        )

    override val availableSortOrders: Set<SortOrder> = LinkedHashSet(
        listOf(
            SortOrder.RELEVANCE,
            SortOrder.UPDATED,
            SortOrder.POPULARITY,
            SortOrder.NEWEST,
            SortOrder.ALPHABETICAL
        )
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
    )

    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        return setOf(
            // Genres
            MangaTag(key = "6", title = "Action", source = source),
            MangaTag(key = "7", title = "Adventure", source = source),
            MangaTag(key = "8", title = "Boys Love", source = source),
            MangaTag(key = "9", title = "Comedy", source = source),
            MangaTag(key = "10", title = "Crime", source = source),
            MangaTag(key = "11", title = "Drama", source = source),
            MangaTag(key = "12", title = "Fantasy", source = source),
            MangaTag(key = "13", title = "Girls Love", source = source),
            MangaTag(key = "14", title = "Historical", source = source),
            MangaTag(key = "15", title = "Horror", source = source),
            MangaTag(key = "16", title = "Isekai", source = source),
            MangaTag(key = "17", title = "Magical Girls", source = source),
            MangaTag(key = "87267", title = "Mature", source = source),
            MangaTag(key = "18", title = "Mecha", source = source),
            MangaTag(key = "19", title = "Medical", source = source),
            MangaTag(key = "20", title = "Mystery", source = source),
            MangaTag(key = "21", title = "Philosophical", source = source),
            MangaTag(key = "22", title = "Psychological", source = source),
            MangaTag(key = "23", title = "Romance", source = source),
            MangaTag(key = "24", title = "Sci-Fi", source = source),
            MangaTag(key = "25", title = "Slice of Life", source = source),
            MangaTag(key = "26", title = "Sports", source = source),
            MangaTag(key = "27", title = "Superhero", source = source),
            MangaTag(key = "28", title = "Thriller", source = source),
            MangaTag(key = "29", title = "Tragedy", source = source),
            MangaTag(key = "30", title = "Wuxia", source = source),
            // Themes
            MangaTag(key = "31", title = "Aliens", source = source),
            MangaTag(key = "32", title = "Animals", source = source),
            MangaTag(key = "33", title = "Cooking", source = source),
            MangaTag(key = "34", title = "Crossdressing", source = source),
            MangaTag(key = "35", title = "Delinquents", source = source),
            MangaTag(key = "36", title = "Demons", source = source),
            MangaTag(key = "37", title = "Genderswap", source = source),
            MangaTag(key = "38", title = "Ghosts", source = source),
            MangaTag(key = "39", title = "Gyaru", source = source),
            MangaTag(key = "40", title = "Harem", source = source),
            MangaTag(key = "41", title = "Incest", source = source),
            MangaTag(key = "42", title = "Loli", source = source),
            MangaTag(key = "43", title = "Mafia", source = source),
            MangaTag(key = "44", title = "Magic", source = source),
            MangaTag(key = "45", title = "Martial Arts", source = source),
            MangaTag(key = "46", title = "Military", source = source),
            MangaTag(key = "47", title = "Monster Girls", source = source),
            MangaTag(key = "48", title = "Monsters", source = source),
            MangaTag(key = "49", title = "Music", source = source),
            MangaTag(key = "50", title = "Ninja", source = source),
            MangaTag(key = "51", title = "Office Workers", source = source),
            MangaTag(key = "52", title = "Police", source = source),
            MangaTag(key = "53", title = "Post-Apocalyptic", source = source),
            MangaTag(key = "54", title = "Reincarnation", source = source),
            MangaTag(key = "55", title = "Reverse Harem", source = source),
            MangaTag(key = "56", title = "Samurai", source = source),
            MangaTag(key = "57", title = "School Life", source = source),
            MangaTag(key = "58", title = "Shota", source = source),
            MangaTag(key = "59", title = "Supernatural", source = source),
            MangaTag(key = "60", title = "Survival", source = source),
            MangaTag(key = "61", title = "Time Travel", source = source),
            MangaTag(key = "62", title = "Traditional Games", source = source),
            MangaTag(key = "63", title = "Vampires", source = source),
            MangaTag(key = "64", title = "Video Games", source = source),
            MangaTag(key = "65", title = "Villainess", source = source),
            MangaTag(key = "66", title = "Virtual Reality", source = source),
            MangaTag(key = "67", title = "Zombies", source = source),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://comix.to/api/v2/manga?")
            var firstParam = true
            fun addParam(param: String) {
                if (firstParam) {
                    append(param)
                    firstParam = false
                } else {
                    append("&").append(param)
                }
            }

            // Search keyword if provided
            if (!filter.query.isNullOrEmpty()) {
                addParam("keyword=${filter.query.urlEncoded()}")
            }

            // Use the provided sort order directly
            when (order) {
                SortOrder.RELEVANCE -> addParam("order[relevance]=desc")
                SortOrder.UPDATED -> addParam("order[chapter_updated_at]=desc")
                SortOrder.POPULARITY -> addParam("order[views_30d]=desc")
                SortOrder.NEWEST -> addParam("order[created_at]=desc")
                SortOrder.ALPHABETICAL -> addParam("order[title]=asc")
                else -> addParam("order[chapter_updated_at]=desc")
            }

            // Handle genre filtering
            if (filter.tags.isNotEmpty()) {
                for (tag in filter.tags) {
                    addParam("genres[]=${tag.key}")
                }
            }

            // Default exclude adult content
            addParam("genres[]=-87264") // Adult
            addParam("genres[]=-87266") // Hentai
            addParam("genres[]=-87268") // Smut
            addParam("genres[]=-87265") // Ecchi
            addParam("limit=$pageSize")
            addParam("page=$page")
        }

        val response = webClient.httpGet(url).parseJson()
        val result = response.getJSONObject("result")
        val items = result.getJSONArray("items")

        return (0 until items.length()).map { i ->
            val item = items.getJSONObject(i)
            parseMangaFromJson(item)
        }
    }

    private fun parseMangaFromJson(json: JSONObject): Manga {
        val hashId = json.getString("hash_id")
        val title = json.getString("title")
        val description = json.optString("synopsis", "").nullIfEmpty()
        val poster = json.getJSONObject("poster")
        val coverUrl = poster.optString("large", "").nullIfEmpty()
        val status = json.optString("status", "")
        val year = json.optInt("year", 0)
        val rating = json.optDouble("rated_avg", 0.0)

        val state = when (status) {
            "finished" -> MangaState.FINISHED
            "releasing" -> MangaState.ONGOING
            "on_hiatus" -> MangaState.PAUSED
            else -> null
        }

        return Manga(
            id = generateUid(hashId),
            url = "/title/$hashId",
            publicUrl = "https://comix.to/title/$hashId",
            coverUrl = coverUrl,
            title = title,
            altTitles = emptySet(),
            description = description,
            rating = if (rating > 0) (rating / 10.0f).toFloat() else RATING_UNKNOWN,
            tags = emptySet(),
            authors = emptySet(),
            state = state,
            source = source,
            contentRating = ContentRating.SAFE,
        )
    }

    override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
        val hashId = manga.url.substringAfter("/title/")
        val chaptersDeferred = async { getChapters(manga) }

        // Get detailed manga info
        val detailUrl = "https://comix.to/api/v2/manga/$hashId"
        val response = webClient.httpGet(detailUrl).parseJson()

        if (response.has("result")) {
            val result = response.getJSONObject("result")
            val updatedManga = parseMangaFromJson(result)

            return@coroutineScope updatedManga.copy(
                chapters = chaptersDeferred.await(),
            )
        }

        return@coroutineScope manga.copy(
            chapters = chaptersDeferred.await(),
        )
    }

    override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterId = chapter.url.substringAfterLast("/").substringBefore("-")
        val chapterUrl = "https://comix.to${chapter.url}"

        // Get the chapter page HTML to extract images from the script
        val response = webClient.httpGet(chapterUrl).parseHtml()

        // Look for the images array in the JavaScript (with escaped quotes)
        val scripts = response.select("script")
        var images: JSONArray? = null

        for (script in scripts) {
            val scriptContent = script.html()

            // Look for the images array with escaped quotes in JSON
            if (scriptContent.contains("\\\"images\\\":[")) {
                try {
                    // Find the start of the images array (with escaped quotes)
                    val imagesStart = scriptContent.indexOf("\\\"images\\\":[")
                    val colonPos = scriptContent.indexOf(":", imagesStart)
                    val arrayStart = scriptContent.indexOf("[", colonPos)

                    // Find the matching closing bracket for the array
                    var bracketCount = 1 // Start with 1 since we're at the opening bracket
                    var arrayEnd = arrayStart + 1 // Start after the opening bracket
                    var inString = false
                    var escapeNext = false

                    for (i in (arrayStart + 1) until scriptContent.length) {
                        val char = scriptContent[i]

                        if (escapeNext) {
                            escapeNext = false
                            continue
                        }

                        when (char) {
                            '\\' -> escapeNext = true
                            '"' -> inString = !inString
                            '[' -> if (!inString) bracketCount++
                            ']' -> if (!inString) {
                                bracketCount--
                                if (bracketCount == 0) {
                                    arrayEnd = i + 1
                                    break
                                }
                            }
                        }
                    }

                    val imagesJsonString = scriptContent.substring(arrayStart, arrayEnd)
                    // Parse the JSON array, handling escaped quotes
                    images = JSONArray(imagesJsonString.replace("\\\"", "\""))
                    break
                } catch (e: Exception) {
                    // Continue to next script if parsing fails
                    continue
                }
            }
        }

        if (images == null) {
            throw ParseException("Unable to find chapter images", chapterUrl)
        }

        return (0 until images.length()).map { i ->
            val imageItem = images.get(i)
            val imageUrl = when (imageItem) {
                is String -> imageItem
                is JSONObject -> imageItem.getString("url")
                else -> throw ParseException("Unexpected image format", chapterUrl)
            }
            MangaPage(
                id = generateUid("$chapterId-$i"),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }

    private suspend fun getChapters(manga: Manga): List<MangaChapter> {
        val hashId = manga.url.substringAfter("/title/")
        val allChapters = mutableListOf<JSONObject>()
        var page = 1

        // Fetch all chapters with pagination
        while (true) {
            val chaptersPath = "/manga/$hashId/chapters"
            val time = 1L
            val hashToken = ComixHash.generateHash(chaptersPath, 0, time)
            val chaptersUrl = "https://comix.to/api/v2/manga/$hashId/chapters?order[number]=desc&limit=100&page=$page&time=$time&_=$hashToken"
            val response = webClient.httpGet(chaptersUrl).parseJson()
            val result = response.getJSONObject("result")
            val items = result.getJSONArray("items")

            if (items.length() == 0) break

            for (i in 0 until items.length()) {
                allChapters.add(items.getJSONObject(i))
            }

            // Check pagination info to see if we have more pages
            val pagination = result.optJSONObject("pagination")
            if (pagination != null) {
                val currentPage = pagination.getInt("current_page")
                val lastPage = pagination.getInt("last_page")
                if (currentPage >= lastPage) break
            }

            page++
        }

        // Group chapters by scanlation team
        val chaptersByTeam = mutableMapOf<String, MutableList<JSONObject>>()
        for (chapter in allChapters) {
            val scanlationGroup = chapter.optJSONObject("scanlation_group")
            val teamName = scanlationGroup?.optString("name", null) ?: "Unknown"
            chaptersByTeam.getOrPut(teamName) { mutableListOf() }.add(chapter)
        }

        // Get all unique chapter numbers
        val allChapterNumbers = allChapters.map { it.getDouble("number").toFloat() }.toSet()

        // Build chapters with branches - each team gets complete chapter list with gaps filled
        val chaptersBuilder = ChaptersListBuilder(allChapters.size * chaptersByTeam.size)

        for ((teamName, teamChapters) in chaptersByTeam) {
            // Map of chapter numbers this team has
            val teamChapterMap = teamChapters.associateBy { it.getDouble("number").toFloat() }

            // For each chapter number, use team's version if available, otherwise find best alternative
            for (chapterNumber in allChapterNumbers) {
                val chapterData = teamChapterMap[chapterNumber]
                    ?: allChapters.find { it.getDouble("number").toFloat() == chapterNumber }
                    ?: continue

                val chapterId = chapterData.getLong("chapter_id")
                val number = chapterData.getDouble("number").toFloat()
                val name = chapterData.optString("name", "").nullIfEmpty()
                val createdAt = chapterData.getLong("created_at")
                val scanlationGroup = chapterData.optJSONObject("scanlation_group")
                val actualTeamName = scanlationGroup?.optString("name", null) ?: "Unknown"

                val title = if (name != null) {
                    "Chapter $number: $name"
                } else {
                    "Chapter $number"
                }

                val chapter = MangaChapter(
                    id = generateUid("$teamName-$chapterId"),
                    title = title,
                    number = number,
                    volume = 0,
                    url = "/title/$hashId/$chapterId-chapter-${number.toInt()}",
                    uploadDate = createdAt * 1000L,
                    source = source,
                    scanlator = actualTeamName,
                    branch = teamName,
                )

                chaptersBuilder.add(chapter)
            }
        }

        return chaptersBuilder.toList().reversed()
    }
}

private object ComixHash {
    private val KEYS = arrayOf(
        "13YDu67uDgFczo3DnuTIURqas4lfMEPADY6Jaeqky+w=",
        "yEy7wBfBc+gsYPiQL/4Dfd0pIBZFzMwrtlRQGwMXy3Q=",
        "yrP+EVA1Dw==",
        "vZ23RT7pbSlxwiygkHd1dhToIku8SNHPC6V36L4cnwM=",
        "QX0sLahOByWLcWGnv6l98vQudWqdRI3DOXBdit9bxCE=",
        "WJwgqCmf",
        "BkWI8feqSlDZKMq6awfzWlUypl88nz65KVRmpH0RWIc=",
        "v7EIpiQQjd2BGuJzMbBA0qPWDSS+wTJRQ7uGzZ6rJKs=",
        "1SUReYlCRA==",
        "RougjiFHkSKs20DZ6BWXiWwQUGZXtseZIyQWKz5eG34=",
        "LL97cwoDoG5cw8QmhI+KSWzfW+8VehIh+inTxnVJ2ps=",
        "52iDqjzlqe8=",
        "U9LRYFL2zXU4TtALIYDj+lCATRk/EJtH7/y7qYYNlh8=",
        "e/GtffFDTvnw7LBRixAD+iGixjqTq9kIZ1m0Hj+s6fY=",
        "xb2XwHNB",
    )

    private fun getKeyBytes(index: Int): IntArray {
        val b64 = KEYS.getOrNull(index) ?: return IntArray(0)
        return try {
            Base64.getDecoder().decode(b64)
                .map { (it.toInt() and 0xFF) }
                .toIntArray()
        } catch (_: Exception) {
            IntArray(0)
        }
    }

    private fun rc4(key: IntArray, data: IntArray): IntArray {
        if (key.isEmpty()) return data
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + key[i % key.size]) % 256
            val temp = s[i]
            s[i] = s[j]
            s[j] = temp
        }
        var i = 0
        j = 0
        val out = IntArray(data.size)
        for (k in data.indices) {
            i = (i + 1) % 256
            j = (j + s[i]) % 256
            val temp = s[i]
            s[i] = s[j]
            s[j] = temp
            out[k] = data[k] xor s[(s[i] + s[j]) % 256]
        }
        return out
    }

    private fun mutS(e: Int): Int = (e + 143) % 256
    private fun mutL(e: Int): Int = ((e ushr 1) or (e shl 7)) and 255
    private fun mutC(e: Int): Int = (e + 115) % 256
    private fun mutM(e: Int): Int = e xor 177
    private fun mutF(e: Int): Int = (e - 188 + 256) % 256
    private fun mutG(e: Int): Int = ((e shl 2) or (e ushr 6)) and 255
    private fun mutH(e: Int): Int = (e - 42 + 256) % 256
    private fun mutDollar(e: Int): Int = ((e shl 4) or (e ushr 4)) and 255
    private fun mutB(e: Int): Int = (e - 12 + 256) % 256
    private fun mutUnderscore(e: Int): Int = (e - 20 + 256) % 256
    private fun mutY(e: Int): Int = ((e ushr 1) or (e shl 7)) and 255
    private fun mutK(e: Int): Int = (e - 241 + 256) % 256

    private fun getMutKey(mk: IntArray, idx: Int): Int =
        if (mk.isNotEmpty() && (idx % 32) < mk.size) mk[idx % 32] else 0

    private fun round1(data: IntArray): IntArray {
        val enc = rc4(getKeyBytes(0), data)
        val mutKey = getKeyBytes(1)
        val prefKey = getKeyBytes(2)
        val out = mutableListOf<Int>()
        for (i in enc.indices) {
            if (i < 7 && i < prefKey.size) out.add(prefKey[i])
            var v = enc[i] xor getMutKey(mutKey, i)
            v = when (i % 10) {
                0, 9 -> mutC(v)
                1 -> mutB(v)
                2 -> mutY(v)
                3 -> mutDollar(v)
                4, 6 -> mutH(v)
                5 -> mutS(v)
                7 -> mutK(v)
                8 -> mutL(v)
                else -> v
            }
            out.add(v and 255)
        }
        return out.toIntArray()
    }

    private fun round2(data: IntArray): IntArray {
        val enc = rc4(getKeyBytes(3), data)
        val mutKey = getKeyBytes(4)
        val prefKey = getKeyBytes(5)
        val out = mutableListOf<Int>()
        for (i in enc.indices) {
            if (i < 6 && i < prefKey.size) out.add(prefKey[i])
            var v = enc[i] xor getMutKey(mutKey, i)
            v = when (i % 10) {
                0, 8 -> mutC(v)
                1 -> mutB(v)
                2, 6 -> mutDollar(v)
                3 -> mutH(v)
                4, 9 -> mutS(v)
                5 -> mutK(v)
                7 -> mutUnderscore(v)
                else -> v
            }
            out.add(v and 255)
        }
        return out.toIntArray()
    }

    private fun round3(data: IntArray): IntArray {
        val enc = rc4(getKeyBytes(6), data)
        val mutKey = getKeyBytes(7)
        val prefKey = getKeyBytes(8)
        val out = mutableListOf<Int>()
        for (i in enc.indices) {
            if (i < 7 && i < prefKey.size) out.add(prefKey[i])
            var v = enc[i] xor getMutKey(mutKey, i)
            v = when (i % 10) {
                0 -> mutC(v)
                1 -> mutF(v)
                2, 8 -> mutS(v)
                3 -> mutG(v)
                4 -> mutY(v)
                5 -> mutM(v)
                6 -> mutDollar(v)
                7 -> mutK(v)
                9 -> mutB(v)
                else -> v
            }
            out.add(v and 255)
        }
        return out.toIntArray()
    }

    private fun round4(data: IntArray): IntArray {
        val enc = rc4(getKeyBytes(9), data)
        val mutKey = getKeyBytes(10)
        val prefKey = getKeyBytes(11)
        val out = mutableListOf<Int>()
        for (i in enc.indices) {
            if (i < 8 && i < prefKey.size) out.add(prefKey[i])
            var v = enc[i] xor getMutKey(mutKey, i)
            v = when (i % 10) {
                0 -> mutB(v)
                1, 9 -> mutM(v)
                2, 7 -> mutL(v)
                3, 5 -> mutS(v)
                4, 6 -> mutUnderscore(v)
                8 -> mutY(v)
                else -> v
            }
            out.add(v and 255)
        }
        return out.toIntArray()
    }

    private fun round5(data: IntArray): IntArray {
        val enc = rc4(getKeyBytes(12), data)
        val mutKey = getKeyBytes(13)
        val prefKey = getKeyBytes(14)
        val out = mutableListOf<Int>()
        for (i in enc.indices) {
            if (i < 6 && i < prefKey.size) out.add(prefKey[i])
            var v = enc[i] xor getMutKey(mutKey, i)
            v = when (i % 10) {
                0 -> mutUnderscore(v)
                1, 7 -> mutS(v)
                2 -> mutC(v)
                3, 5 -> mutM(v)
                4 -> mutB(v)
                6 -> mutF(v)
                8 -> mutDollar(v)
                9 -> mutG(v)
                else -> v
            }
            out.add(v and 255)
        }
        return out.toIntArray()
    }

    fun generateHash(path: String, bodySize: Int = 0, time: Long = 1): String {
        val baseString = "$path:$bodySize:$time"
        val encoded = URLEncoder.encode(baseString, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")

        val initialBytes = encoded.toByteArray(Charsets.US_ASCII)
            .map { it.toInt() and 0xFF }
            .toIntArray()

        val r1 = round1(initialBytes)
        val r2 = round2(r1)
        val r3 = round3(r2)
        val r4 = round4(r3)
        val r5 = round5(r4)

        val finalBytes = ByteArray(r5.size) { r5[it].toByte() }

        return Base64.getUrlEncoder().withoutPadding().encodeToString(finalBytes)
    }
}
