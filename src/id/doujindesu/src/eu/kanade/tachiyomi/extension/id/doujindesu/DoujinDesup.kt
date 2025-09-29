package eu.kanade.tachiyomi.extension.id.doujindesu

package com.lagradost.cloudstream3.network

import android.util.Log
import android.webkit.CookieManager
import androidx.annotation.AnyThread
import com.lagradost.cloudstream3.app
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
        const val TAG = "CloudflareKiller"
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
        // Needs to clear cookies between sessions to generate new cookies.
        safe {
            // This can throw an exception on unsupported devices :(
            CookieManager.getInstance().removeAllCookies(null)
        }
    }

    val savedCookies: MutableMap<String, Map<String, String>> = mutableMapOf()

    /**
     * Gets the headers with cookies, webview user agent included!
     * */
    fun getCookieHeaders(url: String): Headers {
        val userAgentHeaders = WebViewResolver.webViewUserAgent?.let {
            mapOf("user-agent" to it)
        } ?: emptyMap()

        return getHeaders(userAgentHeaders, savedCookies[URI(url).host] ?: emptyMap())
    }

    override fun intercept(chain: Interceptor.Chain): Response = runBlocking {
        val request = chain.request()

        when (val cookies = savedCookies[request.url.host]) {
            null -> {
                val response = chain.proceed(request)
                if(!(response.header("Server") in CLOUDFLARE_SERVERS && response.code in ERROR_CODES)) {
                    return@runBlocking response
                } else {
                    response.close()
                    bypassCloudflare(request)?.let {
                        Log.d(TAG, "Succeeded bypassing cloudflare: ${request.url}")
                        return@runBlocking it
                    }
                }
            }
            else -> {
                return@runBlocking proceed(request, cookies)
            }
        }

        debugWarning({ true }) { "Failed cloudflare at: ${request.url}" }
        return@runBlocking chain.proceed(request)
    }

    private fun getWebViewCookie(url: String): String? {
        return safe {
            CookieManager.getInstance()?.getCookie(url)
        }
    }

    /**
     * Returns true if the cf cookies were successfully fetched from the CookieManager
     * Also saves the cookies.
     * */
    private fun trySolveWithSavedCookies(request: Request): Boolean {
        // Not sure if this takes expiration into account
        return getWebViewCookie(request.url.toString())?.let { cookie ->
            cookie.contains("cf_clearance").also { solved ->
                if (solved) savedCookies[request.url.host] = parseCookieMap(cookie)
            }
        } ?: false
    }

    private suspend fun proceed(request: Request, cookies: Map<String, String>): Response {
        val userAgentMap = WebViewResolver.getWebViewUserAgent()?.let {
            mapOf("user-agent" to it)
        } ?: emptyMap()

        val headers =
            getHeaders(request.headers.toMap() + userAgentMap, cookies + request.cookies)
        return app.baseClient.newCall(
            request.newBuilder()
                .headers(headers)
                .build()
        ).await()
    }

    private suspend fun bypassCloudflare(request: Request): Response? {
        val url = request.url.toString()

        // If no cookies then try to get them
        // Remove this if statement if cookies expire
        if (!trySolveWithSavedCookies(request)) {
            Log.d(TAG, "Loading webview to solve cloudflare for ${request.url}")
            WebViewResolver(
                // Never exit based on url
                Regex(".^"),
                // Cloudflare needs default user agent
                userAgent = null,
                // Cannot use okhttp (i think intercepting cookies fails which causes the issues)
                useOkhttp = false,
                // Match every url for the requestCallBack
                additionalUrls = listOf(Regex("."))
            ).resolveUsingWebView(
                url
            ) {
                trySolveWithSavedCookies(request)
            }
        }

        val cookies = savedCookies[request.url.host] ?: return null
        return proceed(request, cookies)
    }
}


import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.lib.randomua.addRandomUAPreferenceToScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class DoujinDesu : ParsedHttpSource(), ConfigurableSource {
    // Information : DoujinDesu use EastManga WordPress Theme
    override val name = "Doujindesu"
    override val baseUrl by lazy { preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!! }
    override val lang = "id"
    override val supportsLatest = true
    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(CloudflareBypassInterceptor(App.instance))
        .build()

    // Private stuff

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val DATE_FORMAT by lazy {
        SimpleDateFormat("EEEE, dd MMMM yyyy", Locale("id"))
    }

    private fun parseStatus(status: String) = when {
        status.lowercase(Locale.US).contains("publishing") -> SManga.ONGOING
        status.lowercase(Locale.US).contains("finished") -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    private class Category(title: String, val key: String) : Filter.TriState(title) {
        override fun toString(): String {
            return name
        }
    }

    private class Genre(name: String, val id: String = name) : Filter.CheckBox(name) {
        override fun toString(): String {
            return id
        }
    }

    private class Order(title: String, val key: String) : Filter.TriState(title) {
        override fun toString(): String {
            return name
        }
    }

    private class Status(title: String, val key: String) : Filter.TriState(title) {
        override fun toString(): String {
            return name
        }
    }

    private val orderBy = arrayOf(
        Order("Semua", ""),
        Order("A-Z", "title"),
        Order("Update Terbaru", "update"),
        Order("Baru Ditambahkan", "latest"),
        Order("Populer", "popular"),
    )

    private val statusList = arrayOf(
        Status("Semua", ""),
        Status("Berlanjut", "Publishing"),
        Status("Selesai", "Finished"),
    )

    private val categoryNames = arrayOf(
        Category("Semua", ""),
        Category("Doujinshi", "Doujinshi"),
        Category("Manga", "Manga"),
        Category("Manhwa", "Manhwa"),
    )

    private fun genreList() = listOf(
        Genre("Age Progression"),
        Genre("Age Regression"),
        Genre("Ahegao"),
        Genre("All The Way Through"),
        Genre("Amputee"),
        Genre("Anal"),
        Genre("Anorexia"),
        Genre("Apron"),
        Genre("Artist CG"),
        Genre("Aunt"),
        Genre("Bald"),
        Genre("Bestiality"),
        Genre("Big Ass"),
        Genre("Big Breast"),
        Genre("Big Penis"),
        Genre("Bike Shorts"),
        Genre("Bikini"),
        Genre("Birth"),
        Genre("Bisexual"),
        Genre("Blackmail"),
        Genre("Blindfold"),
        Genre("Bloomers"),
        Genre("Blowjob"),
        Genre("Body Swap"),
        Genre("Bodysuit"),
        Genre("Bondage"),
        Genre("Bowjob"),
        Genre("Business Suit"),
        Genre("Cheating"),
        Genre("Collar"),
        Genre("Collor"),
        Genre("Condom"),
        Genre("Cousin"),
        Genre("Crossdressing"),
        Genre("Cunnilingus"),
        Genre("Dark Skin"),
        Genre("Daughter"),
        Genre("Defloration"),
        Genre("Demon"),
        Genre("Demon Girl"),
        Genre("Dick Growth"),
        Genre("DILF"),
        Genre("Double Penetration"),
        Genre("Drugs"),
        Genre("Drunk"),
        Genre("Elf"),
        Genre("Emotionless Sex"),
        Genre("Exhibitionism"),
        Genre("Eyepatch"),
        Genre("Females Only"),
        Genre("Femdom"),
        Genre("Filming"),
        Genre("Fingering"),
        Genre("Footjob"),
        Genre("Full Color"),
        Genre("Furry"),
        Genre("Futanari"),
        Genre("Garter Belt"),
        Genre("Gender Bender"),
        Genre("Ghost"),
        Genre("Glasses"),
        Genre("Gore"),
        Genre("Group"),
        Genre("Guro"),
        Genre("Gyaru"),
        Genre("Hairy"),
        Genre("Handjob"),
        Genre("Harem"),
        Genre("Horns"),
        Genre("Huge Breast"),
        Genre("Huge Penis"),
        Genre("Humiliation"),
        Genre("Impregnation"),
        Genre("Incest"),
        Genre("Inflation"),
        Genre("Insect"),
        Genre("Inseki"),
        Genre("Inverted Nipples"),
        Genre("Invisible"),
        Genre("Kemomimi"),
        Genre("Kimono"),
        Genre("Lactation"),
        Genre("Leotard"),
        Genre("Lingerie"),
        Genre("Loli"),
        Genre("Lolipai"),
        Genre("Maid"),
        Genre("Males"),
        Genre("Males Only"),
        Genre("Masturbation"),
        Genre("Miko"),
        Genre("MILF"),
        Genre("Mind Break"),
        Genre("Mind Control"),
        Genre("Minigirl"),
        Genre("Miniguy"),
        Genre("Monster"),
        Genre("Monster Girl"),
        Genre("Mother"),
        Genre("Multi-work Series"),
        Genre("Muscle"),
        Genre("Nakadashi"),
        Genre("Necrophilia"),
        Genre("Netorare"),
        Genre("Niece"),
        Genre("Nipple Fuck"),
        Genre("Nurse"),
        Genre("Old Man"),
        Genre("Only"),
        Genre("Oyakodon"),
        Genre("Paizuri"),
        Genre("Pantyhose"),
        Genre("Possession"),
        Genre("Pregnant"),
        Genre("Prostitution"),
        Genre("Rape"),
        Genre("Rimjob"),
        Genre("Scat"),
        Genre("School Uniform"),
        Genre("Sex Toys"),
        Genre("Shemale"),
        Genre("Shota"),
        Genre("Sister"),
        Genre("Sleeping"),
        Genre("Slime"),
        Genre("Small Breast"),
        Genre("Snuff"),
        Genre("Sole Female"),
        Genre("Sole Male"),
        Genre("Stocking"),
        Genre("Story Arc"),
        Genre("Sumata"),
        Genre("Sweating"),
        Genre("Swimsuit"),
        Genre("Tanlines"),
        Genre("Teacher"),
        Genre("Tentacles"),
        Genre("Tomboy"),
        Genre("Tomgirl"),
        Genre("Torture"),
        Genre("Twins"),
        Genre("Twintails"),
        Genre("Uncensored"),
        Genre("Unusual Pupils"),
        Genre("Virginity"),
        Genre("Webtoon"),
        Genre("Widow"),
        Genre("X-Ray"),
        Genre("Yandere"),
        Genre("Yaoi"),
        Genre("Yuri"),
    )

    private class AuthorFilter : Filter.Text("Author")
    private class GroupFilter : Filter.Text("Group")
    private class SeriesFilter : Filter.Text("Series")
    private class CharacterFilter : Filter.Text("Karakter")
    private class CategoryNames(categories: Array<Category>) : Filter.Select<Category>("Kategori", categories, 0)
    private class OrderBy(orders: Array<Order>) : Filter.Select<Order>("Urutkan", orders, 0)
    private class GenreList(genres: List<Genre>) : Filter.Group<Genre>("Genre", genres)
    private class StatusList(statuses: Array<Status>) : Filter.Select<Status>("Status", statuses, 0)

    private fun basicInformationFromElement(element: Element): SManga {
        val manga = SManga.create()
        element.select("a").let {
            manga.title = element.selectFirst("h3.title")!!.text()
            manga.setUrlWithoutDomain(it.attr("href"))
        }
        element.select("a > figure.thumbnail > img").first()?.let {
            manga.thumbnail_url = imageFromElement(it)
        }

        return manga
    }

    private fun imageFromElement(element: Element): String {
        return when {
            element.hasAttr("data-src") -> element.attr("abs:data-src")
            element.hasAttr("data-lazy-src") -> element.attr("abs:data-lazy-src")
            element.hasAttr("srcset") -> element.attr("abs:srcset").substringBefore(" ")
            else -> element.attr("abs:src")
        }
    }

    private fun getNumberFromString(epsStr: String?): Float {
        return epsStr?.substringBefore(" ")?.toFloatOrNull() ?: -1f
    }

    private fun reconstructDate(dateStr: String): Long {
        return runCatching { DATE_FORMAT.parse(dateStr)?.time }
            .getOrNull() ?: 0L
    }

    // Popular

    override fun popularMangaFromElement(element: Element): SManga =
        basicInformationFromElement(element)

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/manga/page/$page/?title=&author=&character=&statusx=&typex=&order=popular", headers)
    }

    // Latest

    override fun latestUpdatesFromElement(element: Element): SManga =
        basicInformationFromElement(element)

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/manga/page/$page/?title=&author=&character=&statusx=&typex=&order=update", headers)
    }

    // Element Selectors

    override fun popularMangaSelector(): String = "#archives > div.entries > article"
    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun searchMangaSelector() = popularMangaSelector()

    override fun popularMangaNextPageSelector(): String = "nav.pagination > ul > li.last > a"
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // Search & FIlter

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // Anything else filter handling
        val url = "$baseUrl/manga/page/$page/".toHttpUrl().newBuilder()
        url.addQueryParameter("title", query.ifBlank { "" })

        (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
            when (filter) {
                is CategoryNames -> {
                    val category = filter.values[filter.state]
                    url.addQueryParameter("typex", category.key)
                }
                is OrderBy -> {
                    val order = filter.values[filter.state]
                    url.addQueryParameter("order", order.key)
                }
                is CharacterFilter -> {
                    url.addQueryParameter("character", filter.state)
                }
                is GenreList -> {
                    filter.state
                        .filter { it.state }
                        .forEach { genre ->
                            url.addQueryParameter("genre[]", genre.id)
                        }
                }
                is StatusList -> {
                    val status = filter.values[filter.state]
                    url.addQueryParameter("statusx", status.key)
                }
                else -> {}
            }
        }

        val author = filters.firstInstanceOrNull<AuthorFilter>()?.state?.trim()
        val group = filters.firstInstanceOrNull<GroupFilter>()?.state?.trim()
        val series = filters.firstInstanceOrNull<SeriesFilter>()?.state?.trim()

        // Author filter handling
        if (query.isBlank()) {
            if (!author.isNullOrBlank()) {
                val slug = author.toMultiSlug()
                if (slug.isNotBlank()) {
                    val authorUrl = if (page == 1) {
                        "$baseUrl/author/$slug/"
                    } else {
                        "$baseUrl/author/$slug/page/$page/"
                    }
                    return GET(authorUrl, headers)
                }
            }

            // Group filter handling
            if (!group.isNullOrBlank()) {
                val slug = group.toMultiSlug()
                if (slug.isNotBlank()) {
                    val groupUrl = if (page == 1) {
                        "$baseUrl/group/$slug/"
                    } else {
                        "$baseUrl/group/$slug/page/$page/"
                    }
                    return GET(groupUrl, headers)
                }
            }

            // Series filter handling
            if (!series.isNullOrBlank()) {
                val slug = series.toMultiSlug()
                if (slug.isNotBlank()) {
                    val seriesUrl = if (page == 1) {
                        "$baseUrl/series/$slug/"
                    } else {
                        "$baseUrl/series/$slug/page/$page/"
                    }
                    return GET(seriesUrl, headers)
                }
            }
        }
        return GET(url.build(), headers)
    }

    private val nonAlphaNumSpaceDashRegex = Regex("[^a-z0-9\\s-]")
    private val multiSpaceRegex = Regex("\\s+")

    private fun String.toMultiSlug(): String {
        return this
            .trim()
            .lowercase()
            .replace(nonAlphaNumSpaceDashRegex, "")
            .replace(multiSpaceRegex, "-")
    }

    override fun searchMangaFromElement(element: Element): SManga =
        basicInformationFromElement(element)

    override fun getFilterList() = FilterList(
        Filter.Header("NB: Filter bisa digabungkan dengan memakai pencarian teks selain Author, Group dan Series!"),
        Filter.Separator(),
        Filter.Header("NB: Gunakan ini untuk filter per Author, Group dan Series saja, tidak bisa digabungkan dengan memakai pencarian teks dan filter lainnya!"),
        AuthorFilter(),
        GroupFilter(),
        SeriesFilter(),
        Filter.Separator(),
        Filter.Header("NB: Untuk Character Filter akan mengambil hasil apapun jika diinput, misal 'alice', maka hasil akan memunculkan semua Karakter yang memiliki nama 'Alice', bisa digabungkan dengan filter lainnya"),
        CharacterFilter(),
        StatusList(statusList),
        CategoryNames(categoryNames),
        OrderBy(orderBy),
        GenreList(genreList()),
    )

    // Detail Parse

    private val chapterListRegex = Regex("""\d+[-–]?\d*\..+<br>""", RegexOption.IGNORE_CASE)
    private val htmlTagRegex = Regex("<[^>]*>")

    override fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.selectFirst("section.metadata")!!
        val authorName = if (infoElement.select("td:contains(Author)
