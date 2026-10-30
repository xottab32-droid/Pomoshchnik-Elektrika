package ru.pomoshchnik.elektrika

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Regression test for Android 15+ edge-to-edge status bar overlap. */
@RunWith(AndroidJUnit4::class)
class SystemBarsTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun appTitleIsBelowSystemStatusBar() {
        composeRule.waitForIdle()
        val decorView = composeRule.activity.window.decorView
        val inset = ViewCompat.getRootWindowInsets(decorView)
            ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
        assertTrue("Android status bar inset should exist on the test emulator", inset > 0)
        val titleTop = composeRule.onNodeWithText("ПОМОЩНИК ЭЛЕКТРИКА")
            .fetchSemanticsNode().boundsInWindow.top
        assertTrue(
            "App title overlaps Android status bar: title=$titleTop px, status bar=$inset px",
            titleTop >= inset.toFloat() - 1f
        )
    }
}
