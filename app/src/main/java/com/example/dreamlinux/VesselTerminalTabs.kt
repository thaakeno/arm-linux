package com.example.dreamlinux

/**
 * Process-local terminal registry. Internal IDs never repeat; display numbers
 * reuse the first gap only after a tab has really been removed.
 */
internal class VesselTerminalTabs<T> {
    internal data class Tab<T>(
        val id: Long,
        val number: Int,
        val value: T,
        val closing: Boolean,
    )

    private data class MutableTab<T>(
        val id: Long,
        val number: Int,
        val value: T,
        var closing: Boolean = false,
    )

    private val tabs = ArrayList<MutableTab<T>>()
    private var nextId = 1L
    private var selectedId = 0L

    @Synchronized
    fun add(value: T): Tab<T> {
        val occupied = tabs.mapTo(HashSet()) { it.number }
        var number = 1
        while (number in occupied) number++
        val tab = MutableTab(nextId++, number, value)
        tabs += tab
        selectedId = tab.id
        return tab.snapshot()
    }

    @Synchronized
    fun snapshot(): List<Tab<T>> = tabs.map { it.snapshot() }

    @Synchronized
    fun current(): Tab<T>? = tabs.firstOrNull { it.id == selectedId }?.snapshot()

    @Synchronized
    fun find(id: Long): Tab<T>? = tabs.firstOrNull { it.id == id }?.snapshot()

    @Synchronized
    fun select(id: Long): Boolean {
        if (tabs.none { it.id == id }) return false
        selectedId = id
        return true
    }

    @Synchronized
    fun beginClose(id: Long): Boolean {
        val tab = tabs.firstOrNull { it.id == id } ?: return false
        if (tab.closing) return false
        tab.closing = true
        return true
    }

    @Synchronized
    fun closeFailed(id: Long) {
        tabs.firstOrNull { it.id == id }?.closing = false
    }

    @Synchronized
    fun remove(id: Long): Tab<T>? {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return null
        val removed = tabs.removeAt(index)
        if (selectedId == id) {
            selectedId = if (tabs.isEmpty()) 0L else tabs[minOf(index, tabs.lastIndex)].id
        }
        return removed.snapshot()
    }

    @Synchronized
    fun size(): Int = tabs.size

    private fun MutableTab<T>.snapshot() = Tab(id, number, value, closing)
}
