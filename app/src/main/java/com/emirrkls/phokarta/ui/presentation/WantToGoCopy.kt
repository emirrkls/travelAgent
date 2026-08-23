package com.emirrkls.phokarta.ui.presentation

/**
 * Product terminology for saved places:
 * - Action (before save): "Want to go"
 * - State (after save): "Saved"
 * - Surfaces (shelf / screen / filter): "Want to Go"
 * Bookmark icons are fine; avoid Favorite / Wishlist / Bookmark as labels.
 */
object WantToGoCopy {
    const val ACTION = "Want to go"
    const val STATE_SAVED = "Saved"
    const val SURFACE = "Want to Go"
    const val REMOVE = "Remove from Want to Go"

    fun saveContentDescription(saved: Boolean): String = if (saved) REMOVE else ACTION
}
