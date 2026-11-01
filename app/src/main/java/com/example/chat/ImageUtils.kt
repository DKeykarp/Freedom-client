package com.example.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream

object ImageUtils {
    /** Уменьшает фото до maxSide пикселей, учитывает поворот из EXIF и сжимает в JPEG. */
    fun compress(ctx: Context, uri: Uri, maxSide: Int = 1600, quality: Int = 85): ByteArray {
        val cr = ctx.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("Не удалось прочитать изображение")

        val degrees = cr.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        val m = Matrix()
        if (scale < 1f) m.postScale(scale, scale)
        if (degrees != 0) m.postRotate(degrees.toFloat())
        if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)

        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            .toByteArray()
    }
}
