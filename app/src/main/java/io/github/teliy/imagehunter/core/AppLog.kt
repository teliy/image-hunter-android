package io.github.teliy.imagehunter.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Журнал событий — порт applog десктопной версии (упрощённый):
 * кольцевой буфер на 500 записей, показывается во вкладке «Журнал».
 * Сообщения локализуются вызывающим кодом (context.getString).
 */
object AppLog {

    data class Entry(
        val time: Long,
        val message: String,
        val warn: Boolean = false,
    )

    private const val MAX_ENTRIES = 500

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    /** Добавить запись; безопасно из любого потока. */
    @Synchronized
    fun log(message: String, warn: Boolean = false) {
        val list = _entries.value.toMutableList()
        list.add(Entry(System.currentTimeMillis(), message, warn))
        while (list.size > MAX_ENTRIES) {
            list.removeAt(0)
        }
        _entries.value = list
    }
}
