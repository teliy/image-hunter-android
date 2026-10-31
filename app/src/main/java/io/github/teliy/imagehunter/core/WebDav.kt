package io.github.teliy.imagehunter.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.teliy.imagehunter.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * WebDAV-выгрузка сохранённых картинок — аналог десктопной работы с сетевым
 * диском (Z:), только без монтирования: OkHttp говорит с сервером напрямую.
 *
 *  - PROPFIND (Depth 0) — «Проверить» в настройках;
 *  - MKCOL — создать папку (405 = уже есть, это норм);
 *  - PUT — загрузить файл (201/204 = успех).
 *
 * Анти-фриз — уроки десктопной версии (фикс зависания на диске Z):
 *  - все запросы только из корутин на Dispatchers.IO, UI-поток не блокируется;
 *  - загрузка ПОСЛЕДОВАТЕЛЬНАЯ, одна картинка за раз;
 *  - таймауты на всё: connect 15 с, read 30 с, write 60 с;
 *  - редиректы выключены: сервер, отвечающий 3xx на PUT, дал бы ложный
 *    «успех» (OkHttp переиграл бы PUT в GET) — лучше явная ошибка с подсказкой.
 *
 * Папка создаётся по сегментам: «photos/hunter» → MKCOL photos, потом MKCOL
 * photos/hunter. Имена сегментов и файлов %-кодируются (кириллица/пробелы ок).
 */
object WebDav {

    const val PREFS = "imagehunter"

    /** Настройка подключения; хранится в SharedPreferences приложения. */
    data class Config(
        val url: String = "",        // база, например https://example.com/dav
        val user: String = "",
        val password: String = "",
        val folder: String = "ImageHunter",
        val autoUpload: Boolean = false,
    ) {
        val isConfigured: Boolean get() = url.trim().startsWith("http")
    }

    data class Result(val ok: Int, val fail: Int)

    /** Картинка для загрузки: id записи MediaStore (для отметки «уже выгружено») + uri. */
    data class DavFile(val id: Long = -1L, val uri: Uri)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    // ---------- настройки ----------

    fun loadConfig(context: Context): Config {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            url = p.getString("dav_url", "") ?: "",
            user = p.getString("dav_user", "") ?: "",
            password = p.getString("dav_pass", "") ?: "",
            folder = p.getString("dav_folder", "ImageHunter") ?: "ImageHunter",
            autoUpload = p.getBoolean("dav_auto", false),
        )
    }

    /**
     * Сохранить конфиг. Если сменились адрес или папка — забываем отметки
     * «уже выгружено»: на новом месте сервер пуст.
     */
    fun saveConfig(context: Context, cfg: Config) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = loadConfig(context)
        p.edit()
            .putString("dav_url", cfg.url.trim())
            .putString("dav_user", cfg.user.trim())
            .putString("dav_pass", cfg.password)
            .putString("dav_folder", cfg.folder.trim().ifEmpty { "ImageHunter" })
            .putBoolean("dav_auto", cfg.autoUpload)
            .apply()
        if (old.url.trim() != cfg.url.trim() ||
            old.folder.trim() != cfg.folder.trim()
        ) {
            p.edit().putStringSet("dav_uploaded", emptySet()).apply()
        }
    }

    /** MediaStore-id картинок, уже выгруженных на текущий сервер/папку. */
    fun uploadedIds(context: Context): Set<Long> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return (p.getStringSet("dav_uploaded", emptySet()) ?: emptySet())
            .mapNotNull { it.toLongOrNull() }
            .toSet()
    }

    private fun markUploaded(context: Context, ids: List<Long>) {
        val real = ids.filter { it >= 0 }
        if (real.isEmpty()) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cur = p.getStringSet("dav_uploaded", emptySet()) ?: emptySet()
        p.edit().putStringSet("dav_uploaded", cur + real.map { it.toString() }).apply()
    }

    // ---------- авто-выгрузка скачанного ----------

    /**
     * Авто-выгрузка только что скачанных картинок. Возвращает null, если
     * выключена или не настроена — тогда вызывающий показывает прежний итог.
     */
    suspend fun autoUpload(context: Context, uris: List<Uri>): Result? {
        if (uris.isEmpty()) return null
        val cfg = loadConfig(context)
        if (!cfg.autoUpload || !cfg.isConfigured) return null
        return upload(context, cfg, uris.map { DavFile(uri = it) })
    }

    // ---------- загрузка ----------

    /**
     * Последовательно выгрузить картинки. Папку создаёт перед первой загрузкой
     * (и повторяет попытку, если сервер ответил ошибкой создания папки).
     * Каждая ошибка — в журнал, остальные файлы продолжают грузиться.
     */
    suspend fun upload(context: Context, cfg: Config, files: List<DavFile>): Result =
        withContext(Dispatchers.IO) {
            AppLog.log(context.getString(R.string.log_dav_start, files.size))
            var ok = 0
            var fail = 0
            val uploadedIds = mutableListOf<Long>()
            var foldersReady = false
            for (f in files) {
                val name = displayName(context, f.uri)
                try {
                    val base = baseUrl(cfg)
                    if (!foldersReady) {
                        ensureFolders(base, cfg, cfg.folder)
                        foldersReady = true
                    }
                    putFile(context, cfg, base, cfg.folder, f.uri, name)
                    ok++
                    uploadedIds.add(f.id)
                    AppLog.log(context.getString(R.string.log_dav_ok, name))
                } catch (e: Exception) {
                    fail++
                    AppLog.log(
                        context.getString(R.string.log_dav_fail, name, e.message ?: "?"),
                        warn = true,
                    )
                    // папка могла не создаться — на следующем файле попробуем снова
                    if (e is DavException && e.folderStage) foldersReady = false
                }
            }
            if (ok > 0) markUploaded(context, uploadedIds)
            Result(ok, fail)
        }

    /** Проверка соединения: null — успех, иначе текст ошибки (для UI). */
    suspend fun testConnection(cfg: Config): String? = withContext(Dispatchers.IO) {
        try {
            val base = baseUrl(cfg)
            val body = ("<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>")
                .toRequestBody("application/xml".toMediaTypeOrNull())
            val req = builder(cfg, base)
                .method("PROPFIND", body)
                .header("Depth", "0")
                .build()
            client.newCall(req).execute().use { resp ->
                when (resp.code) {
                    207, 200 -> null
                    401, 403 -> "HTTP ${resp.code} — " +
                        (if (cfg.user.isEmpty()) "server requires login" else "wrong login/password")
                    else -> "HTTP ${resp.code}"
                }
            }
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
    }

    // ---------- внутренности ----------

    /** Нормализованная база без хвостового «/»; исключение — адрес не http(s). */
    private fun baseUrl(cfg: Config): String {
        val u = cfg.url.trim().trimEnd('/')
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            throw DavException("URL must start with http:// or https://")
        }
        return u
    }

    private fun builder(cfg: Config, url: String): Request.Builder {
        val b = Request.Builder().url(url).header("User-Agent", "ImageHunter-Android")
        if (cfg.user.isNotEmpty()) {
            b.header("Authorization", Credentials.basic(cfg.user, cfg.password, Charsets.UTF_8))
        }
        return b
    }

    /**
     * Создать папку выгрузки по сегментам. 405 («уже есть») — не ошибка.
     * Мы создаём по порядку, поэтому 409 обычно означает проблему с базой.
     */
    private fun ensureFolders(base: String, cfg: Config, folder: String) {
        var cum = base
        for (seg in folder.split('/').map { it.trim() }.filter { it.isNotEmpty() }) {
            cum += "/" + Uri.encode(seg)
            val req = builder(cfg, cum).method("MKCOL", null).build()
            client.newCall(req).execute().use { resp ->
                if (resp.code !in intArrayOf(200, 201, 204, 405)) {
                    throw DavException("MKCOL \"$seg\" HTTP ${resp.code}", folderStage = true)
                }
            }
        }
    }

    private fun putFile(
        context: Context,
        cfg: Config,
        base: String,
        folder: String,
        uri: Uri,
        name: String,
    ) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("cannot open $name")
        val path = buildString {
            append(base)
            for (seg in folder.split('/').map { it.trim() }.filter { it.isNotEmpty() }) {
                append('/')
                append(Uri.encode(seg))
            }
            append('/')
            append(Uri.encode(name))
        }
        val req = builder(cfg, path)
            .put(bytes.toRequestBody(mimeFor(name).toMediaTypeOrNull()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code !in intArrayOf(200, 201, 204)) {
                val hint = when (resp.code) {
                    401, 403 -> " — check login/password"
                    409 -> " — folder missing on server"
                    in 300..399 -> " — server redirects; use the direct https:// URL"
                    else -> ""
                }
                throw DavException("PUT HTTP ${resp.code}$hint")
            }
        }
    }

    /** Имя файла записи MediaStore (или от uri), для удалённой стороны. */
    private fun displayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        } catch (e: Exception) {
            // ниже нейтральное имя
        }
        return "image_${System.currentTimeMillis()}.jpg"
    }

    private fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "avif" -> "image/avif"
            else -> "application/octet-stream"
        }
    }

    /** Ошибка с понятным текстом; folderStage — сбой на этапе создания папки. */
    private class DavException(message: String, val folderStage: Boolean = false) :
        Exception(message)
}
