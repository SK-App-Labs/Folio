/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/main/java/com/folioledger/app/data/notification/FolioNotificationManager.kt
package com.folioledger.app.data.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.folioledger.app.MainActivity
import com.folioledger.app.R
import com.folioledger.app.domain.model.BillDueReminderItem
import com.folioledger.app.domain.model.CardDueReminderItem
import com.folioledger.app.domain.model.NumberGroupingSystem
import com.folioledger.app.domain.model.SupportedCurrencies
import com.folioledger.app.domain.util.DailyLogReminderCalculator
import com.folioledger.app.domain.util.MoneyFormatterAndCalculator
import java.time.format.DateTimeFormatter
import java.util.Locale

class FolioNotificationManager(private val context: Context) {

    companion object {
        const val CHANNEL_CREDIT_CARDS = "folio_channel_credit_cards"
        const val CHANNEL_BILLS = "folio_channel_recurring_bills"
        const val CHANNEL_DAILY_LOG = "folio_channel_daily_log"

        const val ACTION_SETTLE_CARD = "com.folioledger.app.ACTION_SETTLE_CARD"
        const val ACTION_LOG_BILL = "com.folioledger.app.ACTION_LOG_BILL"
        const val ACTION_LOG_DAILY_TRANSACTION = "com.folioledger.app.ACTION_LOG_DAILY_TRANSACTION"
        const val ACTION_SNOOZE_DAILY_LOG = "com.folioledger.app.ACTION_SNOOZE_DAILY_LOG"

        const val EXTRA_NOTIFICATION_ACTION = "notification_action"
        const val EXTRA_ACCOUNT_ID = "account_id"
        const val EXTRA_RECURRING_RULE_ID = "recurring_rule_id"
        const val EXTRA_DUE_EPOCH_DAY = "extra_due_epoch_day"
        const val EXTRA_ORIGIN_EPOCH_DAY = "extra_origin_epoch_day"
        const val EXTRA_SNOOZE_MINUTES = "extra_snooze_minutes"
        const val EXTRA_SNOOZE_COUNT = "extra_snooze_count"

        const val NOTIFICATION_ID_CC_BASE = 70_000
        const val NOTIFICATION_ID_BILL_BASE = 80_000
        const val NOTIFICATION_ID_DAILY_LOG = 90_000
        const val REQ_DAILY_LOG_CONTENT = 90_000
        const val REQ_DAILY_LOG_ACTION = 90_001
        const val REQ_DAILY_LOG_SNOOZE = 90_002

        fun getCardNotificationId(accountId: Long): Int =
            NOTIFICATION_ID_CC_BASE + accountId.mod(10_000L).toInt()

        fun getBillNotificationId(ruleId: Long): Int =
            NOTIFICATION_ID_BILL_BASE + ruleId.mod(10_000L).toInt()

        private fun localizedDateFormatter(): DateTimeFormatter =
            DateTimeFormatter.ofPattern("dd MMM", Locale.getDefault())

        fun createNotificationChannels(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return

                val ccChannel = NotificationChannel(
                    CHANNEL_CREDIT_CARDS,
                    context.getString(R.string.notif_channel_cc_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.notif_channel_cc_desc)
                    enableLights(true)
                    enableVibration(true)
                }

                val billsChannel = NotificationChannel(
                    CHANNEL_BILLS,
                    context.getString(R.string.notif_channel_bills_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.notif_channel_bills_desc)
                }

                val dailyLogChannel = NotificationChannel(
                    CHANNEL_DAILY_LOG,
                    context.getString(R.string.notif_channel_daily_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.notif_channel_daily_desc)
                }

                notificationManager.createNotificationChannel(ccChannel)
                notificationManager.createNotificationChannel(billsChannel)
                notificationManager.createNotificationChannel(dailyLogChannel)
            }
        }

        fun hasNotificationPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }

        fun cancelCardNotification(context: Context, accountId: Long) {
            val notificationId = getCardNotificationId(accountId)
            NotificationManagerCompat.from(context).cancel(notificationId)
        }

        fun cancelBillNotification(context: Context, ruleId: Long) {
            val notificationId = getBillNotificationId(ruleId)
            NotificationManagerCompat.from(context).cancel(notificationId)
        }

        fun cancelDailyLogNotification(context: Context) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_DAILY_LOG)
        }
    }

    fun postDailyLogReminder(
        customMessage: String,
        snoozeMinutes: Int,
        originEpochDay: Long,
        snoozeCount: Int,
        hideAmountsOnLockScreen: Boolean
    ) {
        createNotificationChannels(context)
        if (!hasNotificationPermission(context)) return

        val body = customMessage.trim().ifBlank {
            context.getString(R.string.daily_log_default_message)
        }
        val logIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = ACTION_LOG_DAILY_TRANSACTION
            putExtra(EXTRA_NOTIFICATION_ACTION, ACTION_LOG_DAILY_TRANSACTION)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            REQ_DAILY_LOG_CONTENT,
            logIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val actionPendingIntent = PendingIntent.getActivity(
            context,
            REQ_DAILY_LOG_ACTION,
            logIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_DAILY_LOG)
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setContentTitle(context.getString(R.string.notif_daily_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(
                R.drawable.ic_shortcut_expense,
                context.getString(R.string.notif_daily_log_now),
                actionPendingIntent
            )

        val normalizedSnooze = DailyLogReminderCalculator.normalizeSnoozeMinutes(snoozeMinutes)
        if (snoozeCount < DailyLogReminderCalculator.MAX_SNOOZES_PER_DAY) {
            val snoozeIntent = Intent(context, DailyLogReminderActionReceiver::class.java).apply {
                action = ACTION_SNOOZE_DAILY_LOG
                putExtra(EXTRA_ORIGIN_EPOCH_DAY, originEpochDay)
                putExtra(EXTRA_SNOOZE_MINUTES, normalizedSnooze)
                putExtra(EXTRA_SNOOZE_COUNT, snoozeCount)
            }
            val snoozePendingIntent = PendingIntent.getBroadcast(
                context,
                REQ_DAILY_LOG_SNOOZE,
                snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val snoozeLabel = "Snooze ${DailyLogReminderCalculator.formatSnoozeLabel(normalizedSnooze, compact = true)}"
            builder.addAction(
                R.drawable.ic_notification_reminder,
                snoozeLabel,
                snoozePendingIntent
            )
        }

        if (hideAmountsOnLockScreen) {
            val publicNotification = NotificationCompat.Builder(context, CHANNEL_DAILY_LOG)
                .setSmallIcon(R.drawable.ic_notification_reminder)
                .setContentTitle(context.getString(R.string.notif_daily_public_title))
                .setContentText(context.getString(R.string.notif_daily_public_text))
                .build()
            builder.setPublicVersion(publicNotification)
        }

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_DAILY_LOG, builder.build())
    }

    fun postDueReminders(
        cardReminders: List<CardDueReminderItem>,
        billReminders: List<BillDueReminderItem>,
        hideAmounts: Boolean,
        grouping: NumberGroupingSystem
    ) {
        createNotificationChannels(context)
        if (!hasNotificationPermission(context)) return

        val managerCompat = NotificationManagerCompat.from(context)
        val dateFmt = localizedDateFormatter()

        // 1. Post Credit Card Due Reminders
        for (alert in cardReminders) {
            val notificationId = getCardNotificationId(alert.accountId)
            val spec = SupportedCurrencies.fromCode(alert.currencyCode)
            val formattedAmount = MoneyFormatterAndCalculator.formatMinorMoney(
                alert.amountDue,
                spec,
                grouping
            )

            val title = when {
                alert.isOverdue -> "Overdue: ${alert.accountName} Bill"
                alert.daysRemaining == 0L -> "Due Today: ${alert.accountName} Bill"
                alert.daysRemaining == 1L -> "${alert.accountName} Due Tomorrow"
                else -> "${alert.accountName} Due in ${alert.daysRemaining} Days"
            }

            val formattedDue = alert.dueDate.format(dateFmt)
            val body = if (hideAmounts) {
                "Due on $formattedDue. Settle now to preserve your grace period."
            } else {
                "Statement balance of $formattedAmount is due on $formattedDue."
            }

            // Settle Intent
            val settleIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                action = ACTION_SETTLE_CARD
                putExtra(EXTRA_NOTIFICATION_ACTION, ACTION_SETTLE_CARD)
                putExtra(EXTRA_ACCOUNT_ID, alert.accountId)
            }
            val settlePendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                settleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(context, CHANNEL_CREDIT_CARDS)
                .setSmallIcon(R.drawable.ic_notification_reminder)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(settlePendingIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .addAction(
                    R.drawable.ic_shortcut_transfer,
                    context.getString(R.string.notif_cc_settle_now),
                    settlePendingIntent
                )

            if (hideAmounts) {
                val publicNotification = NotificationCompat.Builder(context, CHANNEL_CREDIT_CARDS)
                    .setSmallIcon(R.drawable.ic_notification_reminder)
                    .setContentTitle(context.getString(R.string.notif_cc_public_title))
                    .setContentText(context.getString(R.string.notif_cc_public_text))
                    .build()
                builder.setPublicVersion(publicNotification)
            }

            val notification = builder.build()
            managerCompat.notify(notificationId, notification)
        }

        // 2. Post Recurring Bill Reminders
        for (bill in billReminders) {
            val notificationId = getBillNotificationId(bill.ruleId)
            val spec = SupportedCurrencies.fromCode(bill.currencyCode)
            val formattedAmount = MoneyFormatterAndCalculator.formatMinorMoney(
                bill.amount,
                spec,
                grouping
            )

            val isDueToday = bill.daysRemaining == 0L
            val title = if (isDueToday) {
                "Recurring Bill Logged: ${bill.title}"
            } else when (bill.daysRemaining) {
                1L -> "Upcoming Bill: ${bill.title} (Tomorrow)"
                else -> "Upcoming Bill: ${bill.title} (in ${bill.daysRemaining} days)"
            }

            val formattedDue = bill.dueDate.format(dateFmt)
            val body = if (isDueToday) {
                if (hideAmounts) {
                    "Auto-logged for ${bill.accountName} today."
                } else {
                    "$formattedAmount auto-logged for ${bill.accountName} today."
                }
            } else if (hideAmounts) {
                "Due from ${bill.accountName} on $formattedDue."
            } else {
                "$formattedAmount due from ${bill.accountName} on $formattedDue."
            }

            val billIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (!isDueToday) {
                    action = ACTION_LOG_BILL
                    putExtra(EXTRA_NOTIFICATION_ACTION, ACTION_LOG_BILL)
                    putExtra(EXTRA_RECURRING_RULE_ID, bill.ruleId)
                    putExtra(EXTRA_DUE_EPOCH_DAY, bill.dueDate.toEpochDay())
                }
            }
            val billPendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                billIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(context, CHANNEL_BILLS)
                .setSmallIcon(R.drawable.ic_notification_reminder)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(billPendingIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

            if (!isDueToday) {
                builder.addAction(
                    R.drawable.ic_shortcut_expense,
                    context.getString(R.string.notif_bill_log_payment),
                    billPendingIntent
                )
            }

            if (hideAmounts) {
                val publicNotification = NotificationCompat.Builder(context, CHANNEL_BILLS)
                    .setSmallIcon(R.drawable.ic_notification_reminder)
                    .setContentTitle(
                        context.getString(
                            if (isDueToday) R.string.notif_bill_public_logged_title else R.string.notif_bill_public_title
                        )
                    )
                    .setContentText(
                        context.getString(
                            if (isDueToday) R.string.notif_bill_public_logged_text else R.string.notif_bill_public_text
                        )
                    )
                    .build()
                builder.setPublicVersion(publicNotification)
            }

            val notification = builder.build()
            managerCompat.notify(notificationId, notification)
        }
    }
}
