package cn.activityscore.app

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun EvidenceThumbnail(file: File, expanded: Boolean = false, contentDescription: String? = "证明图片") {
    val bitmap by produceState<android.graphics.Bitmap?>(null, file.path, file.lastModified(), expanded) {
        var preview: android.graphics.Bitmap? = null
        try {
            preview = withContext(Dispatchers.IO) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, bounds)
                    cn.activityscore.app.data.EvidencePolicy.dimensions(bounds.outWidth, bounds.outHeight)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > (if (expanded) 1600 else 320)) sample *= 2
                    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                }.getOrNull()
            }
            value = preview
            awaitDispose { preview?.recycle() }
        } finally { if (value == null) preview?.recycle() }
    }
    if (bitmap == null) {
        Box(if (expanded) Modifier.fillMaxWidth().height(300.dp) else Modifier.size(width = 72.dp, height = 54.dp).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) { Text("无法预览\n请检查证明") }
    } else {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = if (expanded) Modifier.fillMaxWidth().height(300.dp) else Modifier.size(width = 72.dp, height = 54.dp).clip(RoundedCornerShape(8.dp)),
            contentScale = if (expanded) ContentScale.Fit else ContentScale.Crop,
        )
    }
}

