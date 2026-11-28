package com.ddd.ubuplasma.vnc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Vue affichant le framebuffer VNC (bureau Plasma) avec mise à l'échelle
 * letterbox et envoi des clics tactiles au serveur.
 */
class VncView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), VncClient.Listener {

    interface Callback {
        fun onVncError(message: String)
        fun onVncDisconnected()
    }

    private var client: VncClient? = null
    private var bitmap: Bitmap? = null

    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private val destRect = RectF()
    private val backgroundPaint = Paint().apply { color = Color.BLACK }

    fun attach(client: VncClient) {
        this.client = client
    }

    fun detach() {
        client = null
    }

    override fun onFramebuffer(width: Int, height: Int, name: String) {
        post {
            bitmap?.recycle()
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            computeTransform()
            invalidate()
        }
    }

    override fun onUpdate(x: Int, y: Int, w: Int, h: Int) {
        val bmp = bitmap ?: return
        val fb = client?.framebuffer ?: return
        post {
            if (bmp.width == client?.width && bmp.height == client?.height) {
                bmp.setPixels(fb, y * bmp.width + x, bmp.width, x, y, w, h)
                invalidate()
            }
        }
    }

    override fun onError(message: String) {
        post { (context as? Callback)?.onVncError(message) }
    }

    override fun onDisconnected() {
        post { (context as? Callback)?.onVncDisconnected() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeTransform()
    }

    private fun computeTransform() {
        val bmp = bitmap ?: return
        if (width == 0 || height == 0) return
        scale = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        offsetX = (width - bmp.width * scale) / 2f
        offsetY = (height - bmp.height * scale) / 2f
        destRect.set(offsetX, offsetY, offsetX + bmp.width * scale, offsetY + bmp.height * scale)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        val bmp = bitmap
        if (bmp == null) {
            backgroundPaint.color = Color.BLACK
            return
        }
        canvas.drawBitmap(bmp, null, destRect, null)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val client = client ?: return false
        if (bitmap == null || scale <= 0f) return false

        val fbX = ((event.x - offsetX) / scale).toInt()
        val fbY = ((event.y - offsetY) / scale).toInt()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> client.sendPointerEvent(fbX, fbY, 1)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> client.sendPointerEvent(fbX, fbY, 0)
            MotionEvent.ACTION_MOVE -> {
                val mask = if (event.pointerCount > 1) 4 else 1
                client.sendPointerEvent(fbX, fbY, mask)
            }
            else -> return false
        }
        return true
    }
}
