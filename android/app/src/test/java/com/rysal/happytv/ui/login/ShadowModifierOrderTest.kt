package com.rysal.happytv.ui.login

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #164: on a rounded surface `.shadow()` must precede `.clip()` and `.background()`. A clip above
 * the shadow erases it, and the leftover paints as a hard-edged inner rectangle inside the
 * rounded surface. Pins the order on every login surface that draws one.
 */
class ShadowModifierOrderTest {

    private fun source(name: String): String {
        val roots = listOf("src/main/java", "app/src/main/java")
        val file = roots.map { File(it, "com/rysal/happytv/ui/login/$name") }.first { it.exists() }
        return file.readText()
    }

    private fun assertShadowBeforeClipAndFill(name: String, marker: String) {
        val text = source(name)
        val start = text.indexOf(marker)
        assertTrue("$name: marker not found", start >= 0)
        val shadow = text.indexOf(".shadow(", start)
        assertTrue("$name: no .shadow( after the marker", shadow >= 0)
        val before = text.substring(start, shadow)
        assertTrue("$name: .clip( precedes .shadow(", !before.contains(".clip("))
        assertTrue("$name: .background( precedes .shadow(", !before.contains(".background("))
        assertTrue("$name: no .clip( after the shadow", text.indexOf(".clip(", shadow) > shadow)
        assertTrue("$name: no .background( after the shadow", text.indexOf(".background(", shadow) > shadow)
    }

    @Test
    fun cleartextSheetDrawsShadowBeforeClipAndFill() =
        assertShadowBeforeClipAndFill("CleartextSheet.kt", "modifier = modifier\n            .width(d.px(SHEET_WIDTH_PX))")

    @Test
    fun urlFormatsDialogDrawsShadowBeforeClipAndFill() =
        assertShadowBeforeClipAndFill("UrlFormatsDialog.kt", "modifier = modifier\n            .width(d.px(DIALOG_WIDTH_PX))")

    @Test
    fun signInCardDrawsShadowBeforeClipAndFill() =
        assertShadowBeforeClipAndFill("LoginScreen.kt", ".width(d.px(CARD_WIDTH_PX))")
}
