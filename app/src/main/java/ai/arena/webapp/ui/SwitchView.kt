package ai.arena.webapp.ui

import ai.arena.webapp.R

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * iOS-стиль UISwitch: круглая «таблетка» с анимированной ручкой.
 * Зелёный во включённом состоянии, серый в выключенном.
 */
class SwitchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onCheckedChanged: ((Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val trackW = 51f * density
    private val trackH = 31f * density
    private val knobD = 27f * density
    private val knobPad = (trackH - knobD) / 2f

    var checked: Boolean = false
        private set

    private var fraction = 0f
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
        knobPaint.color = Color.WHITE
        knobPaint.setShadowLayer(3f * density, 0f, 1f * density, Color.argb(80, 0, 0, 0))
        if (android.os.Build.VERSION.SDK_INT < 28) {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }
    }

    fun setChecked(value: Boolean, animate: Boolean = true) {
        if (checked == value && animate) {
            if (fraction != targetFraction()) animateTo(value)
            return
        }
        checked = value
        if (animate) {
            animateTo(value)
        } else {
            fraction = targetFraction()
            invalidate()
        }
    }

    private fun targetFraction(): Float = if (checked) 1f else 0f

    private fun animateTo(value: Boolean) {
        animator?.cancel()
        val target = if (value) 1f else 0f
        animator = ValueAnimator.ofFloat(fraction, target).apply {
            duration = 180
            addUpdateListener {
                fraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(trackW.toInt(), trackH.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val off = context.getColor(R.color.ios_switch_off)
        val on = context.getColor(R.color.ios_green)
        trackPaint.color = lerpColor(off, on, fraction)
        canvas.drawRoundRect(
            RectF(0f, 0f, trackW, trackH),
            trackH / 2f, trackH / 2f, trackPaint
        )
        val kx = knobPad + (trackW - knobD - 2 * knobPad) * fraction
        canvas.drawCircle(
            kx + knobD / 2f, trackH / 2f, knobD / 2f, knobPaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && isEnabled) {
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        setChecked(!checked, true)
        onCheckedChanged?.invoke(checked)
        return true
    }

    private fun lerpColor(from: Int, to: Int, f: Float): Int {
        val a = (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * f).toInt()
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * f).toInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * f).toInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f).toInt()
        return Color.argb(a, r, g, b)
    }
}
