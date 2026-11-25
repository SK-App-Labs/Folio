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

import com.folioledger.app.domain.util.BackupTimeFormatter

/**
 * Formats epoch timestamp into relative backup description (e.g. "Today, 2:30 PM", "Yesterday, ...", "3d ago").
 * Delegated to [BackupTimeFormatter.formatBackupRelativeTime] for domain cleanliness.
 */
fun formatBackupRelativeTime(epochMillis: Long?): String =
    BackupTimeFormatter.formatBackupRelativeTime(epochMillis)
