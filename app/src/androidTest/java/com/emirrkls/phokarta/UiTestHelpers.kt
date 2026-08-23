package com.emirrkls.phokarta

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput

fun AndroidComposeTestRule<*, *>.skipOnboardingIfNeeded() {
    waitUntil(timeoutMillis = 10_000) {
        onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty() ||
            onAllNodesWithText("Sign in").fetchSemanticsNodes().isNotEmpty() ||
            onAllNodesWithText("Sarnıç Cove").fetchSemanticsNodes().isNotEmpty() ||
            onAllNodesWithText("Where to next", substring = true).fetchSemanticsNodes().isNotEmpty()
    }
    if (onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty()) {
        onNodeWithText("Skip").performClick()
    }
}

fun AndroidComposeTestRule<*, *>.signInIfNeeded() {
    waitUntil(timeoutMillis = 10_000) {
        onAllNodesWithText("Sign in").fetchSemanticsNodes().isNotEmpty() ||
            onAllNodesWithText("Sarnıç Cove").fetchSemanticsNodes().isNotEmpty() ||
            onAllNodesWithText("Where to next", substring = true).fetchSemanticsNodes().isNotEmpty()
    }
    val onLogin = onAllNodesWithText("Email or username").fetchSemanticsNodes().isNotEmpty() ||
        onAllNodesWithText("Sign in to continue").fetchSemanticsNodes().isNotEmpty()
    if (!onLogin) return

    if (onAllNodesWithText("Email or username").fetchSemanticsNodes().isNotEmpty()) {
        onNodeWithText("Email or username").performTextInput("demo@phokarta.local")
    }
    if (onAllNodesWithText("Password").fetchSemanticsNodes().isNotEmpty()) {
        onNodeWithText("Password").performTextInput("password1")
    }
    onNodeWithText("Sign in").performClick()
}

fun AndroidComposeTestRule<*, *>.waitForExplore() {
    waitUntil(timeoutMillis = 20_000) {
        onAllNodesWithText("Sarnıç Cove").fetchSemanticsNodes().isNotEmpty() ||
            onAllNodesWithText("Where to next", substring = true).fetchSemanticsNodes().isNotEmpty()
    }
    waitUntil(timeoutMillis = 10_000) {
        onAllNodesWithText("Sarnıç Cove").fetchSemanticsNodes().isNotEmpty()
    }
}
