package com.example.insta_auto_adjust.network

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import com.example.insta_auto_adjust.presentation.ShootingIntent
import java.io.ByteArrayOutputStream

/**
 * Temporary representative-frame generator for B-D-C integration before A
 * provides real preview frames.
 */
object DebugFrameImageFactory {
    fun createBase64Jpeg(intent: ShootingIntent): String {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        canvas.drawColor(Color.rgb(218, 226, 232))

        when (intent) {
            ShootingIntent.SUBJECT_PRIORITY -> drawSubjectPriority(canvas, paint)
            ShootingIntent.HIGHLIGHT_PRIORITY -> drawHighlightPriority(canvas, paint)
            ShootingIntent.STABLE_EXPOSURE -> drawStableExposure(canvas, paint)
            ShootingIntent.BALANCED -> drawBalanced(canvas, paint)
        }

        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)
        bitmap.recycle()
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    private fun drawSubjectPriority(canvas: Canvas, paint: Paint) {
        paint.color = Color.rgb(245, 247, 250)
        canvas.drawRect(420f, 0f, 640f, 480f, paint)
        paint.color = Color.rgb(72, 78, 86)
        canvas.drawCircle(210f, 170f, 66f, paint)
        canvas.drawRect(145f, 240f, 275f, 410f, paint)
    }

    private fun drawHighlightPriority(canvas: Canvas, paint: Paint) {
        paint.color = Color.rgb(245, 249, 255)
        canvas.drawRect(0f, 0f, 640f, 190f, paint)
        paint.color = Color.rgb(89, 116, 138)
        canvas.drawRect(0f, 190f, 640f, 480f, paint)
        paint.color = Color.rgb(48, 58, 66)
        canvas.drawCircle(310f, 280f, 56f, paint)
    }

    private fun drawStableExposure(canvas: Canvas, paint: Paint) {
        paint.color = Color.rgb(128, 136, 144)
        canvas.drawRect(0f, 0f, 640f, 480f, paint)
        paint.color = Color.rgb(104, 112, 120)
        canvas.drawCircle(320f, 220f, 72f, paint)
    }

    private fun drawBalanced(canvas: Canvas, paint: Paint) {
        paint.color = Color.rgb(180, 188, 194)
        canvas.drawRect(0f, 0f, 640f, 480f, paint)
        paint.color = Color.rgb(96, 110, 122)
        canvas.drawCircle(250f, 210f, 64f, paint)
        paint.color = Color.rgb(220, 226, 230)
        canvas.drawRect(430f, 80f, 585f, 210f, paint)
    }
}
