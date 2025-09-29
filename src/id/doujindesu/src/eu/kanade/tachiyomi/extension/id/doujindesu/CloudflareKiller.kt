package eu.kanade.tachiyomi.extension.id.doujindesu.cloudflare

import android.util.Log
import android.webkit.CookieManager
import androidx.annotation.AnyThread
import com.lagradost.cloudstream3.mvvm.debugWarning
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.nicehttp.Requests.Companion.await
import com.lagradost.nicehttp.cookies
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.net.URI

@AnyThread
class CloudflareKiller : Interceptor {
    companion object {
        private const val TAG = "CloudflareKiller"
        private val ERROR_CODES = listOf(403, 503)
        private val CLOUDFLARE_SERVERS = listOf("cloudflare-nginx", "cloudflare")

        fun parseCookieMap(cookie: String): Map<String, String> {
            return cookie.split(";").associate {
                val split = it.split("=")
                (split.getOrNull(0)?.trim() ?: "") to (split.getOrNull(1)?.trim() ?: "")
            }.filter { it.key.isNotBlank() && it.value.isNotBlank() }
        }
    }

    init {
        // Clear cookies between sessions to get new cookies
        safe {
            CookieManager.getInstance().removeAllCookies(null)
        }
    }

    private val savedCookies: MutableMap<String, Map<String, String>> = mutableMapOf()

    fun getCookieHeaders(url: String): Headers {
        val userAgentHeaders = WebViewResolver.webViewUserAgent?.let {
            mapOf("user-agent" to it)
        } ?: emptyMap()

        return getHeaders(userAgentHeaders, savedCookies[URI(url).host] ?: emptyMap())
    }

    override fun intercept(chain: Interceptor.Chain): Response = runBlocking {
        val request = chain.request()
        val cookies = savedCookies[request.url.host]

        if (cookies == null) {
            val response = chain.proceed(request)
            if (!(response.header("Server") in CLOUDFLARE_SERVERS && response.code in ERROR_CODES)) {
                return@runBlocking response
            }
            response.close()
            bypassCloudflare(request)?.let {
                Log.d(TAG, "Succeeded bypassing cloudflare: ${request.url}")
                return@runBlocking it
            }
        } else {
            return@runBlocking proceed(request, cookies)
        }
        debugWarning({ true }) { "Failed cloudflare at: ${request.url}" }
        return@runBlocking chain.proceed(request)
    }

    private fun getWebViewCookie(url: String): String? = safe {
        CookieManager.getInstance()?.getCookie(url)
    }

    private fun trySolveWithSavedCookies(request: Request): Boolean {
        return getWebViewCookie(request.url.toString())?.let { cookie ->
            cookie.contains("cf_clearance").also { solved ->
                if (solved) savedCookies[request.url.host] = parseCookieMap(cookie)
            }
        } ?: false
    }

    private suspend fun proceed(request: Request, cookies: Map<String, String>): Response {
        val userAgentMap = WebViewResolver.webViewUserAgent?.let {
            mapOf("user-agent" to it)
        } ?: emptyMap()

        val headers = getHeaders(request.headers.toMap() + userAgentMap, cookies + request.cookies)
        return com.lagradost.nicehttp.app.baseClient.newCall(
            request.newBuilder()
                .headers(headers)
                .build()
        ).await()
    }

    private suspend fun bypassCloudflare(request: Request): Response? {
        val url = request.url.toString()

        if (!trySolveWithSavedCookies(request)) {
            Log.d(TAG, "Loading webview to solve cloudflare for $url")
            WebViewResolver(
                Regex(".^"), // never exit based on url
                userAgent = null,
                useOkhttp = false,
                additionalUrls = listOf(Regex("."))
            ).resolveUsingWebView(url) {
                trySolveWithSavedCookies(request)
            }
        }

        val cookies = savedCookies[request.url.host] ?: return null
        return proceed(request, cookies)
    }
}
