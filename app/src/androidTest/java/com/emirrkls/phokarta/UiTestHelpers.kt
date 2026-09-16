package com.emirrkls.phokarta

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsActions

fun AndroidComposeTestRule<*, *>.skipOnboardingIfNeeded() {
    waitUntil(timeoutMillis = 10_000) {
        onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty() ||
            onAllNodesWithText("Sign in").fetchSemanticsNodes().isNotEmpty() ||
            onAllNodesWithText("For You").fetchSemanticsNodes().isNotEmpty()
    }
    if (onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty()) {
        onNodeWithText("Skip").performClick()
    }
}

fun AndroidComposeTestRule<*, *>.signInIfNeeded() {
    waitUntil(timeoutMillis = 10_000) {
        onAllNodesWithText("Sign in").fetchSemanticsNodes().isNotEmpty() ||
            onAllNodesWithText("For You").fetchSemanticsNodes().isNotEmpty()
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
        onAllNodesWithText("For You").fetchSemanticsNodes().isNotEmpty()
    }
    waitUntil(timeoutMillis = 10_000) {
        onAllNodesWithText("Sunset at Sarnıç Cove").fetchSemanticsNodes().isNotEmpty()
    }
}

fun AndroidComposeTestRule<*, *>.openSarnicPlaceFromExplore() {
    waitForExplore()
    onAllNodesWithText("Sarnıç Cove").onFirst().performClick()
    waitUntil(timeoutMillis = 15_000) {
        onAllNodesWithText("Community reviews").fetchSemanticsNodes().isNotEmpty() &&
            (
                onAllNodesWithText("Been here").fetchSemanticsNodes().isNotEmpty() ||
                    onAllNodesWithText("Rate another visit").fetchSemanticsNodes().isNotEmpty() ||
                    onAllNodesWithText("Continue draft").fetchSemanticsNodes().isNotEmpty()
                )
    }
}

fun AndroidComposeTestRule<*, *>.openVisitComposerFromCurrentPlace() {
    waitUntil(timeoutMillis = 10_000) {
        onAllNodesWithText("Been here").fetchSemanticsNodes().isNotEmpty() ||
            onAllNodesWithText("Rate another visit").fetchSemanticsNodes().isNotEmpty() ||
            onAllNodesWithText("Continue draft").fetchSemanticsNodes().isNotEmpty()
    }
    when {
        onAllNodesWithText("Continue draft").fetchSemanticsNodes().isNotEmpty() ->
            onAllNodesWithText("Continue draft").onFirst()
                .performSemanticsAction(SemanticsActions.OnClick)
        onAllNodesWithText("Rate another visit").fetchSemanticsNodes().isNotEmpty() ->
            onAllNodesWithText("Rate another visit").onFirst()
                .performSemanticsAction(SemanticsActions.OnClick)
        else -> onAllNodesWithText("Been here").onFirst()
            .performSemanticsAction(SemanticsActions.OnClick)
    }
    waitUntil(timeoutMillis = 10_000) {
        onAllNodesWithText("Publish visit").fetchSemanticsNodes().isNotEmpty()
    }
}

fun AndroidComposeTestRule<*, *>.selectRequiredExperienceFields() {
    onNodeWithText("Kahvalti").performClick()
    onNodeWithText("Guzeldi").performClick()
}

fun AndroidComposeTestRule<*, *>.completeMinimumExperience(story: String) {
    selectRequiredExperienceFields()
    onNodeWithContentDescription("Review input")
        .performScrollTo()
        .performTextInput(story)
}
