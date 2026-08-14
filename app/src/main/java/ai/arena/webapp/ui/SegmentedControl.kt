package ai.arena.webapp.ui

import ai.arena.webapp.R

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * iOS-стиль UISegmentedControl: серая подложка, белая «таблетка» с тенью,
 * плавно скользящая между сегментами.
 */
class SegmentedControl @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var onSelect: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val pad = 2f * density
    private val radius = 9f * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackRect = RectF()
    private var thumbRect = RectF()

    private val items = ArrayList<TextView>()
    private var selected = -1
    private var animator: ValueAnimator? = null

    init {
        orientation = HORIZONTAL
        setWillNotDraw(false)
        trackPaint.color = context.getColor(R.color.ios_track)
        thumbPaint.color = context.getColor(R.color.ios_thumb)
        thumbPaint.setShadowLayer(4f * density, 0f, 1f * density, Color.argb(70, 0, 0, 0))
        if (android.os.Build.VERSION.SDK_INT < 28) {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }
    }

    fun setup(labels: List<String>) {
        removeAllViews()
        items.clear()
        for (label in labels) {
            val tv = TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 13f
                isClickable = true
                isFocusable = true
                setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener { v ->
                    select(items.indexOf(v), true)
                    onSelect?.invoke(items.indexOf(v))
                }
            }
            addView(tv)
            items.add(tv)
        }
        if (selected !in items.indices) selected = 0
        applySelection(animate = false)
    }

    fun select(index: Int, animate: Boolean = true) {
        if (index !in items.indices) return
        selected = index
        applySelection(animate)
    }

    fun selectedIndex(): Int = selected

    private fun applySelection(animate: Boolean) {
        for ((i, tv) in items.withIndex()) {
            val on = i == selected
            tv.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            tv.setTextColor(
                context.getColor(if (on) R.color.ios_segment_label else R.color.ios_segment_unselected)
            )
        }
        val target = targetThumbRect()
        if (animate && thumbRect.width() > 0f && width > 0) {
            animator?.cancel()
            val from = RectF(thumbRect)
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 220
                addUpdateListener {
                    val f = it.animatedValue as Float
                    thumbRect.set(
                        from.left + (target.left - from.left) * f,
                        from.top + (target.top - from.top) * f,
                        from.right + (target.right - from.right) * f,
                        from.bottom + (target.bottom - from.bottom) * f
                    )
                    invalidate()
                }
                start()
            }
        } else {
            thumbRect.set(target)
            invalidate()
        }
    }

    private fun targetThumbRect(): RectF {
        if (selected !in items.indices) return RectF()
        val tv = items[selected]
        return RectF(tv.left + pad, pad, tv.right - pad, height - pad)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        trackRect.set(0f, 0f, w.toFloat(), h.toFloat())
        thumbRect.set(targetThumbRect())
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)
        canvas.drawRoundRect(thumbRect, radius - pad, radius - pad, thumbPaint)
        super.dispatchDraw(canvas)
    }
}
