/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.folioledger.app.ui.settings.sheets

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folioledger.app.ui.theme.LocalFolioSemanticColors

const val PRIVACY_POLICY_URL = "https://sites.google.com/view/folio-ledger-privacy/home"

fun openPrivacyPolicyWeb(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching {
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, "Could not open browser. Please visit:\n$PRIVACY_POLICY_URL", Toast.LENGTH_LONG).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyPolicyBottomSheet(
    onDismiss: () -> Unit,
    onOpenWeb: () -> Unit = {}
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val semanticColors = LocalFolioSemanticColors.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Security,
                        contentDescription = null,
                        tint = semanticColors.incomeEmerald,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Privacy Policy & Disclosures",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "100% Offline • Zero Telemetry • Local SQLite",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Hosted Policy Hero Card
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = semanticColors.incomeEmerald.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, semanticColors.incomeEmerald.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Official Hosted Privacy Policy",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Under Google Play's User Data Policy, our full active privacy policy is published online. Folio is architected from the ground up to never transmit or store your financial data on external servers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            if (onOpenWeb != {}) onOpenWeb() else openPrivacyPolicyWeb(context)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = semanticColors.incomeEmerald,
                            contentColor = MaterialTheme.colorScheme.surface
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Public,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Open Full Web Policy",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Detailed Policy Sections
            PrivacySectionItem(
                title = "1. 100% Local SQLite Device Storage",
                content = "Every account, balance, transaction, category, recurring rule, budget, and preference is saved exclusively inside local SQLite app-private storage on your physical device. Folio has zero remote cloud servers, zero web databases, and zero technical ability to access or view your ledger."
            )

            PrivacySectionItem(
                title = "2. Zero Analytics, Tracking & Telemetry",
                content = "Folio does not include any analytics SDKs (e.g. Firebase Analytics, Google Analytics), behavioral trackers, or advertising networks. We collect zero usage statistics, crash logs, device identifiers, or diagnostic telemetry."
            )

            PrivacySectionItem(
                title = "3. Zero Sensitive Android Permissions",
                content = "Folio operates without sensitive permissions. The app does NOT request access to your SMS messages, contacts, location, microphone, camera, or phone state. All transactions are logged manually by you or through voluntary local file imports."
            )

            PrivacySectionItem(
                title = "4. Google Play Billing (Tip Jar)",
                content = "Voluntary supporter contributions in the Tip Jar are processed directly by Google Play Billing via standard Google Play APIs. Folio never receives, processes, or stores your credit card numbers or payment details. Only an anonymous local purchase record is kept to unlock your supporter badge."
            )

            PrivacySectionItem(
                title = "5. Backups & Device Migration",
                content = "Automated rolling backups remain strictly in app-private storage. Public Downloads mirroring is disabled by default and requires explicit opt-in. Device-to-Device transfers use Android's encrypted native transfer mechanism without touching third-party servers."
            )

            PrivacySectionItem(
                title = "6. Complete Data Deletion & Ownership",
                content = "You maintain absolute ownership of your data. Using 'Reset All Ledger Data' in Settings or uninstalling the app permanently purges all financial records from your device immediately with zero residual copies."
            )

            PrivacySectionItem(
                title = "7. Contact Information",
                content = "If you have questions regarding this Privacy Policy or Folio's offline architecture, please email us directly at support.folioledger@gmail.com."
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Close")
                }
                Button(
                    onClick = {
                        if (onOpenWeb != {}) onOpenWeb() else openPrivacyPolicyWeb(context)
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1.5f)
                ) {
                    Text("Open Policy URL")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PrivacySectionItem(
    title: String,
    content: String
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }
    }
}
