package com.example.dreamlinux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VesselTerminalTabsTest {
    @Test
    fun reusesDisplayNumbersButNeverSessionIds() {
        val tabs = VesselTerminalTabs<String>()
        val first = tabs.add("a")
        val second = tabs.add("b")
        assertEquals(1, first.number)
        assertEquals(2, second.number)

        tabs.remove(first.id)
        val third = tabs.add("c")

        assertEquals(1, third.number)
        assertTrue(third.id > second.id)
    }

    @Test
    fun closeFailureKeepsTabAndSelection() {
        val tabs = VesselTerminalTabs<String>()
        val first = tabs.add("a")
        val second = tabs.add("b")
        assertTrue(tabs.select(first.id))
        assertTrue(tabs.beginClose(first.id))
        assertFalse(tabs.beginClose(first.id))

        tabs.closeFailed(first.id)

        assertEquals(first.id, tabs.current()?.id)
        assertFalse(tabs.find(first.id)?.closing ?: true)
        assertEquals(2, tabs.snapshot().size)
        assertEquals(second.id, tabs.snapshot()[1].id)
    }

    @Test
    fun removingSelectedPicksAdjacentTerminal() {
        val tabs = VesselTerminalTabs<String>()
        val first = tabs.add("a")
        val second = tabs.add("b")
        val third = tabs.add("c")
        tabs.select(second.id)

        tabs.remove(second.id)

        assertEquals(third.id, tabs.current()?.id)
        assertEquals(listOf(first.id, third.id), tabs.snapshot().map { it.id })
    }
}
