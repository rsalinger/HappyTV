package com.rysal.happytv.backup

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node

/** #19: the backup rules are include-only and name just the household file; the manifest wires them. */
class BackupRulesTest {

    private val res = File("src/main/res/xml")

    private fun root(name: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, name)).documentElement

    private fun children(e: Element): List<Element> =
        (0 until e.childNodes.length).map { e.childNodes.item(it) }.filter { it.nodeType == Node.ELEMENT_NODE }.map { it as Element }

    private fun assertOnlyHouseholdFile(parent: Element) {
        val rules = children(parent)
        assertEquals("exactly one rule in ${parent.tagName}", 1, rules.size)
        val rule = rules.single()
        assertEquals("include", rule.tagName)
        assertEquals("file", rule.getAttribute("domain"))
        assertEquals(HouseholdBackup.FILE_NAME, rule.getAttribute("path"))
    }

    @Test
    fun `full backup content on Android 11 and older includes only the household file`() {
        val root = root("backup_rules.xml")
        assertEquals("full-backup-content", root.tagName)
        assertOnlyHouseholdFile(root)
    }

    @Test
    fun `data extraction rules include only the household file for cloud backup and device transfer`() {
        val root = root("data_extraction_rules.xml")
        assertEquals("data-extraction-rules", root.tagName)
        val sections = children(root)
        assertEquals(listOf("cloud-backup", "device-transfer"), sections.map { it.tagName })
        sections.forEach(::assertOnlyHouseholdFile)
    }

    @Test
    fun `the manifest points at both rule files, the backup agent and full backup only`() {
        val app = children(
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
                .parse(File("src/main/AndroidManifest.xml")).documentElement,
        ).first { it.tagName == "application" }
        val ns = "http://schemas.android.com/apk/res/android"
        assertEquals("@xml/data_extraction_rules", app.getAttributeNS(ns, "dataExtractionRules"))
        assertEquals("@xml/backup_rules", app.getAttributeNS(ns, "fullBackupContent"))
        assertEquals("true", app.getAttributeNS(ns, "fullBackupOnly"))
        assertEquals("true", app.getAttributeNS(ns, "allowBackup"))
        val agent = app.getAttributeNS(ns, "backupAgent")
        assertEquals(".backup.HappyTvBackupAgent", agent)
        assertTrue(
            File("src/main/java/com/rysal/happytv/backup/HappyTvBackupAgent.kt").readText().contains("class HappyTvBackupAgent : BackupAgent()"),
        )
    }
}
