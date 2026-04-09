package org.koitharu.kotatsu.parsers.site.madara.en

import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import java.io.IOException
import java.security.MessageDigest

@MangaSourceParser("YAKSHACOMICS", "YakshaComics", "en")
internal class YakshaComics(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.YAKSHACOMICS, "yakshacomics.com") {

	override val selectDesc =
		"div.description-summary div.summary__content h3 + p, " +
			"div.description-summary div.summary__content:not(:has(h3)), " +
			"div.summary_content div.post-content_item > h5 + div, " +
			"div.summary_content div.manga-excerpt"

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val url = request.url.toString()
		if (!url.contains(domain) || url.contains("/hcdn-cgi/")) {
			return chain.proceed(request)
		}

		val response = chain.proceed(request)
		if (response.code != 403) {
			return response
		}
		response.close()

		Thread.sleep(3_000L)
		val token = fetchToken(chain).sha256Hex()
		val validateRequest = Request.Builder()
			.url("https://$domain/hcdn-cgi/jschallenge-validate")
			.headers(getRequestHeaders().newBuilder().set("Referer", "https://$domain/").build())
			.post(
				FormBody.Builder()
					.add("challenge", token)
					.build(),
			)
			.build()
		chain.proceed(validateRequest).use {
			if (!it.isSuccessful) {
				throw IOException("Failed to bypass js challenge")
			}
		}

		return chain.proceed(request)
	}

	private tailrec fun fetchToken(chain: Interceptor.Chain, attempt: Int = 0): String {
		if (attempt >= MAX_ATTEMPT) {
			throw IOException("Failed to fetch challenge token")
		}
		val challengeRequest = Request.Builder()
			.url("https://$domain/hcdn-cgi/jschallenge")
			.headers(getRequestHeaders().newBuilder().set("Referer", "https://$domain/").build())
			.get()
			.build()
		val response = chain.proceed(challengeRequest)
		response.use {
			val token = TOKEN_REGEX.find(it.body.string())?.groupValues?.getOrNull(1)
			return if (!token.isNullOrBlank() && token != "nil") {
				token
			} else {
				fetchToken(chain, attempt + 1)
			}
		}
	}

	private fun String.sha256Hex(): String {
		return MessageDigest.getInstance("SHA-256")
			.digest(toByteArray(Charsets.UTF_8))
			.joinToString(separator = "") { byte -> "%02x".format(byte) }
	}

	private companion object {
		private const val MAX_ATTEMPT = 5
		private val TOKEN_REGEX = Regex("""cjs[^']+'([^']+)""")
	}
}
