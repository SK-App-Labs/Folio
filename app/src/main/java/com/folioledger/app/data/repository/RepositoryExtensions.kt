/*
 * Folio — Offline-First Personal Finance & Ledger for Android
 * Copyright (C) 2026 Sivasubramanian Kubendiran (SKAL Studios)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

// File: app/src/main/java/com/folioledger/app/data/repository/RepositoryExtensions.kt
package com.folioledger.app.data.repository

import kotlinx.coroutines.CancellationException

/**
 * Runs the given [block] and returns its result encapsulated in a [Result].
 *
 * Unlike standard [kotlin.runCatching], this implementation re-throws any [CancellationException]
 * so that Kotlin Coroutines cooperative cancellation and structured concurrency are strictly preserved.
 */
inline fun <R> runCatchingNonCancellation(block: () -> R): Result<R> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
