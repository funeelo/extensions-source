package eu.kanade.tachiyomi.extension.id.kiryuu

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class Kiryuu : ParsedHttpSource() {

    override val name = "Kiryuu"

    override val baseUrl = "https://kiryuu03.com"

    override val lang = "id"

    override val supportsLatest = true

    override val id = 3639673976007021338

    override val client = network.cloudflareClient.newBuilder()
        .rateLimit(2, 1)
        .build()

    private val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale("id"))

    // Popular Manga
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/advanced-search/?order=popular&page=$page", headers)
    }

    override fun popularMangaSelector() = "div.grid > a[href*='/manga/']"

    override fun popularMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            title = element.selectFirst("h3, h2")?.text() ?: ""
            thumbnail_url = element.selectFirst("img")?.attr("abs:src")
        }
    }

    override fun popularMangaNextPageSelector() = "a[rel='next']"

    // Latest Updates
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/advanced-search/?order=update&page=$page", headers)
    }

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/advanced-search/".toHttpUrl().newBuilder()
        url.addQueryParameter("title", query)
        url.addQueryParameter("page", page.toString())

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> {
                    filter.state.forEach { genre ->
                        if (genre.state) {
                            url.addQueryParameter("genre[]", genre.id)
                        }
                    }
                }
                is StatusFilter -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("status", filter.toUriPart())
                    }
                }
                is TypeFilter -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("type", filter.toUriPart())
                    }
                }
                is SortFilter -> {
                    url.addQueryParameter("order", filter.toUriPart())
                }
                else -> {}
            }
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // Manga Details
    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            title = document.selectFirst("h1.text-3xl, h1")?.text() ?: ""
            thumbnail_url = document.selectFirst("img[alt*='${title}'], div.relative img")?.attr("abs:src")
            description = document.selectFirst("div[class*='description'], div.text-sm p")?.text()
            author = document.select("div:contains(Author) + div, span:contains(Author) + span").text()
            artist = author
            status = parseStatus(document.select("div:contains(Status) + div, span:contains(Status)").text())
            genre = document.select("a[href*='/genre/'], div.rounded-full span").joinToString { it.text() }
        }
    }

    private fun parseStatus(status: String) = when {
        status.contains("Ongoing", true) -> SManga.ONGOING
        status.contains("Completed", true) -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    // Chapter List
    override fun chapterListSelector() = "div[id*='chapter'] a[href*='/chapter-'], li.flex a[href*='/chapter-']"

    override fun chapterFromElement(element: Element): SChapter {
        return SChapter.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            name = element.selectFirst("span.font-medium, h3")?.text() 
                ?: element.ownText()
            date_upload = element.selectFirst("span.text-xs, time")?.text()?.let {
                try {
                    dateFormat.parse(it)?.time ?: 0L
                } catch (e: Exception) {
                    0L
                }
            } ?: 0L
        }
    }

    // Page List
    override fun pageListParse(document: Document): List<Page> {
        return document.select("div[id*='chapter-content'] img, div.reader-area img, #readerarea img").mapIndexed { index, element ->
            val imageUrl = element.attr("abs:src").ifEmpty {
                element.attr("abs:data-src")
            }.ifEmpty {
                element.attr("abs:data-lazy-src")
            }
            Page(index, "", imageUrl)
        }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()

    // Filters
    override fun getFilterList() = FilterList(
        Filter.Header("NOTE: Ignored if using text search!"),
        Filter.Separator(),
        SortFilter(),
        StatusFilter(),
        TypeFilter(),
        GenreFilter(),
    )

    private class SortFilter : UriPartFilter(
        "Sort By",
        arrayOf(
            Pair("Default", ""),
            Pair("A-Z", "title"),
            Pair("Z-A", "titlereverse"),
            Pair("Latest Update", "update"),
            Pair("Latest Added", "latest"),
            Pair("Popular", "popular"),
        ),
    )

    private class StatusFilter : UriPartFilter(
        "Status",
        arrayOf(
            Pair("All", ""),
            Pair("Ongoing", "ongoing"),
            Pair("Completed", "completed"),
        ),
    )

    private class TypeFilter : UriPartFilter(
        "Type",
        arrayOf(
            Pair("All", ""),
            Pair("Manga", "manga"),
            Pair("Manhwa", "manhwa"),
            Pair("Manhua", "manhua"),
        ),
    )

    private class GenreFilter : Filter.Group<GenreCheckBox>(
        "Genre",
        listOf(
            GenreCheckBox("Action", "action"),
            GenreCheckBox("Adventure", "adventure"),
            GenreCheckBox("Comedy", "comedy"),
            GenreCheckBox("Drama", "drama"),
            GenreCheckBox("Fantasy", "fantasy"),
            GenreCheckBox("Horror", "horror"),
            GenreCheckBox("Mystery", "mystery"),
            GenreCheckBox("Romance", "romance"),
            GenreCheckBox("Sci-Fi", "sci-fi"),
            GenreCheckBox("Slice of Life", "slice-of-life"),
            GenreCheckBox("Supernatural", "supernatural"),
        ),
    )

    private class GenreCheckBox(name: String, val id: String) : Filter.CheckBox(name)

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }
}