@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.example.myvault

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.LruCache
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.roundToInt

// ============================================================ theme

private const val APP_NAME = "Anshu Jainam Namya"

private val Bg = Color(0xFF0A0E1A)
private val Surf = Color(0xFF131A2B)
private val Accent1 = Color(0xFF7C5CFF)
private val Accent2 = Color(0xFF4F8CFF)
private val TextPri = Color(0xFFF2F4FA)
private val TextSec = Color(0xFF8E98B3)
private val Danger = Color(0xFFFF5C7A)
private val AccentBrush = Brush.linearGradient(listOf(Accent1, Accent2))

private val VaultColors = darkColorScheme(
    primary = Accent1,
    onPrimary = Color.White,
    background = Bg,
    onBackground = TextPri,
    surface = Surf,
    onSurface = TextPri
)

sealed class PinResult {
    object Success : PinResult()
    object Next : PinResult()
    data class Error(val msg: String) : PinResult()
}

object ThumbCache {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    fun get(k: String): Bitmap? = cache.get(k)
    fun put(k: String, v: Bitmap) { cache.put(k, v) }
    fun clear() = cache.evictAll()
}

// ============================================================ activity

class MainActivity : ComponentActivity() {

    private var pinSet by mutableStateOf(false)
    private var unlocked by mutableStateOf(false)
    private var hasAllFiles by mutableStateOf(false)
    private var photos by mutableStateOf<List<File>>(emptyList())
    private var viewIndex by mutableStateOf<Int?>(null)
    private var busy by mutableStateOf(false)

    private val pendingShared = mutableListOf<Uri>()
    private var suppressLock = false // true only while a system dialog/picker is on top

    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            suppressLock = false
        }

    private val picker =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
            suppressLock = false
            if (uris.isNotEmpty()) importUris(uris, deleteOriginals = false)
        }

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        // No screenshots, blank preview in Recent apps
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        pinSet = VaultCrypto.isPinSet(this)
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            MaterialTheme(colorScheme = VaultColors) {
                var toDelete by remember { mutableStateOf<File?>(null) }

                Box(Modifier.fillMaxSize().background(Bg)) {
                    Box(Modifier.fillMaxSize().systemBarsPadding()) {
                        when {
                            !pinSet -> SetupPin { pin ->
                                withContext(Dispatchers.Default) { VaultCrypto.setupPin(this@MainActivity, pin) }
                                pinSet = true
                                unlocked = true
                                afterUnlock()
                            }

                            !unlocked -> PinScreen(
                                subtitle = "Enter your PIN to unlock",
                                hint = null
                            ) { pin ->
                                val wait = VaultCrypto.lockoutMs(this@MainActivity)
                                if (wait > 0) {
                                    PinResult.Error("Too many attempts. Try again in ${(wait + 999) / 1000}s")
                                } else {
                                    val ok = withContext(Dispatchers.Default) {
                                        VaultCrypto.unlock(this@MainActivity, pin)
                                    }
                                    if (ok) {
                                        unlocked = true
                                        afterUnlock()
                                        PinResult.Success
                                    } else {
                                        val w2 = VaultCrypto.lockoutMs(this@MainActivity)
                                        PinResult.Error(
                                            if (w2 > 0) "Too many attempts. Try again in ${(w2 + 999) / 1000}s"
                                            else "Incorrect PIN"
                                        )
                                    }
                                }
                            }

                            !hasAllFiles -> PermissionScreen {
                                startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:$packageName")
                                    )
                                )
                            }

                            else -> VaultScreen(
                                photos = photos,
                                busy = busy,
                                onAdd = {
                                    suppressLock = true
                                    picker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                onLock = { lockNow() },
                                onOpen = { viewIndex = it }
                            )
                        }
                    }

                    val vi = viewIndex
                    if (unlocked && hasAllFiles && vi != null && photos.isNotEmpty()) {
                        Viewer(
                            photos = photos,
                            startIndex = vi,
                            onClose = { viewIndex = null },
                            onRestore = { restoreToGallery(it) },
                            onDelete = { toDelete = it }
                        )
                    }

                    toDelete?.let { f ->
                        AlertDialog(
                            onDismissRequest = { toDelete = null },
                            containerColor = Surf,
                            title = { Text("Delete permanently?") },
                            text = { Text("This photo will be gone for good. This cannot be undone.", color = TextSec) },
                            confirmButton = {
                                TextButton(onClick = {
                                    toDelete = null
                                    deleteFromVault(f)
                                }) { Text("Delete", color = Danger) }
                            },
                            dismissButton = {
                                TextButton(onClick = { toDelete = null }) { Text("Cancel", color = TextSec) }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        suppressLock = false
        hasAllFiles = Environment.isExternalStorageManager()
        if (unlocked && hasAllFiles) afterUnlock()
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app (Home / Recents / screen off) locks the vault
        if (!suppressLock) lockNow()
    }

    // ------------------------------------------------------------ lock / unlock

    private fun lockNow() {
        VaultCrypto.lock()
        ThumbCache.clear()
        unlocked = false
        viewIndex = null
    }

    private fun afterUnlock() {
        hasAllFiles = Environment.isExternalStorageManager()
        if (!hasAllFiles) return
        lifecycleScope.launch {
            photos = withContext(Dispatchers.IO) { listSorted() }
            processPending()
        }
    }

    private fun listSorted() = VaultCrypto.listPhotos().sortedByDescending { it.lastModified() }

    // ------------------------------------------------------------ import (Share)

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val list: List<Uri> = when (i.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
                    ?: emptyList()
            else -> emptyList()
        }
        if (list.isNotEmpty()) {
            pendingShared.addAll(list)
            processPending()
        }
    }

    // Shared photos wait here until the PIN is entered
    private fun processPending() {
        if (!unlocked || !hasAllFiles || pendingShared.isEmpty()) return
        val uris = pendingShared.toList()
        pendingShared.clear()
        importUris(uris, deleteOriginals = true)
    }

    private fun importUris(uris: List<Uri>, deleteOriginals: Boolean) {
        if (!unlocked || !hasAllFiles) return
        lifecycleScope.launch {
            busy = true
            val done = withContext(Dispatchers.IO) {
                uris.filter { runCatching { VaultCrypto.importPhoto(this@MainActivity, it) }.isSuccess }
            }
            photos = withContext(Dispatchers.IO) { listSorted() }
            busy = false
            if (deleteOriginals) {
                val deletable = done.filter { it.authority == "media" }
                if (deletable.isNotEmpty()) requestDelete(deletable)
            }
        }
    }

    // Android asks the user once to confirm removing the originals from Gallery
    private fun requestDelete(uris: List<Uri>) {
        runCatching {
            val pi = MediaStore.createDeleteRequest(contentResolver, uris)
            suppressLock = true
            deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        }.onFailure { suppressLock = false }
    }

    // ------------------------------------------------------------ restore / delete

    private fun deleteFromVault(f: File) {
        f.delete()
        photos = photos - f
        if (photos.isEmpty()) viewIndex = null
    }

    private fun restoreToGallery(f: File) {
        lifecycleScope.launch {
            busy = true
            val ok = withContext(Dispatchers.IO) { restore(f) }
            busy = false
            if (ok) {
                photos = photos - f
                if (photos.isEmpty()) viewIndex = null
                Toast.makeText(this@MainActivity, "Restored to Gallery", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "Restore failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun restore(f: File): Boolean = runCatching {
        val bytes = VaultCrypto.openPhoto(f).use { it.readBytes() }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        val mime = o.outMimeType ?: "image/jpeg"
        val ext = mime.substringAfter('/').replace("jpeg", "jpg")
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Restored_${System.currentTimeMillis()}.$ext")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Restored")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("insert failed")
        contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        f.delete() // remove from vault only after the Gallery copy is written
    }.getOrDefault(false)
}

// ============================================================ PIN screens

@Composable
fun SetupPin(onDone: suspend (String) -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    PinScreen(
        subtitle = if (first == null) "Create a 6-digit PIN" else "Confirm your PIN",
        hint = "If you forget this PIN, your photos cannot be recovered."
    ) { p ->
        if (first == null) {
            first = p
            PinResult.Next
        } else if (first == p) {
            onDone(p)
            PinResult.Success
        } else {
            first = null
            PinResult.Error("PINs didn't match. Try again.")
        }
    }
}

@Composable
fun PinScreen(subtitle: String, hint: String?, onComplete: suspend (String) -> PinResult) {
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errMsg by remember { mutableStateOf<String?>(null) }
    val shake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(150)
        runCatching { focus.requestFocus() }
    }

    fun submit(p: String) {
        busy = true
        scope.launch {
            when (val r = onComplete(p)) {
                is PinResult.Success -> Unit
                is PinResult.Next -> {
                    pin = ""
                    busy = false
                }
                is PinResult.Error -> {
                    errMsg = r.msg
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    for (v in listOf(-22f, 22f, -14f, 14f, -6f, 6f, 0f)) shake.animateTo(v, tween(45))
                    pin = ""
                    busy = false
                }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().imePadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))
        AppBadge(72.dp)
        Spacer(Modifier.height(16.dp))
        Text(APP_NAME, color = TextPri, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, color = TextSec, fontSize = 14.sp)
        Spacer(Modifier.height(30.dp))

        Box(Modifier.offset { IntOffset(shake.value.roundToInt(), 0) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                repeat(6) { i -> Dot(filled = i < pin.length, error = errMsg != null) }
            }
            // Invisible field on top of the dots: tapping it opens the number-only keyboard
            BasicTextField(
                value = pin,
                onValueChange = { v ->
                    if (!busy) {
                        val d = v.filter { it.isDigit() }.take(6)
                        errMsg = null
                        pin = d
                        if (d.length == 6) submit(d)
                    }
                },
                modifier = Modifier.matchParentSize().focusRequester(focus),
                textStyle = TextStyle(color = Color.Transparent),
                cursorBrush = SolidColor(Color.Transparent),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
            )
        }

        Spacer(Modifier.height(16.dp))
        Box(Modifier.heightIn(min = 40.dp), contentAlignment = Alignment.TopCenter) {
            val msg = when {
                errMsg != null -> errMsg
                busy -> "Please wait…"
                else -> hint
            }
            if (msg != null) {
                Text(
                    msg,
                    color = if (errMsg != null) Danger else TextSec,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
        Spacer(Modifier.weight(1.5f))
    }
}

@Composable
fun Dot(filled: Boolean, error: Boolean) {
    val scale by animateFloatAsState(
        if (filled) 1.2f else 1f,
        spring(Spring.DampingRatioMediumBouncy),
        label = "dotScale"
    )
    val fill by animateColorAsState(
        when {
            error -> Danger
            filled -> Accent1
            else -> Color.Transparent
        },
        label = "dotColor"
    )
    Box(
        Modifier
            .size(16.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clip(CircleShape)
            .background(fill)
            .border(1.5.dp, if (error) Danger else if (filled) Accent1 else TextSec, CircleShape)
    )
}

// ============================================================ common bits

@Composable
fun AppBadge(size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(AccentBrush),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Filled.Lock, null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun IconCircleButton(icon: ImageVector, accent: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .then(if (accent) Modifier.background(AccentBrush) else Modifier.background(Surf))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun GradientButton(text: String, icon: ImageVector?, onClick: () -> Unit) {
    Row(
        Modifier
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(AccentBrush)
            .clickable(onClick = onClick)
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
fun PillButton(
    text: String,
    icon: ImageVector,
    modifier: Modifier,
    accent: Boolean,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    Row(
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .then(if (accent) Modifier.background(AccentBrush) else Modifier.background(tint.copy(alpha = 0.16f)))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (accent) Color.White else tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = if (accent) Color.White else tint, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun PermissionScreen(onOpenSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AppBadge(72.dp)
        Spacer(Modifier.height(24.dp))
        Text("One last step", color = TextPri, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(
            "Android needs \"All files access\" so your vault can keep its hidden .MyVault folder. " +
                "Your photos stay encrypted at all times.",
            color = TextSec, fontSize = 14.sp, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        GradientButton("Grant access", null, onOpenSettings)
    }
}

// ============================================================ vault screen

@Composable
fun VaultScreen(
    photos: List<File>,
    busy: Boolean,
    onAdd: () -> Unit,
    onLock: () -> Unit,
    onOpen: (Int) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppBadge(44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(APP_NAME, color = TextPri, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    if (photos.isEmpty()) "Private vault" else "${photos.size} photos  •  AES-256 encrypted",
                    color = TextSec, fontSize = 12.sp, maxLines = 1
                )
            }
            IconCircleButton(Icons.Filled.Add, accent = true, onClick = onAdd)
            Spacer(Modifier.width(8.dp))
            IconCircleButton(Icons.Filled.Lock, accent = false, onClick = onLock)
        }

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Accent1, trackColor = Surf)
        else Spacer(Modifier.height(2.dp))

        if (photos.isEmpty()) {
            EmptyState(onAdd)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(photos, key = { _, f -> f.name }) { i, f -> Thumb(f) { onOpen(i) } }
            }
        }
    }
}

@Composable
fun EmptyState(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(120.dp).clip(CircleShape).background(Surf), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Lock, null, tint = Accent1, modifier = Modifier.size(52.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("Your vault is empty", color = TextPri, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Share photos from your Gallery to this app, or tap below. They'll be locked away safely.",
            color = TextSec, fontSize = 14.sp, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        GradientButton("Add photos", Icons.Filled.Add, onAdd)
    }
}

@Composable
fun Thumb(f: File, onClick: () -> Unit) {
    val bmp by produceState<Bitmap?>(ThumbCache.get(f.name), f) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { loadBitmap(f, 300) }?.also { ThumbCache.put(f.name, it) }
        }
    }
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(Surf)
            .clickable(onClick = onClick)
    ) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

// ============================================================ viewer

@Composable
fun Viewer(
    photos: List<File>,
    startIndex: Int,
    onClose: () -> Unit,
    onRestore: (File) -> Unit,
    onDelete: (File) -> Unit
) {
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, photos.size - 1)) { photos.size }
    val current = photos.getOrNull(pager.currentPage)
    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            key = { photos.getOrNull(it)?.name ?: it }
        ) { page ->
            photos.getOrNull(page)?.let { ZoomableImage(it) }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent)))
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconCircleButton(Icons.Filled.Close, accent = false, onClick = onClose)
            Spacer(Modifier.weight(1f))
            Text(
                "${pager.currentPage + 1} / ${photos.size}",
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.width(12.dp))
        }

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PillButton("Restore", Icons.Filled.Refresh, Modifier.weight(1f), accent = true) {
                current?.let(onRestore)
            }
            PillButton("Delete", Icons.Filled.Delete, Modifier.weight(1f), accent = false, tint = Danger) {
                current?.let(onDelete)
            }
        }
    }
}

@Composable
fun ZoomableImage(f: File) {
    val bmp by produceState<Bitmap?>(null, f) {
        value = withContext(Dispatchers.IO) { loadBitmap(f, 2048) }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    scale = if (scale > 1f) 1f else 2.5f
                    offset = Offset.Zero
                })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val multi = event.changes.size > 1
                        if (multi || scale > 1f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            val maxX = box.width * (scale - 1f) / 2f
                            val maxY = box.height * (scale - 1f) / 2f
                            offset = if (scale <= 1f) Offset.Zero
                            else Offset(
                                (offset.x + pan.x).coerceIn(-maxX, maxX),
                                (offset.y + pan.y).coerceIn(-maxY, maxY)
                            )
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b == null) {
            CircularProgressIndicator(color = Accent1)
        } else {
            Image(
                b.asImageBitmap(), null,
                Modifier.fillMaxSize().graphicsLayer(
                    scaleX = scale, scaleY = scale,
                    translationX = offset.x, translationY = offset.y
                ),
                contentScale = ContentScale.Fit
            )
        }
    }
}

// ============================================================ image decoding

// Decrypts once, downsamples, and fixes camera rotation
fun loadBitmap(f: File, maxDim: Int): Bitmap? = runCatching {
    val bytes = VaultCrypto.openPhoto(f).use { it.readBytes() }
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    var s = 1
    while (o.outWidth / (s * 2) >= maxDim && o.outHeight / (s * 2) >= maxDim) s *= 2
    val bmp = BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }
    ) ?: throw IllegalStateException("decode failed")
    val deg = when (
        ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    ) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (deg == 0f) bmp
    else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
}.getOrNull()
