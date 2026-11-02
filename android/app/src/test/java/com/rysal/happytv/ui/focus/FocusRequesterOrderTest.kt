package com.rysal.happytv.ui.focus

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #53 / F-43: a `FocusRequester` only finds focus targets that come *after* it in
 * the modifier chain; behind a `focusable` / `clickable` it is a silent no-op.
 * This source scan fails when a misordered `focusRequester(` is added, and when a
 * frame-counted focus retry comes back.
 */
class FocusRequesterOrderTest {

    private val root = File("src/main/java/com/rysal/happytv")

    @Test
    fun `no focusRequester is placed after the focus target in its chain`() {
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                misorderedLines(file.readLines()).map { "${file.relativeTo(root)}:$it" }
            }
            .toList()
        assertTrue("focusRequester placed after a focusable/clickable: $offenders", offenders.isEmpty())
    }

    @Test
    fun `no focus retry is counted in frames`() {
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""RETRY_FRAMES""").containsMatchIn(it.readText()) }
            .map { it.relativeTo(root).toString() }
            .toList()
        assertTrue("frame-counted focus retries (use awaitFocus / requestWhenAttached): $offenders", offenders.isEmpty())
    }

    @Test
    fun `the scan flags a misordered chain and accepts the right one`() {
        val bad = """
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .htvClickableSurface(radiusPx = 4, background = c, onClick = {})
                    .then(if (r != null) Modifier.focusRequester(r) else Modifier),
            )
        """.trimIndent().lines()
        assertEquals(listOf(5), misorderedLines(bad))

        val good = """
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .then(if (r != null) Modifier.focusRequester(r) else Modifier)
                    .htvClickableSurface(radiusPx = 4, background = c, onClick = {}),
            )
            Box(Modifier.clickable {})
            Box(Modifier.focusRequester(r))
        """.trimIndent().lines()
        assertTrue(misorderedLines(good).isEmpty())
    }

    private val focusTargets = listOf(
        ".focusable(", ".clickable(", ".htvClickable", ".combinedClickable(", ".toggleable(",
        ".selectable(", ".focusTarget(", ".focusableRow(", ".clickableRow(",
    )

    /** 1-based line numbers of `focusRequester(` calls that follow a focus target in their chain. */
    private fun misorderedLines(lines: List<String>): List<Int> {
        val found = mutableListOf<Int>()
        lines.forEachIndexed { index, line ->
            if (!line.contains("focusRequester(") || line.trimStart().startsWith("//") ||
                line.trimStart().startsWith("import") || line.trimStart().startsWith("*")
            ) {
                return@forEachIndexed
            }
            // The chain head is the nearest line above that starts with Modifier / modifier
            // (a continuation line starts with `.`, `)`, `if`, `else`, or is the call's own
            // `.then(` wrapper).
            var start = index
            val limit = (index - 40).coerceAtLeast(0)
            while (start > limit && !startsChain(lines[start])) start--
            val before = buildString {
                for (i in start until index) appendLine(stripComment(lines[i]))
                append(stripComment(line).substringBefore("focusRequester("))
            }
            if (focusTargets.any { before.contains(it) }) found += index + 1
        }
        return found
    }

    private fun startsChain(line: String): Boolean {
        val t = line.trim().removePrefix("modifier = ").removePrefix("modifier=").trim()
        return t.startsWith("Modifier") || t.startsWith("modifier") ||
            t.contains("Modifier.") && !t.startsWith(".") || t.startsWith("holdReveal")
    }

    private fun stripComment(line: String): String = line.substringBefore("//")
}
