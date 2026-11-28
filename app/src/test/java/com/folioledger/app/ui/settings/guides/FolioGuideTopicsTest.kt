/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.settings.guides

import com.folioledger.app.ui.settings.sheets.PRIVACY_POLICY_URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolioGuideTopicsTest {

    @Test
    fun `all guide topic IDs are unique and non-blank`() {
        val topics = FolioGuideTopics.allTopics
        assertTrue("Topics list should not be empty", topics.isNotEmpty())

        val ids = topics.map { it.id }
        val uniqueIds = ids.toSet()
        assertEquals("Duplicate guide topic IDs found", ids.size, uniqueIds.size)
        assertTrue("All topic IDs must be non-blank", ids.all { it.isNotBlank() })
    }

    @Test
    fun `every guide topic has title, summary, and non-empty bullet points`() {
        for (topic in FolioGuideTopics.allTopics) {
            assertTrue("Topic ${topic.id} has blank title", topic.title.isNotBlank())
            assertTrue("Topic ${topic.id} has blank summary", topic.summary.isNotBlank())
            assertTrue("Topic ${topic.id} has empty bullet points", topic.bulletPoints.isNotEmpty())
            assertTrue(
                "Topic ${topic.id} has blank bullet point",
                topic.bulletPoints.all { it.isNotBlank() },
            )
        }
    }

    @Test
    fun `every specific guide category tab has at least one topic`() {
        val specificCategories = GuideCategoryTab.entries.filter { it != GuideCategoryTab.ALL }
        for (category in specificCategories) {
            val count = FolioGuideTopics.allTopics.count { it.category == category }
            assertTrue("Category $category should have at least one topic", count > 0)
        }
    }

    @Test
    fun `privacy and offline FAQ topic includes official hosted policy URL`() {
        val topic = FolioGuideTopics.allTopics.firstOrNull { it.id == "faq_privacy_offline" }
        assertNotNull("Privacy FAQ topic must exist", topic)
        assertTrue(
            "Privacy FAQ must contain official privacy policy URL",
            topic!!.bulletPoints.any { it.contains(PRIVACY_POLICY_URL) },
        )
    }

    @Test
    fun `privacy policy URL constant is valid HTTPS url`() {
        assertEquals("https://sites.google.com/view/folio-ledger-privacy/home", PRIVACY_POLICY_URL)
        assertTrue(PRIVACY_POLICY_URL.startsWith("https://"))
    }
}
