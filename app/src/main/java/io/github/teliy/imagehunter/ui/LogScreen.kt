package io.github.teliy.imagehunter.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.teliy.imagehunter.R
import io.github.teliy.imagehunter.core.AppLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Вкладка «Журнал» — аналог журнала десктопной версии: события поисков,
 * распознавания плиток и загрузок, свежие сверху.
 */
@Composable
fun LogScreen() {
    val entries by AppLog.entries.collectAsState()
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.log_empty), style = MaterialTheme.typography.bodyLarge)
        }
    } else {
        val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
        LazyColumn(Modifier.fillMaxSize()) {
            items(entries.asReversed()) { entry ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text(
                        fmt.format(Date(entry.time)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        entry.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (entry.warn) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
