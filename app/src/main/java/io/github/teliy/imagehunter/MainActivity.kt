package io.github.teliy.imagehunter

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import io.github.teliy.imagehunter.core.AppLog
import io.github.teliy.imagehunter.core.Injector
import io.github.teliy.imagehunter.core.Updater
import io.github.teliy.imagehunter.ui.GalleryScreen
import io.github.teliy.imagehunter.ui.LogScreen
import io.github.teliy.imagehunter.ui.SearchScreen
import io.github.teliy.imagehunter.ui.theme.ImageHunterTheme
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/** Экраны диалога обновления (аналог сценария app/core/updates.py десктопа). */
private sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data class Available(val info: Updater.UpdateInfo) : UpdateUi
    data class Downloading(val pct: Int) : UpdateUi
    data class Ready(val file: File) : UpdateUi
}

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Injector.init(applicationContext)
        Updater.cleanup(applicationContext)   // недокачанные APK прошлых сессий
        val prefs = getSharedPreferences("imagehunter", MODE_PRIVATE)

        setContent {
            ImageHunterTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                var showSettings by rememberSaveable { mutableStateOf(false) }
                var desktopSite by rememberSaveable { mutableStateOf(prefs.getBoolean("desktop_site", false)) }
                var updateUi by remember { mutableStateOf<UpdateUi>(UpdateUi.Idle) }
                var updateMsg by remember { mutableStateOf<String?>(null) }
                var permsHint by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                val appVer = remember { Updater.installedVersion(this@MainActivity) }

                // Автопроверка обновлений при старте — не чаще раза в сутки
                // (тихая: диалог показываем только если есть новая версия).
                LaunchedEffect(Unit) {
                    val now = System.currentTimeMillis()
                    if (now - prefs.getLong("last_update_check", 0L) > AUTO_CHECK_INTERVAL_MS) {
                        prefs.edit().putLong("last_update_check", now).apply()
                        runCatching { Updater.checkLatest() }.getOrNull()?.let { info ->
                            if (Updater.isNewer(info.version, appVer)) {
                                AppLog.log(getString(R.string.log_update_found, info.version))
                                updateUi = UpdateUi.Available(info)
                            }
                        }
                    }
                }

                // Ручная проверка из настроек: показываем любой результат.
                val checkUpdates: () -> Unit = {
                    scope.launch {
                        updateUi = UpdateUi.Checking
                        AppLog.log(getString(R.string.log_update_check))
                        val info = runCatching { Updater.checkLatest() }.getOrNull()
                        when {
                            info != null && Updater.isNewer(info.version, appVer) -> {
                                AppLog.log(getString(R.string.log_update_found, info.version))
                                showSettings = false
                                updateUi = UpdateUi.Available(info)
                            }
                            info != null -> {
                                AppLog.log(getString(R.string.log_update_uptodate, info.version))
                                updateUi = UpdateUi.Idle
                                updateMsg = getString(R.string.update_uptodate, appVer)
                            }
                            else -> {
                                updateUi = UpdateUi.Idle
                                updateMsg = getString(R.string.update_check_error)
                            }
                        }
                    }
                }

                val downloadUpdate: (Updater.UpdateInfo) -> Unit = { info ->
                    scope.launch {
                        updateUi = UpdateUi.Downloading(0)
                        try {
                            val apk = Updater.downloadApk(applicationContext, info) { pct ->
                                updateUi = UpdateUi.Downloading(pct)
                            }
                            AppLog.log(getString(R.string.log_update_download_ok, humanSize(apk.length())))
                            updateUi = UpdateUi.Ready(apk)
                        } catch (e: Exception) {
                            AppLog.log(getString(R.string.log_update_fail, e.message ?: "?"), warn = true)
                            updateMsg = getString(R.string.update_error, e.message ?: "?")
                            updateUi = UpdateUi.Idle
                        }
                    }
                }

                val installUpdate: (File) -> Unit = { apk ->
                    val ok = Updater.installApk(this@MainActivity, apk)
                    if (ok) {
                        permsHint = false
                    } else {
                        // права нет: Updater сам открыл настройки; подсказка в диалоге,
                        // после возврата пользователь жмёт «Установить обновление» ещё раз
                        permsHint = true
                    }
                }

                Scaffold(
                    topBar = { TopBar(onSettings = { showSettings = true }) },
                    bottomBar = { BottomBar(tab, onSelect = { tab = it }) },
                ) { pad ->
                    Box(Modifier.padding(pad)) {
                        when (tab) {
                            0 -> SearchScreen(desktopSite)
                            1 -> GalleryScreen()
                            else -> LogScreen()
                        }
                    }
                }

                if (showSettings) {
                    SettingsDialog(
                        appVer = appVer,
                        updateBusy = updateUi is UpdateUi.Checking,
                        desktopSite = desktopSite,
                        onDesktopSite = { v ->
                            desktopSite = v
                            prefs.edit().putBoolean("desktop_site", v).apply()
                        },
                        onCheckUpdates = checkUpdates,
                        onDismiss = { showSettings = false },
                    )
                }

                when (val s = updateUi) {
                    is UpdateUi.Available -> UpdateAvailableDialog(
                        info = s.info,
                        installed = appVer,
                        onDownload = { downloadUpdate(s.info) },
                        onLater = { updateUi = UpdateUi.Idle },
                    )
                    is UpdateUi.Downloading -> DownloadingDialog(s.pct)
                    is UpdateUi.Ready -> ReadyDialog(
                        permsHint = permsHint,
                        onInstall = { installUpdate(s.file) },
                        onCancel = { updateUi = UpdateUi.Idle },
                    )
                    else -> {}
                }

                updateMsg?.let { msg ->
                    AlertDialog(
                        onDismissRequest = { updateMsg = null },
                        confirmButton = {
                            TextButton(onClick = { updateMsg = null }) {
                                Text(stringResource(R.string.ok))
                            }
                        },
                        title = { Text(stringResource(R.string.app_name)) },
                        text = { Text(msg) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(onSettings: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.app_name)) },
        actions = {
            IconButton(onClick = onSettings) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.settings),
                )
            }
        },
    )
}

@Composable
private fun BottomBar(current: Int, onSelect: (Int) -> Unit) {
    NavigationBar {
        NavigationBarItem(
            selected = current == 0,
            onClick = { onSelect(0) },
            icon = { Icon(Icons.Filled.Search, contentDescription = null) },
            label = { Text(stringResource(R.string.tab_search)) },
        )
        NavigationBarItem(
            selected = current == 1,
            onClick = { onSelect(1) },
            icon = { Icon(Icons.Filled.Download, contentDescription = null) },
            label = { Text(stringResource(R.string.tab_gallery)) },
        )
        NavigationBarItem(
            selected = current == 2,
            onClick = { onSelect(2) },
            icon = { Icon(Icons.Filled.List, contentDescription = null) },
            label = { Text(stringResource(R.string.tab_log)) },
        )
    }
}

@Composable
private fun SettingsDialog(
    appVer: String,
    updateBusy: Boolean,
    desktopSite: Boolean,
    onDesktopSite: (Boolean) -> Unit,
    onCheckUpdates: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        },
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.desktop_site),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(checked = desktopSite, onCheckedChange = onDesktopSite)
                }
                Text(
                    stringResource(R.string.language),
                    Modifier.padding(top = 12.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
                LanguageRow(tag = "", label = stringResource(R.string.lang_system))
                LanguageRow(tag = "ru", label = "Русский")
                LanguageRow(tag = "en", label = "English")
                LanguageRow(tag = "zh", label = "中文")

                HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.settings_version, appVer),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onCheckUpdates, enabled = !updateBusy) {
                        Text(
                            if (updateBusy) stringResource(R.string.update_checking)
                            else stringResource(R.string.check_updates)
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun LanguageRow(tag: String, label: String) {
    TextButton(onClick = {
        val locales = if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
        else LocaleListCompat.forLanguageTags(tag)
        AppCompatDelegate.setApplicationLocales(locales)
    }) { Text(label) }
}

/** Диалог «доступна новая версия»: Скачать / Позже. */
@Composable
private fun UpdateAvailableDialog(
    info: Updater.UpdateInfo,
    installed: String,
    onDownload: () -> Unit,
    onLater: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onLater,
        confirmButton = {
            TextButton(onClick = onDownload) { Text(stringResource(R.string.download)) }
        },
        dismissButton = {
            TextButton(onClick = onLater) { Text(stringResource(R.string.update_later)) }
        },
        title = { Text(stringResource(R.string.update_available_title)) },
        text = {
            Text(
                stringResource(
                    R.string.update_available_text,
                    info.version,
                    installed,
                    humanSize(info.sizeBytes),
                )
            )
        },
    )
}

/** Диалог скачивания с прогрессом; отмены нет — APK небольшой, окно закроется само. */
@Composable
private fun DownloadingDialog(pct: Int) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        title = { Text(stringResource(R.string.update_available_title)) },
        text = {
            Column {
                Text(stringResource(R.string.update_downloading, pct))
                LinearProgressIndicator(
                    progress = { pct / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
            }
        },
    )
}

/** Диалог «APK скачан»: Установить обновление / отмена; подсказка о праве установки. */
@Composable
private fun ReadyDialog(
    permsHint: Boolean,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            TextButton(onClick = onInstall) { Text(stringResource(R.string.update_install)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        },
        title = { Text(stringResource(R.string.update_ready_title)) },
        text = {
            Column {
                Text(stringResource(R.string.update_ready_text))
                if (permsHint) {
                    Text(
                        stringResource(R.string.update_perms_hint),
                        Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    )
}

/** Размер файла для строк интерфейса: «1.2 MB» / «800 KB». */
private fun humanSize(bytes: Long): String {
    return if (bytes >= 1024L * 1024) {
        String.format(Locale.getDefault(), "%.1f MB", bytes / 1048576.0)
    } else {
        String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    }
}

private const val AUTO_CHECK_INTERVAL_MS: Long = 24L * 60 * 60 * 1000
