package eu.kanade.tachiyomi.extension.id.kiryuu

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody.Companion.asResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class Kiryuu : MangaThemesia(
    "Kiryuu",
    "https://kiryuu02.com",
    "id",
    dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("id")),
) {
    override val id = 3639673976007021338

    override val client: OkHttpClient = super.client.newBuilder()
        .addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val mime = response.headers["Content-Type"]
            if (response.isSuccessful) {
                if (mime != "application/octet-stream") {
                    return@addInterceptor response
                }
                val type = IMG_CONTENT_TYPE.toMediaType()
                val body = response.body.source().asResponseBody(type)
                return@addInterceptor response.newBuilder().body(body).build()
            }
            response
        }
        .rateLimit(4)
        .build()

    override fun Element.imgAttr(): String = when {
        hasAttr("data-lzl-src") -> attr("abs:data-lzl-src")
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("data-src") -> attr("abs:data-src")
        hasAttr("data-cfsrc") -> attr("abs:data-cfsrc")
        else -> attr("abs:src")
    }

    override fun mangaDetailsParse(document: Document) = super.mangaDetailsParse(document).apply {
        title = document.selectFirst(seriesThumbnailSelector)!!.attr("title")
    }

    override val hasProjectPage = true

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("status", "")
            .addQueryParameter("type", "")
            .addQueryParameter("order", "popular")
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("status", "")
            .addQueryParameter("type", "")
            .addQueryParameter("order", "update")
            .build()
        return GET(url, headers)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/manga".toHttpUrl().newBuilder()
            .addQueryParameter("title", query)
            .addQueryParameter("page", page.toString())

        filters.forEach { filter ->
            when (filter) {
                is StatusFilter -> {
                    url.addQueryParameter("status", filter.toUriPart())
                }
                is TypeFilter -> {
                    url.addQueryParameter("type", filter.toUriPart())
                }
                is OrderByFilter -> {
                    url.addQueryParameter("order", filter.toUriPart())
                }
                else -> {}
            }
        }

        return GET(url.build(), headers)
    }

    override fun popularMangaSelector() = "div.listupd div.bs"

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun searchMangaSelector() = popularMangaSelector()

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        title = element.selectFirst("a")!!.attr("title")
        thumbnail_url = element.selectFirst("img")?.imgAttr()
    }

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun popularMangaNextPageSelector() = "div.pagination a.next"

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun chapterListSelector() = "div.eph-num a"

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.selectFirst("span.chapternum")?.text() ?: element.text()
        date_upload = element.selectFirst("span.chapterdate")?.text()?.let { parseChapterDate(it) } ?: 0
    }

    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()

        document.select("div#readerarea img").forEachIndexed { index, element ->
            val imageUrl = element.imgAttr()
            if (imageUrl.isNotEmpty()) {
                pages.add(Page(index, "", imageUrl))
            }
        }

        return pages
    }

    override fun imageUrlParse(document: Document): String {
        return document.selectFirst("div#readerarea img")?.imgAttr() ?: ""
    }
}

private const val IMG_CONTENT_TYPE = "image/jpeg"
