/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.res

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LocalizationParityAndXmlTest {

    private fun locateResDir(): File = listOf(
        File("src/main/res"),
        File("app/src/main/res"),
        File("../app/src/main/res"),
    ).firstOrNull { it.exists() && it.isDirectory }
        ?: error("Could not locate app/src/main/res directory from ${File("").absolutePath}")

    private fun parseStringsFile(file: File): Map<String, Pair<String, String?>> {
        val factory = DocumentBuilderFactory.newInstance()
        val doc = factory.newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val map = LinkedHashMap<String, Pair<String, String?>>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            val key = el.getAttribute("name")
            assertTrue("Empty string key in ${file.name}", key.isNotBlank())
            assertFalse("Duplicate string key '$key' in ${file.path}", map.containsKey(key))
            val formattedAttr = el.getAttribute("formatted").takeIf { it.isNotBlank() }
            map[key] = el.textContent to formattedAttr
        }
        return map
    }

    private fun extractFormatSpecifiers(text: String): List<String> {
        val regex = Regex("%(\\d+\\$)?[a-zA-Z]")
        return regex.findAll(text).map { it.value }.sorted().toList()
    }

    @Test
    fun allTwelveLocalesMatchBaseKeysAndFormatSpecifiers() {
        val resDir = locateResDir()
        val baseFiles = File(resDir, "values")
            .listFiles { f -> f.isFile && f.name.startsWith("strings") && f.name.endsWith(".xml") }
            ?.sortedBy { it.name }
            .orEmpty()
        assertTrue("Expected base strings*.xml files", baseFiles.size >= 10)

        val baseStrings = LinkedHashMap<String, Pair<String, String?>>()
        for (file in baseFiles) {
            val parsed = parseStringsFile(file)
            for ((k, v) in parsed) {
                assertFalse("Duplicate key '$k' across base values/ XML files", baseStrings.containsKey(k))
                baseStrings[k] = v
            }
        }

        val expectedLocales = listOf(
            "values-de", "values-es", "values-fr", "values-hi",
            "values-in", "values-it", "values-ja", "values-ko",
            "values-pt-rBR", "values-ta", "values-zh-rCN", "values-zh-rTW",
        )

        for (localeFolder in expectedLocales) {
            val localeFile = File(File(resDir, localeFolder), "strings.xml")
            assertTrue("Missing $localeFolder/strings.xml", localeFile.exists())
            val localeStrings = parseStringsFile(localeFile)

            val missingKeys = baseStrings.keys - localeStrings.keys
            val extraKeys = localeStrings.keys - baseStrings.keys
            assertEquals("Missing keys in $localeFolder/strings.xml", emptySet<String>(), missingKeys)
            assertEquals("Orphaned extra keys in $localeFolder/strings.xml", emptySet<String>(), extraKeys)

            for ((key, basePair) in baseStrings) {
                val locPair = localeStrings[key]
                assertNotNull(locPair)
                val (baseText, baseFormatted) = basePair
                val (locText, locFormatted) = locPair!!

                val baseSpecs = if (baseFormatted == "false") emptyList() else extractFormatSpecifiers(baseText)
                val locSpecs = if (locFormatted == "false") emptyList() else extractFormatSpecifiers(locText)
                assertEquals(
                    "Format specifier mismatch for key '$key' in $localeFolder/strings.xml",
                    baseSpecs,
                    locSpecs,
                )

                if (locText.contains('%') && locFormatted != "false") {
                    assertTrue(
                        "Literal '%' string '$key' in $localeFolder must either use valid %1\$s/%1\$d formatters or set formatted=\"false\"",
                        locSpecs.isNotEmpty(),
                    )
                }
            }
        }
    }
}
