package com.rysal.happytv.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #187 item 6: a full refresh takes 2 to 3 minutes, so the dialog must not promise "a minute or two". */
class RefreshEverythingCopyTest {
    @Test
    fun body_saysAFewMinutes() {
        assertFalse(RefreshEverythingCopy.BODY.contains("a minute or two"))
        assertTrue(RefreshEverythingCopy.BODY.contains("a few minutes"))
        assertFalse(RefreshEverythingCopy.BODY.contains("!"))
    }
}
