package ru.pomoshchnik.elektrika

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertExists
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Smoke check: the new application UI starts with a graphical cabinet choice. */
@RunWith(AndroidJUnit4::class)
class GraphicalPanelUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun chooseCabinetBeforeAddingDinRail() {
        rule.onNode(hasSetTextAction()).performTextInput("Графический тест")
        rule.onNodeWithText("Создать проект").performClick()
        rule.waitUntil(10000L) {
            rule.onAllNodesWithText("Графический тест", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        rule.onNodeWithText("Щит", useUnmergedTree = true).performClick()
        rule.onNodeWithText("1. Выбери корпус").assertExists()
        rule.onNodeWithText("Щит 24").performClick()

        rule.waitUntil(10000L) {
            rule.onAllNodesWithText("Установленные DIN-рейки · 0/2")
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("+ DIN-рейка").performScrollTo().performClick()
        rule.waitUntil(10000L) {
            rule.onAllNodesWithText("Установленные DIN-рейки · 1/2")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}
