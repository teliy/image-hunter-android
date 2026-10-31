package io.github.teliy.imagehunter.core

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.util.JSONTokener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Semaphore
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Отмеченная плитка выдачи: лучший кандидат на оригинал + запасные ссылки. */
data class SelItem(val best: String, val alts: List<String>) {

    companion object {
        /**
         * Разобрать результат `window.__IH_SEL_JSON||'[]'` из evaluateJavascript:
         * приходит JSON-литерал строки, поэтому сначала раскрываем строку.
         */
        fun parseAll(result: String?): List<SelItem> {
            return try {
                val s = JSONTokener(result ?: "[]").nextValue().toString()
                val arr = org.json.JSONArray(s)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val best = o.optString("best")
                    if (!best.startsWith("http")) return@mapNotNull null
                    val alts = mutableListOf<String>()
                    val a = o.optJSONArray("alts")
                    if (a != null) {
                        for (j in 0 until a.length()) {
                            val u = a.optString(j)
                            if (u.startsWith("http")) alts.add(u)
                        }
                    }
                    SelItem(best, alts)
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        /** Разобрать результат COLLECT_JS — JSON-массив ссылок. */
        fun parseUrlList(result: String?): List<String> {
            return try {
                val s = JSONTokener(result ?: "[]").nextValue().toString()
                val arr = org.json.JSONArray(s)
                (0 until arr.length()).mapNotNull { i ->
                    arr.optString(i).takeIf { it.startsWith("http") }
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}

/**
 * Загрузка картинок в галерею (MediaStore, Pictures/ImageHunter).
 *
 * Для каждой плитки пробуем best, затем alts (как на десктопе: сначала
 * оригинал, потом запасные миниатюры). Referer — страница выдачи, откуда
 * взяли картинку: часть сайтов без него отдаёт 403.
 */
object Downloader {

    data class Summary(val ok: Int, val fail: Int)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    suspend fun download(
        context: Context,
        items: List<SelItem>,
        referer: String,
        userAgent: String,
    ): Summary = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        AppLog.log(context.getString(R.string.log_download_start, items.size, referer))
        var ok = 0
        var fail = 0
        coroutineScope {
            val gate = Semaphore(3)   // три параллельных загрузки, как договаривались на десктопе
            items.forEachIndexed { idx, item ->
                launch {
                    gate.withPermit {
                        val fetched = fetch(item, referer, userAgent)
                        if (fetched == null) {
                            synchronized(this@Downloader) { fail++ }
                            AppLog.log(context.getString(R.string.log_download_fail, item.best), warn = true)
                            return@withPermit
                        }
                        val (bytes, mime) = fetched
                        val name = "IH_${stamp}_${idx + 1}.${extFor(mime, item.best)}"
                        val saved = save(resolver, name, mime, bytes)
                        if (saved) {
                            synchronized(this@Downloader) { ok++ }
                            AppLog.log(context.getString(R.string.log_download_ok, name))
                        } else {
                            synchronized(this@Downloader) { fail++ }
                            AppLog.log(context.getString(R.string.log_download_fail, name), warn = true)
                        }
                    }
                }
            }
        }
        Summary(ok, fail)
    }

    /** Скачать первый валидный кандидат: (байты, mime) или null. */
    private fun fetch(item: SelItem, referer: String, ua: String): Pair<ByteArray, String>? {
        for (url in listOf(item.best) + item.alts) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", ua)
                    .header("Referer", referer)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body ?: return@use
                    val bytes = body.bytes()
                    if (bytes.isEmpty() || !looksImage(bytes)) return@use
                    val mime = (resp.header("Content-Type") ?: "")
                        .substringBefore(';').trim()
                        .ifEmpty { "image/jpeg" }
                    return bytes to mime
                }
            } catch (e: Exception) {
                // сеть/протокол — пробуем следующего кандидата
            }
        }
        return null
    }

    /** Проверка, что байты действительно декодируются как изображение. */
    private fun looksImage(bytes: ByteArray): Boolean {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            opts.outWidth > 0 && opts.outHeight > 0
        } catch (e: Exception) {
            false
        }
    }

    /** Сохранить в MediaStore: Pictures/ImageHunter; true — успех. */
    private fun save(resolver: android.content.ContentResolver, name: String, mime: String, bytes: ByteArray): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ImageHunter")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun extFor(mime: String, url: String): String {
        val byMime = mapOf(
            "image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp",
            "image/gif" to "gif", "image/bmp" to "bmp", "image/avif" to "avif",
        )
        byMime[mime.lowercase(Locale.ROOT)]?.let { return it }
        val path = url.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "")
        return if (ext.length in 2..5 && ext.all { it.isLetterOrDigit() }) ext.lowercase(Locale.ROOT) else "jpg"
    }
}
