package com.rysal.happytv.ui.focus

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** #242: the shared focus-after-removal holder. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class FocusAfterRemovalHolderTest {

    @get:Rule
    val compose = createComposeRule()

    private var items by mutableStateOf(listOf("a", "b", "c", "d"))
    private var ready by mutableStateOf(true)
    private var scope by mutableStateOf("one")
    private lateinit var holder: FocusAfterRemoval<String>

    private fun setContent() {
        compose.setContent {
            holder = rememberFocusAfterRemoval(items, { it }, ready = ready, resetKey = scope)
        }
        compose.waitForIdle()
    }

    private fun drop(key: String) {
        items = items - key
        compose.waitForIdle()
    }

    @Test fun middleItemHandsFocusToTheOneThatTookItsPlace() {
        setContent()
        compose.runOnIdle { holder.removed("b") }
        drop("b")
        assertEquals("c", holder.focusKey)
        assertNull(holder.emptiedTo)
    }

    @Test fun firstItemHandsFocusToTheNextOne() {
        setContent()
        compose.runOnIdle { holder.removed("a") }
        drop("a")
        assertEquals("b", holder.focusKey)
    }

    @Test fun lastItemHandsFocusToTheOneBefore() {
        setContent()
        compose.runOnIdle { holder.removed("d") }
        drop("d")
        assertEquals("c", holder.focusKey)
    }

    @Test fun onlyItemEmptiesTheListAndReportsWhereFocusGoes() {
        items = listOf("a")
        setContent()
        compose.runOnIdle { holder.removed("a", EmptyFocus.EmptyState) }
        drop("a")
        assertNull(holder.focusKey)
        assertEquals(EmptyFocus.EmptyState, holder.emptiedTo)
    }

    @Test fun onlyItemGoesToTheRailByDefault() {
        items = listOf("a")
        setContent()
        compose.runOnIdle { holder.removed("a") }
        drop("a")
        assertEquals(EmptyFocus.Rail, holder.emptiedTo)
    }

    @Test fun aRemovalTheListReflectsLateWaitsForIt() {
        setContent()
        compose.runOnIdle { holder.removed("b") }
        compose.waitForIdle()
        // The list still holds "b" (the Room emission has not arrived): nothing resolves yet.
        assertNull(holder.focusKey)
        assertNull(holder.emptiedTo)
        // An unrelated emission that still has "b" changes nothing either.
        items = listOf("a", "b", "c", "d", "e")
        compose.waitForIdle()
        assertNull(holder.focusKey)
        drop("b")
        assertEquals("c", holder.focusKey)
    }

    @Test fun nothingResolvesWhileTheListIsLoading() {
        setContent()
        ready = false
        compose.waitForIdle()
        compose.runOnIdle { holder.removed("b") }
        drop("b")
        assertNull(holder.focusKey)
        ready = true
        compose.waitForIdle()
        assertEquals("c", holder.focusKey)
    }

    @Test fun focusHandledClearsTheTarget() {
        setContent()
        compose.runOnIdle { holder.removed("b") }
        drop("b")
        compose.runOnIdle { holder.focusHandled() }
        assertNull(holder.focusKey)
    }

    @Test fun changingTheListScopeDropsAWaitingRemoval() {
        setContent()
        compose.runOnIdle { holder.removed("b") }
        scope = "two"
        compose.waitForIdle()
        drop("b")
        assertNull(holder.focusKey)
    }

    @Test fun aRemovalTheListNeverReflectsIsForgottenAndDoesNotFireLater() {
        // "Show hidden channels": the row stays in the list after the hide.
        setContent()
        compose.runOnIdle { holder.removed("b") }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(REMOVAL_REFLECT_TIMEOUT_MS + 1_000)
        compose.waitForIdle()
        assertNull(holder.focusKey)
        // Later the list drops "b" for an unrelated reason: focus must not move.
        drop("b")
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertNull(holder.focusKey)
        assertNull(holder.emptiedTo)
    }

    @Test fun anItemThatStaysInTheListHandsFocusToItsNeighbourAtOnce() {
        setContent()
        compose.runOnIdle { assertEquals(true, holder.focusNeighbourOf("b")) }
        assertEquals("c", holder.focusKey)
        compose.runOnIdle { holder.focusHandled(); assertEquals(true, holder.focusNeighbourOf("d")) }
        assertEquals("c", holder.focusKey)
        // And nothing is left pending to fire when the list changes later.
        drop("b")
        assertEquals("c", holder.focusKey)
    }

    @Test fun aLoneItemThatStaysHasNoNeighbour() {
        items = listOf("a")
        setContent()
        compose.runOnIdle { assertEquals(false, holder.focusNeighbourOf("a")) }
        assertNull(holder.focusKey)
    }

    @Test fun removingAKeyNotInTheListRecordsNothing() {
        setContent()
        compose.runOnIdle { assertEquals(false, holder.removed("zzz")) }
    }

    @Test fun theRuleIsPureForALateList() {
        assertNull(resolveFocusAfterRemoval("b", 1, listOf("a", "b", "c")))
        assertEquals(RemovalFocus.Target("c"), resolveFocusAfterRemoval("b", 1, listOf("a", "c")))
        assertEquals(RemovalFocus.Target("c"), resolveFocusAfterRemoval("d", 3, listOf("a", "b", "c")))
        assertEquals(RemovalFocus.ListEmpty, resolveFocusAfterRemoval("a", 0, emptyList<String>()))
    }
}
