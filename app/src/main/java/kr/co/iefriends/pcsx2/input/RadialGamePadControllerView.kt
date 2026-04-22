package kr.co.iefriends.pcsx2.input

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import com.swordfish.radialgamepad.library.RadialGamePad
import com.swordfish.radialgamepad.library.config.ButtonConfig
import com.swordfish.radialgamepad.library.config.CrossConfig
import com.swordfish.radialgamepad.library.config.PrimaryDialConfig
import com.swordfish.radialgamepad.library.config.RadialGamePadConfig
import com.swordfish.radialgamepad.library.config.SecondaryDialConfig
import com.swordfish.radialgamepad.library.event.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kr.co.iefriends.pcsx2.NativeApp

class RadialGamePadControllerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    companion object {
        // Primary dial IDs
        private const val ID_DPAD        = 0
        private const val ID_LEFT_STICK  = 1
        private const val ID_RIGHT_STICK = 2

        // Face buttons
        private const val ID_CROSS       = 10
        private const val ID_CIRCLE      = 11
        private const val ID_SQUARE      = 12
        private const val ID_TRIANGLE    = 13

        // Shoulders
        private const val ID_L1          = 20
        private const val ID_L2          = 21
        private const val ID_R1          = 22
        private const val ID_R2          = 23

        // Meta buttons
        private const val ID_SELECT      = 30
        private const val ID_START       = 31
        private const val ID_L3          = 32
        private const val ID_R3          = 33

        // Analog threshold for D-pad direction events
        private const val DPAD_THRESHOLD = 0.5f

        // Analog scale: RadialGamePad gives -1..1, NativeApp expects 0..255
        private const val ANALOG_SCALE   = 255f
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        setupPads()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope.cancel()
    }

    private fun setupPads() {
        removeAllViews()
        setupLeftPad()
        setupRightPad()
    }

    // -------------------------------------------------------------------------
    // Left pad: D-pad center, L1/L2 top, Select top-center
    // -------------------------------------------------------------------------
    private fun buildLeftPadConfig(): RadialGamePadConfig {
        return RadialGamePadConfig(
            sockets     = 12,
            primaryDial = PrimaryDialConfig.Cross(CrossConfig(id = ID_DPAD)),
            secondaryDials = listOf(
                SecondaryDialConfig.SingleButton(
                    index        = 2,
                    scale        = 1f,
                    distance     = 0f,
                    buttonConfig = ButtonConfig(id = ID_SELECT, label = "SEL")
                ),
                SecondaryDialConfig.SingleButton(
                    index        = 3,
                    scale        = 1f,
                    distance     = 0f,
                    buttonConfig = ButtonConfig(id = ID_L1, label = "L1")
                ),
                SecondaryDialConfig.SingleButton(
                    index        = 4,
                    scale        = 1f,
                    distance     = 0f,
                    buttonConfig = ButtonConfig(id = ID_L2, label = "L2")
                ),
                SecondaryDialConfig.Stick(
                    index    = 9,
                    spread   = 2,
                    scale    = 2.2f,
                    distance = 0.1f,
                    id       = ID_LEFT_STICK
                )
            )
        )
    }

    // -------------------------------------------------------------------------
    // Right pad: face buttons center, R1/R2 top, Start top-center
    // -------------------------------------------------------------------------
    private fun buildRightPadConfig(): RadialGamePadConfig {
        return RadialGamePadConfig(
            sockets     = 12,
            primaryDial = PrimaryDialConfig.PrimaryButtons(
                dials = listOf(
                    ButtonConfig(id = ID_CIRCLE,   label = "○"),
                    ButtonConfig(id = ID_CROSS,    label = "✕"),
                    ButtonConfig(id = ID_SQUARE,   label = "□"),
                    ButtonConfig(id = ID_TRIANGLE, label = "△")
                )
            ),
            secondaryDials = listOf(
                SecondaryDialConfig.SingleButton(
                    index        = 2,
                    scale        = 1f,
                    distance     = 0f,
                    buttonConfig = ButtonConfig(id = ID_R1, label = "R1")
                ),
                SecondaryDialConfig.SingleButton(
                    index        = 3,
                    scale        = 1f,
                    distance     = 0f,
                    buttonConfig = ButtonConfig(id = ID_R2, label = "R2")
                ),
                SecondaryDialConfig.SingleButton(
                    index        = 4,
                    scale        = 1f,
                    distance     = 0f,
                    buttonConfig = ButtonConfig(id = ID_START, label = "START")
                ),
                SecondaryDialConfig.Stick(
                    index    = 9,
                    spread   = 2,
                    scale    = 2.2f,
                    distance = 0.1f,
                    id       = ID_RIGHT_STICK
                )
            )
        )
    }

    private fun setupLeftPad() {
        val pad = RadialGamePad(buildLeftPadConfig(), 25f, context)
        addView(pad, LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.MATCH_PARENT
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        })
        scope.launch {
            pad.events().collect { handleEvent(it) }
        }
    }

    private fun setupRightPad() {
        val pad = RadialGamePad(buildRightPadConfig(), 25f, context)
        addView(pad, LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.MATCH_PARENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        })
        scope.launch {
            pad.events().collect { handleEvent(it) }
        }
    }

    // -------------------------------------------------------------------------
    // Event dispatch
    // -------------------------------------------------------------------------
    private fun handleEvent(event: Event) {
        when (event) {
            is Event.Button    -> handleButton(event)
            is Event.Direction -> handleDirection(event)
            else               -> Unit
        }
    }

    private fun handleButton(event: Event.Button) {
        val keyCode = idToKeyCode(event.id) ?: return
        val pressed = event.action == MotionEvent.ACTION_DOWN
        NativeApp.setPadButton(keyCode, 0, pressed)
    }

    private fun handleDirection(event: Event.Direction) {
        when (event.id) {
            ID_DPAD -> {
                // D-pad: threshold-based digital
                NativeApp.setPadButton(KeyEvent.KEYCODE_DPAD_UP,    0, event.yAxis < -DPAD_THRESHOLD)
                NativeApp.setPadButton(KeyEvent.KEYCODE_DPAD_DOWN,  0, event.yAxis >  DPAD_THRESHOLD)
                NativeApp.setPadButton(KeyEvent.KEYCODE_DPAD_LEFT,  0, event.xAxis < -DPAD_THRESHOLD)
                NativeApp.setPadButton(KeyEvent.KEYCODE_DPAD_RIGHT, 0, event.xAxis >  DPAD_THRESHOLD)
            }
            ID_LEFT_STICK -> {
                // Left stick: analog axes, mirroring sendAnalog() in MainActivity
                // Positive X = right (111), Negative X = left (113)
                // Positive Y = down (112), Negative Y = up (110)
                NativeApp.setPadButton(111, scaled(maxOf(0f, event.xAxis)),  event.xAxis >  0f)
                NativeApp.setPadButton(113, scaled(maxOf(0f, -event.xAxis)), event.xAxis <  0f)
                NativeApp.setPadButton(112, scaled(maxOf(0f, event.yAxis)),  event.yAxis >  0f)
                NativeApp.setPadButton(110, scaled(maxOf(0f, -event.yAxis)), event.yAxis <  0f)
            }
            ID_RIGHT_STICK -> {
                // Right stick: analog axes
                // Positive X = right (121), Negative X = left (123)
                // Positive Y = down (122), Negative Y = up (120)
                NativeApp.setPadButton(121, scaled(maxOf(0f, event.xAxis)),  event.xAxis >  0f)
                NativeApp.setPadButton(123, scaled(maxOf(0f, -event.xAxis)), event.xAxis <  0f)
                NativeApp.setPadButton(122, scaled(maxOf(0f, event.yAxis)),  event.yAxis >  0f)
                NativeApp.setPadButton(120, scaled(maxOf(0f, -event.yAxis)), event.yAxis <  0f)
            }
        }
    }

    private fun scaled(value: Float): Int = (value * ANALOG_SCALE).toInt().coerceIn(0, 255)

    private fun idToKeyCode(id: Int): Int? = when (id) {
        ID_CROSS     -> KeyEvent.KEYCODE_BUTTON_A
        ID_CIRCLE    -> KeyEvent.KEYCODE_BUTTON_B
        ID_SQUARE    -> KeyEvent.KEYCODE_BUTTON_X
        ID_TRIANGLE  -> KeyEvent.KEYCODE_BUTTON_Y
        ID_L1        -> KeyEvent.KEYCODE_BUTTON_L1
        ID_L2        -> KeyEvent.KEYCODE_BUTTON_L2
        ID_R1        -> KeyEvent.KEYCODE_BUTTON_R1
        ID_R2        -> KeyEvent.KEYCODE_BUTTON_R2
        ID_SELECT    -> KeyEvent.KEYCODE_BUTTON_SELECT
        ID_START     -> KeyEvent.KEYCODE_BUTTON_START
        ID_L3        -> KeyEvent.KEYCODE_BUTTON_THUMBL
        ID_R3        -> KeyEvent.KEYCODE_BUTTON_THUMBR
        else         -> null
    }
}