package io.github.logan0116.bfilter.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * 用 zxing 把文本编码成二维码位图。
 *
 * 只用到 `QRCodeWriter` 这一个类 —— 不引入它的 Android UI 包，避免多带一个 Activity。
 */
fun generateQrCode(content: String, sizePx: Int = 720): ImageBitmap? = runCatching {
    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        sizePx,
        sizePx,
        mapOf(EncodeHintType.MARGIN to 1)
    )
    val pixels = IntArray(matrix.width * matrix.height)
    for (y in 0 until matrix.height) {
        val rowOffset = y * matrix.width
        for (x in 0 until matrix.width) {
            pixels[rowOffset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
        }
    }
    Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
    }.asImageBitmap()
}.getOrNull()

/**
 * 把二维码存进相册。
 *
 * 手机版扫码登录有个绕不开的物理限制：**二维码和 B 站 App 在同一块屏幕上，
 * 用户没法用自己扫自己**。所以必须提供"存图 → 在 B站 App 扫一扫里选相册"这条路。
 *
 * Android 10+ 走 MediaStore，不需要任何存储权限；更低版本没有免权限的写入路径，
 * 返回 false 让界面提示用户改用截图或粘贴凭证。
 */
fun saveQrToGallery(
    context: Context,
    image: ImageBitmap,
    fileName: String = "bfilter-login-qr.png"
): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    return runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Bfilter")
        }
        val uri = context.contentResolver
            .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching false
        context.contentResolver.openOutputStream(uri)?.use { out ->
            image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
        } ?: return@runCatching false
        true
    }.getOrDefault(false)
}
