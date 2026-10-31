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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class GalleryItem(val id: Long, val uri: Uri, val name: String)

/**
 * Вкладка «Загрузки»: сетка скачанных картинок (Pictures/ImageHunter).
 * Права не нужны: свои записи в MediaStore приложение видит без разрешений.
 */
@Composable
fun GalleryScreen() {
    val context = LocalContext.current
    var images by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<GalleryItem?>(null) }

    LaunchedEffect(reload) {
        images = withContext(Dispatchers.IO) { queryDownloads(context) }
    }

    if (images.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
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
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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
