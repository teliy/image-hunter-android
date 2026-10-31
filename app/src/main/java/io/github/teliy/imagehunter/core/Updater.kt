package io.github.teliy.imagehunter.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Обновление из приложения — аналог десктопного app/core/updates.py:
 *
 *  1. checkLatest()      — спросить GitHub Releases
 *                          (api.github.com/repos/teliy/image-hunter-android/releases/latest);
 *  2. isNewer()          — сравнить versionName релиза (тег «v0.2.0» → 0.2.0)
 *                          с установленной версией;
 *  3. downloadApk()      — скачать APK ассет релиза в кэш приложения
 *                          с прогрессом для UI;
 *  4. installApk()       — отдать APK системному установщику через
 *                          FileProvider; при отсутствии права «устанавливать
 *                          неизвестные приложения» сначала открыть настройки.
 *
 * Проверка без токена: GitHub отдаёт 60 запросов/час с одного IP —
 * для автопроверки раз в сутки этого достаточно с запасом.
 */
object Updater {

    const val RELEASES_URL =
        "https://api.github.com/repos/teliy/image-hunter-android/releases/latest"

    /** Информация о доступном релизе. */
    data class UpdateInfo(
        val version: String,      // версия БЕЗ ведущего «v», например «0.2.0»
        val apkUrl: String,       // browser_download_url ассета .apk
        val sizeBytes: Long,      // размер APK (для строки в диалоге)
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    // ---------- версия ----------

    /** Установленный versionName (например «0.2.0»). */
    fun installedVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        "0"
    }

    /** Установленный versionCode. */
    fun installedVersionCode(context: Context): Long = try {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    } catch (e: Exception) {
        0L
    }

    /** «v0.10.3-beta» → [0, 10, 3]; нечисловые хвосты отбрасываем. */
    fun parseVersion(v: String): List<Int> {
        val clean = v.trim().removePrefix("v").removePrefix("V").substringBefore('-')
        return clean.split('.').mapNotNull { part ->
            part.trim().toIntOrNull()?.let { if (it < 0) null else it }
        }
    }

    /** true, если remote строго новее установленной версии. */
    fun isNewer(remote: String, installed: String): Boolean {
        val a = parseVersion(remote)
        val b = parseVersion(installed)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    // ---------- проверка релиза ----------

    /**
     * Спросить последний релиз GitHub. Вернуть UpdateInfo, если релиз есть
     * и в нём есть APK-ассет; null — релизов нет / сеть недоступна.
     */
    suspend fun checkLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(RELEASES_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "ImageHunter-Android")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body ?: return@withContext null
                val json = JSONObject(body.string())
                val tag = json.optString("tag_name")
                if (tag.isEmpty()) return@withContext null
                val assets = json.optJSONArray("assets") ?: return@withContext null
                var apkUrl: String? = null
                var apkSize = 0L
                // предпочтительно «ImageHunter-….apk», иначе любой .apk (старые релизы)
                var fallbackUrl: String? = null
                var fallbackSize = 0L
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name")
                    val url = a.optString("browser_download_url")
                    if (!name.endsWith(".apk", ignoreCase = true) || !url.startsWith("http")) continue
                    if (name.startsWith("ImageHunter", ignoreCase = true)) {
                        apkUrl = url
                        apkSize = a.optLong("size")
                        break
                    }
                    if (fallbackUrl == null) {
                        fallbackUrl = url
                        fallbackSize = a.optLong("size")
                    }
                }
                val chosenUrl = apkUrl ?: fallbackUrl ?: return@withContext null
                val chosenSize = if (apkUrl != null) apkSize else fallbackSize
                UpdateInfo(
                    version = tag.removePrefix("v").removePrefix("V"),
                    apkUrl = chosenUrl,
                    sizeBytes = chosenSize,
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------- скачивание APK ----------

    /**
     * Скачать APK релиза в кэш приложения (cache/updates/ImageHunter-update.apk),
     * onProgress — процент 0..100. Возвращает готовый файл.
     */
    suspend fun downloadApk(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "ImageHunter-update.apk")
        out.delete()
        try {
            val req = Request.Builder()
                .url(info.apkUrl)
                .header("User-Agent", "ImageHunter-Android")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("empty response body")
                val total = if (body.contentLength() > 0) body.contentLength() else info.sizeBytes
                body.byteStream().use { input ->
                    FileOutputStream(out).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val read = input.read(buf)
                            if (read == -1) break
                            output.write(buf, 0, read)
                            done += read
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
            }
            if (out.length() == 0L) throw IOException("downloaded file is empty")
            onProgress(100)
            out
        } catch (e: Exception) {
            out.delete()
            throw e
        }
    }

    /** Удалить недокачанный/устаревший APK из кэша (например при следующем старте). */
    fun cleanup(context: Context) {
        runCatching {
            File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
        }
    }

    // ---------- установка ----------

    /** Есть ли право «устанавливать неизвестные приложения» (Android 8+). */
    fun canInstall(activity: Activity): Boolean =
        activity.packageManager.canRequestPackageInstalls()

    /** Открыть системные настройки права установки для нашего приложения. */
    fun openInstallPermissionSettings(activity: Activity) {
        activity.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}"),
            )
        )
    }

    /**
     * Запустить установку скачанного APK системным установщиком.
     * Если права нет — открываем настройки и возвращаем false
     * (после выдачи права пользователь жмёт «Установить» ещё раз).
     */
    fun installApk(activity: Activity, apk: File): Boolean {
        if (!canInstall(activity)) {
            openInstallPermissionSettings(activity)
            return false
        }
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
        return true
    }
}
