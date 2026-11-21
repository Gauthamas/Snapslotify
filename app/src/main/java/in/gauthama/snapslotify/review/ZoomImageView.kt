package `in`.gauthama.snapslotify.review

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * An image that fits the view, pinch-zooms (1x to 6x), pans when zoomed, double-tap resets, and can outline one rectangle
 * given in image pixels (the OCR box of a field's source line).
 */
class ZoomImageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : AppCompatImageView(context, attrs) {

    private val base = Matrix()
    private val user = Matrix()
    private val drawMatrix = Matrix()
    private var highlight: RectF? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4 * resources.displayMetrics.density
        color = Color.rgb(255, 152, 0)
    }
    private val fill = Paint().apply { color = Color.argb(60, 255, 152, 0) }

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val current = scale()
            val f = (current * d.scaleFactor).coerceIn(1f, 6f) / current
            user.postScale(f, f, d.focusX, d.focusY)
            apply()
            return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scale() <= 1.01f) return false
            user.postTranslate(-dx, -dy)
            apply()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            user.reset()
            apply()
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    /** Outline [r] (image pixels); null clears it. */
    fun highlight(r: RectF?) {
        highlight = r
        invalidate()
    }

    private fun scale(): Float = FloatArray(9).also(user::getValues)[Matrix.MSCALE_X]

    private fun apply() {
        drawMatrix.set(base)
        drawMatrix.postConcat(user)
        imageMatrix = drawMatrix
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        user.reset()
        fit()
    }

    private fun fit() {
        val d = drawable ?: return
        if (width == 0 || height == 0) return
        base.setRectToRect(RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat()),
            RectF(0f, 0f, width.toFloat(), height.toFloat()), Matrix.ScaleToFit.CENTER)
        apply()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (scale() > 1.01f) parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = highlight ?: return
        val mapped = RectF(r).also(drawMatrix::mapRect)
        canvas.drawRect(mapped, fill)
        canvas.drawRect(mapped, paint)
    }
}
