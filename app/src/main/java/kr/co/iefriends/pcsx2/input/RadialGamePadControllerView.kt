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
import kr.co.iefriends.pcsx2.activities.LocalPadConfig
import kr.co.iefriends.pcsx2.activities.PadConfig
import kr.co.iefriends.pcsx2.activities.PadDial
import kr.co.iefriends.pcsx2.activities.PrimaryDialDef
import kr.co.iefriends.pcsx2.activities.PsxIds

class RadialGamePadControllerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    companion object {
        private const val DPAD_THRESHOLD = 0.5f
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

    // -------------------------------------------------------------------------
    // Pad setup — driven entirely by LocalPadConfig
    // -------------------------------------------------------------------------

    private fun setupPads() {
        removeAllViews()
        setupPad(LocalPadConfig.leftPad,  Gravity.START or Gravity.CENTER_VERTICAL)
        setupPad(LocalPadConfig.rightPad, Gravity.END   or Gravity.CENTER_VERTICAL)
    }

    private fun buildPadConfig(config: PadConfig): RadialGamePadConfig {
        val primaryDial = when (val p = config.primaryDial) {
            is PrimaryDialDef.Cross    -> PrimaryDialConfig.Cross(
                CrossConfig(id = p.id, useDiagonals = p.useDiagonals)
            )
            is PrimaryDialDef.Buttons  -> PrimaryDialConfig.PrimaryButtons(
                dials = p.dials.map { ButtonConfig(id = it.id, label = it.label) }
            )
        }

        val secondaryDials = config.secondaryDials.map { dial ->
            when (dial) {
                is PadDial.Empty  -> SecondaryDialConfig.Empty(
                    dial.index, dial.spread, dial.scale, dial.distance
                )
                is PadDial.Button -> SecondaryDialConfig.SingleButton(
                    dial.index, dial.scale, dial.distance,
                    ButtonConfig(id = dial.id, label = dial.label)
                )
                is PadDial.Stick  -> SecondaryDialConfig.Stick(
                    index    = dial.index,
                    spread   = dial.spread,
                    scale    = dial.scale,
                    distance = dial.distance,
                    id       = dial.id
                )
            }
        }

        return RadialGamePadConfig(
            sockets        = config.socketCount,
            primaryDial    = primaryDial,
            secondaryDials = secondaryDials
        )
    }

    private fun setupPad(config: PadConfig, gravity: Int) {
        val pad = RadialGamePad(buildPadConfig(config), LocalPadConfig.PAD_SIZE, context)
        addView(pad, LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.MATCH_PARENT
        ).apply {
            this.gravity = gravity
        })
        scope.launch {
            pad.events().collect { handleEvent(it) }
        }
    }

    // -------------------------------------------------------------------------
    // Event dispatch — unchanged from original
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
            PsxIds.DPAD -> {
                NativeApp.setPadButton(KeyEvent.KEYCODE_DPAD_UP,    0, event.yAxis < -DPAD_THRESHOLD)
                NativeApp.setPadButton(KeyEvent.KEYCODE_DPAD_DOWN,  0, event.yAxis >  DPAD_THRESHOLD)
                NativeApp.setPadButton(KeyEvent.KEYCODE_DPAD_LEFT,  0, event.xAxis < -DPAD_THRESHOLD)
                NativeApp.setPadButton(KeyEvent.KEYCODE_DPAD_RIGHT, 0, event.xAxis >  DPAD_THRESHOLD)
            }
            PsxIds.LEFT_STICK -> {
                NativeApp.setPadButton(111, scaled(maxOf(0f,  event.xAxis)), event.xAxis >  0f)
                NativeApp.setPadButton(113, scaled(maxOf(0f, -event.xAxis)), event.xAxis <  0f)
                NativeApp.setPadButton(112, scaled(maxOf(0f,  event.yAxis)), event.yAxis >  0f)
                NativeApp.setPadButton(110, scaled(maxOf(0f, -event.yAxis)), event.yAxis <  0f)
            }
            PsxIds.RIGHT_STICK -> {
                NativeApp.setPadButton(121, scaled(maxOf(0f,  event.xAxis)), event.xAxis >  0f)
                NativeApp.setPadButton(123, scaled(maxOf(0f, -event.xAxis)), event.xAxis <  0f)
                NativeApp.setPadButton(122, scaled(maxOf(0f,  event.yAxis)), event.yAxis >  0f)
                NativeApp.setPadButton(120, scaled(maxOf(0f, -event.yAxis)), event.yAxis <  0f)
            }
        }
    }

    private fun scaled(value: Float): Int = (value * ANALOG_SCALE).toInt().coerceIn(0, 255)

    private fun idToKeyCode(id: Int): Int? = when (id) {
        PsxIds.CROSS     -> KeyEvent.KEYCODE_BUTTON_A
        PsxIds.CIRCLE    -> KeyEvent.KEYCODE_BUTTON_B
        PsxIds.SQUARE    -> KeyEvent.KEYCODE_BUTTON_X
        PsxIds.TRIANGLE  -> KeyEvent.KEYCODE_BUTTON_Y
        PsxIds.L1        -> KeyEvent.KEYCODE_BUTTON_L1
        PsxIds.L2        -> KeyEvent.KEYCODE_BUTTON_L2
        PsxIds.R1        -> KeyEvent.KEYCODE_BUTTON_R1
        PsxIds.R2        -> KeyEvent.KEYCODE_BUTTON_R2
        PsxIds.SELECT    -> KeyEvent.KEYCODE_BUTTON_SELECT
        PsxIds.START     -> KeyEvent.KEYCODE_BUTTON_START
        PsxIds.L3        -> KeyEvent.KEYCODE_BUTTON_THUMBL
        PsxIds.R3        -> KeyEvent.KEYCODE_BUTTON_THUMBR
        else             -> null
    }
}