package com.rysal.happytv.ui.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Where the D-pad goes when a removal empties the list: the rail, or the empty state's own action. */
enum class EmptyFocus { Rail, EmptyState }

/** What a removal resolved to once the list dropped the removed key. */
sealed interface RemovalFocus<out K> {
    /** The key that now sits where the removed one was (the one before it when it was last). */
    data class Target<K>(val key: K) : RemovalFocus<K>

    /** Nothing is left in the list. */
    data object ListEmpty : RemovalFocus<Nothing>
}

/**
 * The pure rule behind [rememberFocusAfterRemoval] (#242, rules of #228, #230, #232). [remaining] is
 * the list as it is now; returns null while it still holds [removedKey] (the Room emission is async,
 * so the list has not reflected the removal yet), [RemovalFocus.ListEmpty] when it is empty, else the
 * key at [removedIndex] (the item that took the removed one's place), or the last key when the
 * removed item was last.
 */
fun <K> resolveFocusAfterRemoval(removedKey: K, removedIndex: Int, remaining: List<K>): RemovalFocus<K>? = when {
    removedKey in remaining -> null
    remaining.isEmpty() -> RemovalFocus.ListEmpty
    else -> RemovalFocus.Target(remaining[removedIndex.coerceIn(0, remaining.size - 1)])
}

/**
 * Focus after removal for one list. Call [removed] when the user removes an item, then:
 * - [focusKey] becomes the key to focus once the list has dropped the item (put your focus requester
 *   on that row and request it; call [focusHandled] when done);
 * - [emptiedTo] becomes the [EmptyFocus] given to [removed] when the list is empty afterwards.
 *
 * A removal the list never reflects (the row stays, as with "Show hidden channels") is dropped after
 * [REMOVAL_REFLECT_TIMEOUT_MS], so it cannot fire later on an unrelated change. Use [focusNeighbourOf]
 * when the item stays in the list.
 */
@Stable
class FocusAfterRemoval<K> internal constructor(
    private val keysNow: () -> List<K>,
    internal val readyNow: () -> Boolean,
) {
    internal var pending by mutableStateOf<Pending<K>?>(null)

    var focusKey: K? by mutableStateOf(null)
        private set
    var emptiedTo: EmptyFocus? by mutableStateOf(null)
        private set

    internal class Pending<K>(val key: K, val index: Int, val whenEmpty: EmptyFocus)

    /** Records the removal of [key] from the list as it is now. False when [key] is not in it. */
    fun removed(key: K, whenEmpty: EmptyFocus = EmptyFocus.Rail): Boolean {
        val index = keysNow().indexOf(key)
        if (index < 0) return false
        focusKey = null
        emptiedTo = null
        pending = Pending(key, index, whenEmpty)
        return true
    }

    /**
     * For an item that leaves the user's reach but stays in the list: focus goes at once to the next
     * row, else the one before it. False when there is no other row (the caller picks the fallback).
     */
    fun focusNeighbourOf(key: K): Boolean {
        val keys = keysNow()
        val target = indexAfterRemoval(keys.indexOf(key), keys.size)?.let { keys[it] } ?: return false
        pending = null
        emptiedTo = null
        focusKey = target
        return true
    }

    fun focusHandled() {
        focusKey = null
    }

    internal fun reset() {
        pending = null
        focusKey = null
        emptiedTo = null
    }

    internal fun keys(): List<K> = keysNow()

    internal fun resolved(p: Pending<K>, r: RemovalFocus<K>) {
        if (pending !== p) return
        when (r) {
            is RemovalFocus.Target -> focusKey = r.key
            RemovalFocus.ListEmpty -> emptiedTo = p.whenEmpty
        }
        pending = null
    }

    internal fun expired(p: Pending<K>) {
        if (pending === p) pending = null
    }
}

/**
 * One holder for "where does focus go after this item leaves the list" (#242). It records the removed
 * key and its index, waits until [items] no longer holds the key and [ready] is true (not loading),
 * then publishes [FocusAfterRemoval.focusKey] or [FocusAfterRemoval.emptiedTo]. A change of [resetKey]
 * (another category) drops a removal still waiting. Nothing is copied or compared per recomposition:
 * the key list is only built when a removal is recorded and while one is pending.
 */
@Composable
fun <T, K> rememberFocusAfterRemoval(
    items: List<T>,
    keyOf: (T) -> K,
    ready: Boolean = true,
    resetKey: Any? = null,
): FocusAfterRemoval<K> {
    val itemsNow = rememberUpdatedState(items)
    val keyOfNow = rememberUpdatedState(keyOf)
    val readyNow = rememberUpdatedState(ready)
    val holder = remember {
        FocusAfterRemoval(
            keysNow = { itemsNow.value.map(keyOfNow.value) },
            readyNow = { readyNow.value },
        )
    }
    LaunchedEffect(resetKey) { holder.reset() }
    LaunchedEffect(holder.pending) {
        val p = holder.pending ?: return@LaunchedEffect
        val result = withTimeoutOrNull(REMOVAL_REFLECT_TIMEOUT_MS) {
            snapshotFlow {
                if (holder.readyNow()) resolveFocusAfterRemoval(p.key, p.index, holder.keys()) else null
            }.first { it != null }
        }
        if (result != null) holder.resolved(p, result) else holder.expired(p)
    }
    return holder
}

/**
 * Where focus goes when the focused item leaves a list (#114): the item that
 * takes its place (the next one), else the one before it when it was last, else
 * nothing when the list is empty afterwards. [removedIndex] and [size] describe
 * the list as it was *before* the removal; the result is an index into that
 * same list, so the caller can read the target's key before the data changes.
 */
fun indexAfterRemoval(removedIndex: Int, size: Int): Int? = when {
    removedIndex !in 0 until size -> null
    removedIndex + 1 < size -> removedIndex + 1
    removedIndex - 1 >= 0 -> removedIndex - 1
    else -> null
}

/**
 * [indexAfterRemoval] for a list with rows that cannot take focus (headers,
 * dividers): the nearest [focusable] item after [removedIndex], else the nearest
 * one before it, else null. Reads the pre-removal list.
 */
fun <T> List<T>.focusTargetAfterRemoval(removedIndex: Int, focusable: (T) -> Boolean = { true }): T? {
    if (removedIndex !in indices) return null
    for (i in removedIndex + 1 until size) if (focusable(this[i])) return this[i]
    for (i in removedIndex - 1 downTo 0) if (focusable(this[i])) return this[i]
    return null
}
