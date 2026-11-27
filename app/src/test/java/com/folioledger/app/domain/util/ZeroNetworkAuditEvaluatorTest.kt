/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.domain.util

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZeroNetworkAuditEvaluatorTest {

    @Test
    fun `evaluate returns VERIFIED for Folio optional offline permissions`() {
        val report = ZeroNetworkAuditEvaluator.evaluate(
            listOf(
                "android.permission.POST_NOTIFICATIONS",
                "com.android.vending.BILLING"
            )
        )

        assertEquals(ZeroNetworkAuditStatus.VERIFIED, report.status)
        assertTrue(report.isZeroNetworkVerified)
        assertFalse(report.hasInternetPermission)
        assertFalse(report.hasNetworkStatePermission)
    }

    @Test
    fun `evaluate returns VERIFIED for null and empty permission collections`() {
        val nullReport = ZeroNetworkAuditEvaluator.evaluate(null)
        val emptyReport = ZeroNetworkAuditEvaluator.evaluate(emptyList())

        assertEquals(ZeroNetworkAuditStatus.VERIFIED, nullReport.status)
        assertTrue(nullReport.isZeroNetworkVerified)
        assertFalse(nullReport.hasInternetPermission)
        assertFalse(nullReport.hasNetworkStatePermission)

        assertEquals(ZeroNetworkAuditStatus.VERIFIED, emptyReport.status)
        assertTrue(emptyReport.isZeroNetworkVerified)
        assertFalse(emptyReport.hasInternetPermission)
        assertFalse(emptyReport.hasNetworkStatePermission)
    }

    @Test
    fun `evaluate fails closed when INTERNET permission is detected`() {
        val exactReport = ZeroNetworkAuditEvaluator.evaluate(
            listOf(
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.INTERNET"
            )
        )
        val noisyReport = ZeroNetworkAuditEvaluator.evaluate(
            listOf("  ANDROID.PERMISSION.INTERNET  ")
        )

        assertEquals(ZeroNetworkAuditStatus.NETWORK_PERMISSION_DETECTED, exactReport.status)
        assertFalse(exactReport.isZeroNetworkVerified)
        assertTrue(exactReport.hasInternetPermission)
        assertFalse(exactReport.hasNetworkStatePermission)

        assertEquals(ZeroNetworkAuditStatus.NETWORK_PERMISSION_DETECTED, noisyReport.status)
        assertFalse(noisyReport.isZeroNetworkVerified)
        assertTrue(noisyReport.hasInternetPermission)
        assertFalse(noisyReport.hasNetworkStatePermission)
    }

    @Test
    fun `evaluate fails closed when ACCESS_NETWORK_STATE permission is detected`() {
        val report = ZeroNetworkAuditEvaluator.evaluate(
            listOf(
                "com.android.vending.BILLING",
                "android.permission.ACCESS_NETWORK_STATE"
            )
        )

        assertEquals(ZeroNetworkAuditStatus.NETWORK_PERMISSION_DETECTED, report.status)
        assertFalse(report.isZeroNetworkVerified)
        assertFalse(report.hasInternetPermission)
        assertTrue(report.hasNetworkStatePermission)
    }

    @Test
    fun `AndroidManifest removes INTERNET and ACCESS_NETWORK_STATE via tools node remove`() {
        val manifestFile = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml")
        ).firstOrNull { it.isFile }
            ?: error("Could not locate AndroidManifest.xml from ${File(".").absolutePath}")

        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val document = factory.newDocumentBuilder().parse(manifestFile)
        val permissions = document.getElementsByTagName("uses-permission")

        val androidNs = "http://schemas.android.com/apk/res/android"
        val toolsNs = "http://schemas.android.com/tools"
        val nodeByPermission = mutableMapOf<String, String>()

        for (index in 0 until permissions.length) {
            val element = permissions.item(index) as? org.w3c.dom.Element ?: continue
            val name = element.getAttributeNS(androidNs, "name")
            val toolsNode = element.getAttributeNS(toolsNs, "node")
            if (name.isNotBlank()) {
                nodeByPermission[name] = toolsNode
            }
        }

        assertEquals("remove", nodeByPermission["android.permission.INTERNET"])
        assertEquals("remove", nodeByPermission["android.permission.ACCESS_NETWORK_STATE"])
    }
}
