package com.rysal.happytv.ui.focus

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
