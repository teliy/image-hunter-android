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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import io.github.teliy.imagehunter.core.Injector
import io.github.teliy.imagehunter.ui.GalleryScreen
import io.github.teliy.imagehunter.ui.LogScreen
import io.github.teliy.imagehunter.ui.SearchScreen
import io.github.teliy.imagehunter.ui.theme.ImageHunterTheme

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Injector.init(applicationContext)
        val prefs = getSharedPreferences("imagehunter", MODE_PRIVATE)

        setContent {
            ImageHunterTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                var showSettings by rememberSaveable { mutableStateOf(false) }
                var desktopSite by rememberSaveable { mutableStateOf(prefs.getBoolean("desktop_site", false)) }

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
                        desktopSite = desktopSite,
                        onDesktopSite = { v ->
                            desktopSite = v
                            prefs.edit().putBoolean("desktop_site", v).apply()
                        },
                        onDismiss = { showSettings = false },
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
    desktopSite: Boolean,
    onDesktopSite: (Boolean) -> Unit,
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
