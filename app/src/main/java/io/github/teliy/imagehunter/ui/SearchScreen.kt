package io.github.teliy.imagehunter.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.teliy.imagehunter.R
import io.github.teliy.imagehunter.core.AppLog
import io.github.teliy.imagehunter.core.Downloader
import io.github.teliy.imagehunter.core.ENGINES
import io.github.teliy.imagehunter.core.Injector
import io.github.teliy.imagehunter.core.SelItem
import io.github.teliy.imagehunter.core.START_URL
import io.github.teliy.imagehunter.core.WebDav
import io.github.teliy.imagehunter.core.isImageSearchUrl
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLEncoder

/** Десктопный User-Agent: у части поисковиков мобильная выдача не даёт чекбоксы. */
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

/** График инъекции после загрузки страницы — как на десктопе (плитки дорисовываются). */
private val INJECT_DELAYS_MS = longArrayOf(0, 1500, 3500, 7000, 12000)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SearchScreen(desktopSite: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var query by rememberSaveable { mutableStateOf("") }
    var engineIdx by rememberSaveable { mutableIntStateOf(0) }
    var checked by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Pair<List<SelItem>, String>?>(null) }
    // (url, n) последней записи «плиток с чекбоксами» — дедупликация журнала
    var logKey by remember { mutableStateOf("" to -1) }

    fun find() {
        val q = query.trim()
        if (q.isEmpty()) return
        val engine = ENGINES[engineIdx]
        val url = engine.template.replace("%s", URLEncoder.encode(q, "UTF-8"))
        AppLog.log(context.getString(R.string.log_search, engine.name, q))
        webView?.loadUrl(url)
    }

    fun startDownload(items: List<SelItem>, referer: String) {
        if (busy || items.isEmpty()) return
        busy = true
        val ua = webView?.settings?.userAgentString ?: DESKTOP_UA
        scope.launch {
            val r = Downloader.download(context, items, referer, ua)
            var msg = context.getString(R.string.download_done, r.ok, r.fail)
            // авто-выгрузка скачанного в WebDAV (если включена в настройках)
            val dav = WebDav.autoUpload(context, r.uris)
            if (dav != null) {
                msg = context.getString(
                    R.string.download_done_dav, r.ok, r.fail, dav.ok, dav.fail
                )
            }
            busy = false
            infoMsg = msg
        }
    }

    Column(Modifier.fillMaxSize()) {
        // строка запроса + «Найти»
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.query_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { find() }),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { find() }, modifier = Modifier.padding(start = 8.dp)) {
                Text(stringResource(R.string.find))
            }
        }

        // поисковик + действия (горизонтальная прокрутка)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EngineMenu(engineIdx) { engineIdx = it }
            Button(
                onClick = {
                    val wv = webView ?: return@Button
                    wv.evaluateJavascript("window.__IH_SEL_JSON||'[]'") { res ->
                        val items = SelItem.parseAll(res)
                        if (items.isEmpty()) {
                            infoMsg = context.getString(R.string.no_checked)
                        } else {
                            startDownload(items, wv.url ?: "")
                        }
                    }
                },
                enabled = checked > 0 && !busy,
            ) {
                Text(
                    stringResource(R.string.download_checked) +
                        if (checked > 0) " " + stringResource(R.string.checked_suffix, checked) else ""
                )
            }
            OutlinedButton(
                onClick = {
                    val wv = webView ?: return@OutlinedButton
                    wv.evaluateJavascript(Injector.COLLECT) { res ->
                        val urls = SelItem.parseUrlList(res)
                        if (urls.isEmpty()) {
                            infoMsg = context.getString(R.string.no_links)
                        } else {
                            AppLog.log(context.getString(R.string.log_collect, urls.size))
                            confirm = urls.map { SelItem(it, emptyList()) } to (wv.url ?: "")
                        }
                    }
                },
                enabled = !busy,
            ) {
                Text(stringResource(R.string.collect_links))
            }
            OutlinedButton(
                onClick = {
                    checked = 0
                    AppLog.log(context.getString(R.string.log_clear_marks))
                    webView?.evaluateJavascript(
                        "window.__IH_CB&&window.__IH_CB.uncheckAll?window.__IH_CB.uncheckAll():0", null
                    )
                },
                enabled = checked > 0,
            ) {
                Text(stringResource(R.string.clear_marks))
            }
        }

        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        // встроенный браузер поиска
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx ->
                WebView(ctx).also { wv ->
                    wv.layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    wv.settings.javaScriptEnabled = true
                    wv.settings.domStorageEnabled = true
                    if (Injector.defaultUa == null) Injector.defaultUa = wv.settings.userAgentString
                    applyUserAgent(wv, desktopSite)
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            scheduleInjections(view)
                        }

                        override fun doUpdateVisitedHistory(
                            view: WebView, url: String?, isReload: Boolean
                        ) {
                            // SPA-переходы (новый поиск изнутри страницы) — тоже выдача
                            scheduleInjections(view)
                        }
                    }
                    wv.loadUrl(START_URL)
                    webView = wv
                }
            },
            update = { wv -> applyUserAgent(wv, desktopSite) },
        )
    }

    // системная «назад» — назад в браузере, как в обычном браузере
    BackHandler(enabled = webView?.canGoBack() == true) { webView?.goBack() }

    // опрос отметок раз в 700 мс — тот же контракт, что у десктопа
    LaunchedEffect(Unit) {
        while (true) {
            delay(700)
            val wv = webView ?: continue
            wv.evaluateJavascript("window.__IH_CB_COUNT||0") { res ->
                val n = res?.trim('"', ' ', '\n', '\r')?.toIntOrNull() ?: 0
                checked = n
                val url = wv.url ?: ""
                if (logKey.first != url || logKey.second != n) {
                    logKey = url to n
                    if (n > 0) {
                        AppLog.log(context.getString(R.string.log_boxes, n))
                    } else if (isImageSearchUrl(url)) {
                        AppLog.log(context.getString(R.string.log_boxes_zero, 1), warn = true)
                    }
                }
            }
        }
    }

    // информационные диалоги
    infoMsg?.let { msg ->
        AlertDialog(
            onDismissRequest = { infoMsg = null },
            confirmButton = {
                TextButton(onClick = { infoMsg = null }) { Text(stringResource(R.string.ok)) }
            },
            title = { Text(stringResource(R.string.app_name)) },
            text = { Text(msg) },
        )
    }

    // подтверждение «Собрать ссылки»
    confirm?.let { (items, referer) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    startDownload(items, referer)
                }) { Text(stringResource(R.string.download)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) }
            },
            title = { Text(stringResource(R.string.collect_confirm_title)) },
            text = { Text(stringResource(R.string.collect_confirm_text, items.size)) },
        )
    }
}

@Composable
private fun EngineMenu(current: Int, onSelect: (Int) -> Unit) {
    Box {
        var open by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { open = true }, modifier = Modifier.width(150.dp)) {
            Text(ENGINES[current].short, maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ENGINES.forEachIndexed { idx, engine ->
                DropdownMenuItem(
                    text = { Text(engine.name) },
                    onClick = {
                        open = false
                        onSelect(idx)
                    },
                )
            }
        }
    }
}

/** Переключение мобильного/десктопного User-Agent (с перезагрузкой страницы). */
private fun applyUserAgent(wv: WebView, desktopSite: Boolean) {
    val target = if (desktopSite) DESKTOP_UA else Injector.defaultUa ?: return
    if (wv.settings.userAgentString == target) return
    wv.settings.userAgentString = target
    if (!wv.url.isNullOrBlank()) wv.reload()
}

/** Инъекция по графику после загрузки страницы (идемпотентно). */
private fun scheduleInjections(view: WebView) {
    for (ms in INJECT_DELAYS_MS) {
        view.postDelayed({
            val url = view.url ?: return@postDelayed
            Injector.inject(view, url)
        }, ms)
    }
}
