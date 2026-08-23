package com.emirrkls.phokarta.core.data

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight signal that the Activity feed should reload page 0
 * after the current user publishes a public visit.
 */
@Singleton
class ActivityFeedInvalidator @Inject constructor() {
    private val dirty = AtomicBoolean(false)

    fun markDirty() {
        dirty.set(true)
    }

    fun consume(): Boolean = dirty.getAndSet(false)
}
