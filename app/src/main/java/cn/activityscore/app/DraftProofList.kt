package cn.activityscore.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.activityscore.app.data.EvidencePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Preview only. The existing save path still copies and fully validates every selected original. */
@Composable internal fun DraftProofList(uris: List<Uri>, onRemove: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(uris, key = { _, uri -> uri.toString() }) { index, uri ->
            Column {
                DraftProof(uri)
                TextButton(onClick = { onRemove(index) }) { Text("移除证明 ${index + 1}") }
            }
        }
    }
}

@Composable private fun DraftProof(uri: Uri) {
    val resolver = LocalContext.current.applicationContext.contentResolver
    val state by produceState<Pair<Bitmap?, Boolean>>(null to false, uri) {
        var bitmap: Bitmap? = null
        try {
            bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
                    EvidencePolicy.dimensions(bounds.outWidth, bounds.outHeight)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 160) sample *= 2
                    resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                }.getOrNull()
            }
            value = bitmap to true
            awaitDispose { bitmap?.recycle() }
        } finally { if (value.first == null) bitmap?.recycle() }
    }
    val bitmap = state.first
    if (bitmap != null) Image(bitmap.asImageBitmap(), "待添加证明", Modifier.size(80.dp, 60.dp), contentScale = ContentScale.Crop)
    else Box(Modifier.size(80.dp, 60.dp)) { Muted(if (state.second) "无法预览" else "读取中…") }
}
