package io.github.teliy.imagehunter.core

import android.content.Context
import android.webkit.WebView
import io.github.teliy.imagehunter.R
import org.json.JSONObject

/**
 * Инъекция чекбоксов и сбор ссылок — тот же контракт, что у десктопа:
 *
 *  - assets/inject_checkbox.js — навешивает на плитки выдачи оранжевые
 *    чекбоксы и ведёт window.__IH_CB_COUNT / window.__IH_SEL_JSON
 *    ([{"best": "<url>", "alts": [...]}, …]); это ДОСЛОВНЫЙ JS из
 *    app/core/extractor.py десктопной версии (эвристики плиток Яндекса,
 *    DDG, Google, Bing, Brave, Sogou, прокси-раскрытия, подъём к обёртке
 *    с оригиналом, MutationObserver для бесконечной выдачи);
 *  - assets/collect_links.js — собирает все ссылки на картинки со страницы.
 *
 * Отличие от десктопа: Android WebView исполняет JS только в главной
 * странице (кросс-доменные фреймы недоступны), поэтому выдача Mail.ru
 * во вложенном фрейме Яндекса чекбоксов не получит — для остальных
 * поисковиков логика идентична.
 */
object Injector {

    /** JS сбора всех ссылок на картинки (результат — JSON-строка массива). */
    lateinit var COLLECT: String
        private set

    /** Стандартный мобильный User-Agent WebView (запоминаем до первого переключения). */
    var defaultUa: String? = null

    private var checkboxJs: String = ""
    private var appContext: Context? = null

    /** Прочитать ассеты один раз при старте приложения. */
    fun init(context: Context) {
        if (checkboxJs.isNotEmpty()) return
        val ctx = context.applicationContext
        appContext = ctx
        checkboxJs = ctx.assets.open("inject_checkbox.js").bufferedReader().use { it.readText() }
        COLLECT = ctx.assets.open("collect_links.js").bufferedReader().use { it.readText() }
    }

    /**
     * Навесить чекбоксы на страницу выдачи (идемпотентно — повторные запуски
     * просто пересканируют страницу, как на десктопе). На страницах вне
     * выдачи ничего не делает.
     */
    fun inject(webView: WebView, pageUrl: String) {
        if (checkboxJs.isEmpty() || !isImageSearchUrl(pageUrl)) return
        val title = appContext?.getString(R.string.cb_title) ?: "Image Hunter"
        val js = checkboxJs.replace("__IH_CB_TITLE__", JSONObject.quote(title))
        webView.evaluateJavascript(js, null)
    }
}
