package com.example.dreamlinux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VesselProcStatParserTest {
    @Test
    fun parsesCommContainingSpacesAndParentheses() {
        // stat fields 3..22 after the closing ')' are:
        // state, ppid, pgrp, session, tty, tpgid, flags, minflt, cminflt,
        // majflt, cmajflt, utime, stime, cutime, cstime, priority, nice,
        // num_threads, itrealvalue, starttime.
        val raw = "4242 (bash (login) shell) S 100 4242 4242 0 -1 0 0 0 0 0 0 0 0 0 0 0 0 0 987654"
        val stat = VesselProcStatParser.parse(raw, 4242)!!

        assertEquals('S', stat.state)
        assertEquals(100, stat.parent)
        assertEquals(4242, stat.processGroup)
        assertEquals(4242, stat.session)
        assertEquals(987654L, stat.startedAtTicks)
    }

    @Test
    fun rejectsMismatchedPid() {
        val raw = "10 (bash) S 1 10 10 0 -1 0 0 0 0 0 0 0 0 0 0 0 0 0 123"
        assertNull(VesselProcStatParser.parse(raw, 11))
    }
}
