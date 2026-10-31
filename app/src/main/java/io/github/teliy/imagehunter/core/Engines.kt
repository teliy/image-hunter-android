package io.github.teliy.imagehunter.core

import android.net.Uri
import java.util.Locale

/**
 * Поисковики Image Hunter — порт app/core/engines.py десктопной версии.
 *
 * Каждый движок: полное имя в списке, шаблон поиска (%s — закодированный
 * запрос), короткое имя, фирменный цвет бренда.
 */
data class Engine(
    val name: String,
    val template: String,
    val short: String,
    val color: String,
)

val ENGINES: List<Engine> = listOf(
    Engine("Яндекс.Картинки", "https://ya.ru/images/search?text=%s", "Яндекс", "#FC3F1D"),
    // iax=images&ia=images — канонический адрес выдачи картинок DDG; kp=1 — strict safe search
    Engine("DuckDuckGo (safe)", "https://duckduckgo.com/?q=%s&iax=images&ia=images&kp=1&t=h_", "DuckDuckGo", "#DE5833"),
    Engine("Google Картинки", "https://www.google.com/search?q=%s&udm=2", "Google", "#4285F4"),
    Engine("Bing Картинки", "https://www.bing.com/images/search?q=%s", "Bing", "#008373"),
    Engine("Brave Картинки", "https://search.brave.com/images?q=%s", "Brave", "#FB542B"),
    Engine("Startpage Картинки", "https://www.startpage.com/sp/search?query=%s&cat=images", "Startpage", "#6573FF"),
    Engine("Mojeek Картинки", "https://www.mojeek.com/images?q=%s", "Mojeek", "#455A64"),
    Engine("Baidu Картинки", "https://image.baidu.com/search/index?tn=baiduimage&ie=utf-8&word=%s", "Baidu", "#2932E1"),
    Engine("Sogou Картинки", "https://pic.sogou.com/pics?query=%s", "Sogou", "#FF6A00"),
    Engine("Naver Картинки", "https://search.naver.com/search.naver?where=image&query=%s", "Naver", "#03C75A"),
    // mail.ru/search?type=images — конечный адрес (go.mail.ru/search_images редиректит сюда)
    Engine("Mail.ru Картинки", "https://mail.ru/search?text=%s&type=images", "Mail.ru", "#005FF9"),
)

/** Начальная страница новой вкладки поиска — как на десктопе. */
const val START_URL = "https://ya.ru/images/"

private val GOOGLE_HOST = Regex("(^|\\.)google\\.[a-z.]{2,6}$")

private val YANDEX_HOSTS = listOf(
    "ya.ru", "yandex.ru", "yandex.by", "yandex.kz", "yandex.uz", "yandex.com",
)

/**
 * Это страница выдачи картинок известного поисковика? Только там ставим
 * чекбоксы (порт is_image_search_url из engines.py).
 */
fun isImageSearchUrl(url: String): Boolean {
    if (url.isBlank()) return false
    return try {
        val u = Uri.parse(url)
        val host = (u.host ?: "").lowercase(Locale.ROOT)
        val path = u.path ?: ""
        fun q(k: String): String = u.getQueryParameter(k) ?: ""
        val yandex = YANDEX_HOSTS.any { host == it || host.endsWith(".$it") }
        when {
            yandex -> path.startsWith("/images")
            host.endsWith("duckduckgo.com") ->
                q("ia") == "images" || q("iax") == "images" || q("iar") == "images"
            GOOGLE_HOST.containsMatchIn(host) ->
                q("udm") == "2" || path.startsWith("/imgres")
            host.endsWith("bing.com") -> path.startsWith("/images")
            host.endsWith("brave.com") -> path.startsWith("/images")
            host.endsWith("mojeek.com") -> path.startsWith("/images")
            host == "image.baidu.com" -> true
            host == "pic.sogou.com" -> true
            host.endsWith("naver.com") ->
                q("where") == "image" || path.startsWith("/image-search")
            host.endsWith("startpage.com") ->
                (path.startsWith("/sp/search") || path.startsWith("/do/search")) && q("cat") == "images"
            // Mail.ru объединил поисковый портал: выдача картинок — mail.ru/search?type=images
            host == "go.mail.ru" -> path.startsWith("/search_images")
            host == "mail.ru" || host.endsWith(".mail.ru") ->
                path.startsWith("/search") && q("type") == "images"
            else -> false
        }
    } catch (e: Exception) {
        false
    }
}
