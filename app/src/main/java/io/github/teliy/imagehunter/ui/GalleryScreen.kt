package io.github.teliy.imagehunter.ui

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.teliy.imagehunter.R
import io.github.teliy.imagehunter.core.AppLog
import io.github.teliy.imagehunter.core.WebDav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class GalleryItem(val id: Long, val uri: Uri, val name: String)

/**
 * Вкладка «Загрузки»: сетка скачанных картинок (Pictures/ImageHunter).
 * Права не нужны: свои записи в MediaStore приложение видит без разрешений.
 *
 * WebDAV: «Загрузить всё в WebDAV» отправляет на сервер картинки, которых там
 * ещё нет (id уже выгруженных хранятся локально; при смене сервера/папки
 * список сбрасывается). В превью картинки есть одиночная «Загрузить в WebDAV»
 * — всегда выгружает заново (перезаписывает файл на сервере).
 */
@Composable
fun GalleryScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var images by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<GalleryItem?>(null) }
    var davBusy by remember { mutableStateOf(false) }
    var davInfo by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) {
        images = withContext(Dispatchers.IO) { queryDownloads(context) }
    }

    fun uploadAll() {
        if (davBusy) return
        scope.launch {
            val cfg = WebDav.loadConfig(context)
            if (!cfg.isConfigured) {
                davInfo = context.getString(R.string.dav_not_configured)
                return@launch
            }
            val already = WebDav.uploadedIds(context)
            val pending = images.filter { it.id !in already }
            if (pending.isEmpty()) {
                davInfo = context.getString(R.string.dav_all_uploaded)
                return@launch
            }
            davBusy = true
            val r = WebDav.upload(
                context, cfg, pending.map { WebDav.DavFile(it.id, it.uri) }
            )
            davBusy = false
            reload++   // ids могли вырасти — пересчитаем список «чего не хватает»
            davInfo = context.getString(R.string.dav_done, r.ok, r.fail)
        }
    }

    fun uploadOne(item: GalleryItem) {
        if (davBusy) return
        scope.launch {
            val cfg = WebDav.loadConfig(context)
            if (!cfg.isConfigured) {
                davInfo = context.getString(R.string.dav_not_configured)
                return@launch
            }
            davBusy = true
            val r = WebDav.upload(context, cfg, listOf(WebDav.DavFile(item.id, item.uri)))
            davBusy = false
            davInfo = context.getString(R.string.dav_done, r.ok, r.fail)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { uploadAll() }, enabled = !davBusy) {
                Icon(
                    Icons.Filled.CloudUpload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.dav_upload_all))
            }
        }
        if (davBusy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        if (images.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.gallery_empty),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(images, key = { it.id }) { item ->
                    AsyncImage(
                        model = item.uri,
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clickable { preview = item },
                    )
                }
            }
        }
    }

    preview?.let { item ->
        AlertDialog(
            onDismissRequest = { preview = null },
            confirmButton = {
                TextButton(onClick = {
                    preview = null
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(item.uri, "image/*")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    )
                }) { Text(stringResource(R.string.open_image)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("image/*")
                        .putExtra(Intent.EXTRA_STREAM, item.uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, null))
                }) { Text(stringResource(R.string.share)) }
            },
            title = { Text(item.name) },
            text = {
                Column {
                    AsyncImage(
                        model = item.uri,
                        contentDescription = item.name,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = {
                            preview = null
                            uploadOne(item)
                        }) { Text(stringResource(R.string.dav_upload_one)) }
                        TextButton(onClick = {
                            preview = null
                            try {
                                context.contentResolver.delete(item.uri, null, null)
                                AppLog.log(context.getString(R.string.log_delete, item.name))
                            } catch (e: Exception) {
                                AppLog.log(e.message ?: e.toString(), warn = true)
                            }
                            reload++
                        }) { Text(stringResource(R.string.delete_image)) }
                    }
                }
            },
        )
    }

    davInfo?.let { msg ->
        AlertDialog(
            onDismissRequest = { davInfo = null },
            confirmButton = {
                TextButton(onClick = { davInfo = null }) { Text(stringResource(R.string.ok)) }
            },
            title = { Text(stringResource(R.string.app_name)) },
            text = { Text(msg) },
        )
    }
}

private fun queryDownloads(context: Context): List<GalleryItem> {
    val out = mutableListOf<GalleryItem>()
    val proj = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
    )
    val sel = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
    val args = arrayOf("Pictures/ImageHunter%")
    val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
    try {
        context.contentResolver
            .query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, sel, args, sort)
            ?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                    )
                    out.add(GalleryItem(id, uri, c.getString(nameCol) ?: "?"))
                }
            }
    } catch (e: Exception) {
        AppLog.log(e.message ?: e.toString(), warn = true)
    }
    return out
}
