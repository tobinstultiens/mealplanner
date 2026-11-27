package dev.tobins.mealplanner.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ring around the focused control (issue 345) is for someone moving the focus with a keyboard: a hardware key shows
 * it, the on-screen keyboard's Next or the Enter of its action key does not. Robolectric has no input method, so the
 * on-screen keyboard is its action sent through the field's semantics and its keys sent with the flags Android gives them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FocusRingKeyboardTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val ring = Color(0xFFFF00FF)
    private val first = FocusRequester()

    /** Two fields, the first asking for Next; the ring's colour is the only magenta, the fields' own borders and cursors are grey. */
    private fun show() = compose.setContent {
        MaterialTheme(lightColorScheme(primary = ring)) {
            val grey = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Gray, cursorColor = Color.Gray, focusedSupportingTextColor = Color.Gray)
            Column(Modifier.fillMaxSize().focusRing().padding(24.dp)) {
                OutlinedTextField(
                    "", {}, Modifier.testTag("first").focusRequester(first),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), singleLine = true, colors = grey,
                )
                OutlinedTextField("", {}, Modifier.testTag("second"), supportingText = { Text("Helper") }, singleLine = true, colors = grey)
            }
        }
    }

    /** How many pixels of the screen have the ring's colour. */
    private fun ringPixels(): Int {
        val image = compose.onRoot().captureToImage()
        val pixels = IntArray(image.width * image.height)
        image.readPixels(pixels)
        return pixels.count { it == ring.toArgb() }
    }

    private fun key(flags: Int, code: Int) =
        KeyEvent(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN, code, 0, 0, 0, 0, flags))

    @Test
    fun theKeyboardsNextMovesTheFocusAndDrawsNoRing() {
        show()
        compose.runOnIdle { first.requestFocus() }
        compose.onNodeWithTag("first").performImeAction()
        compose.onNodeWithTag("second").assertIsFocused()
        assertEquals(0, ringPixels())
    }

    @Test
    fun theKeysOfTheKeyboardsActionKeyAndDeleteDrawNoRing() {
        show()
        compose.runOnIdle { first.requestFocus() }
        compose.onRoot().performKeyPress(key(android.view.KeyEvent.FLAG_EDITOR_ACTION, android.view.KeyEvent.KEYCODE_ENTER))
        compose.onRoot().performKeyPress(key(android.view.KeyEvent.FLAG_SOFT_KEYBOARD, android.view.KeyEvent.KEYCODE_DEL))
        compose.onNodeWithTag("first").performImeAction()
        assertEquals(0, ringPixels())
    }

    @Test
    fun aHardwareTabMovesTheFocusAndDrawsTheRing() {
        show()
        compose.runOnIdle { first.requestFocus() }
        compose.onNodeWithTag("first").performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithTag("second").assertIsFocused()
        assertTrue(ringPixels() > 0, "the ring is drawn")
    }
}
