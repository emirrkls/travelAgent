package com.emirrkls.phokarta.ui.presentation

import androidx.annotation.StringRes
import com.emirrkls.phokarta.R

/**
 * Product terminology for saved places (resource IDs).
 * - Action (before save): want_to_go_action
 * - State (after save): want_to_go_saved
 * - Surfaces (shelf / screen / filter): want_to_go_surface
 */
object WantToGoCopy {
    @StringRes val ACTION = R.string.want_to_go_action
    @StringRes val STATE_SAVED = R.string.want_to_go_saved
    @StringRes val SURFACE = R.string.want_to_go_surface
    @StringRes val REMOVE = R.string.want_to_go_remove

    @StringRes
    fun saveContentDescriptionRes(saved: Boolean): Int = if (saved) REMOVE else ACTION
}
