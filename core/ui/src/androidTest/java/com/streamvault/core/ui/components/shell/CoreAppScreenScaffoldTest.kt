package com.streamvault.core.ui.components.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import org.junit.Rule
import org.junit.Test
import com.google.common.truth.Truth.assertThat

class CoreAppScreenScaffoldTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun exposesDestinationSemanticsAndForwardsSelection() {
        var selectedDestination: String? = null
        composeRule.setContent {
            MaterialTheme {
                CoreAppScreenScaffold(
                    currentDestinationId = "home",
                    destinations = listOf(
                        UiDestination(id = "home", label = "Home", icon = Icons.Default.Home),
                        UiDestination(id = "settings", label = "Settings", icon = Icons.Default.Home)
                    ),
                    onDestinationSelected = { selectedDestination = it },
                    title = "Home"
                ) {}
            }
        }

        composeRule.onNodeWithContentDescription("streamvault.destination:home").assertExists()
        composeRule
            .onNode(hasText("Settings") and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick)

        composeRule.runOnIdle {
            assertThat(selectedDestination).isEqualTo("settings")
        }
    }

    @Test
    fun enteringTopBarReturnsFocusToCurrentDestination() {
        assertFocusReturnsToCurrentDestination(
            chrome = NavigationChrome.TopBar,
            direction = Key.DirectionUp
        )
    }

    @Test
    fun enteringRailReturnsFocusToCurrentDestination() {
        assertFocusReturnsToCurrentDestination(
            chrome = NavigationChrome.Rail,
            direction = Key.DirectionLeft
        )
    }

    private fun assertFocusReturnsToCurrentDestination(
        chrome: NavigationChrome,
        direction: Key
    ) {
        composeRule.setContent {
            MaterialTheme {
                CoreAppScreenScaffold(
                    currentDestinationId = "movies",
                    destinations = listOf(
                        UiDestination(id = "home", label = "Home", icon = Icons.Default.Home),
                        UiDestination(id = "movies", label = "Movies", icon = Icons.Default.Home)
                    ),
                    onDestinationSelected = {},
                    title = "Movies",
                    navigationChrome = chrome,
                    showScreenHeader = false
                ) {
                    Surface(
                        onClick = {},
                        modifier = Modifier.testTag("content-target")
                    ) {
                        Text("Content target")
                    }
                }
            }
        }

        composeRule
            .onNodeWithTag("content-target")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.onNodeWithTag("content-target").performKeyInput {
            pressKey(direction)
        }
        composeRule
            .onNode(hasText("Movies") and hasClickAction())
            .assertIsFocused()
    }
}
