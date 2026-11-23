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
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.IntentCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File

class MainActivity : ComponentActivity() {

    private var pinSet by mutableStateOf(false)
    private var unlocked by mutableStateOf(false)
    private var hasAllFiles by mutableStateOf(false)
    private var photos by mutableStateOf<List<File>>(emptyList())
    private var viewing by mutableStateOf<File?>(null)
    private var busy by mutableStateOf(false)
    private var pinError by mutableStateOf<String?>(null)

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
        // No screenshots, blank preview in Recent apps
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        pinSet = VaultCrypto.isPinSet(this)
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    when {
                        !pinSet -> SetupPin { pin ->
                            VaultCrypto.setupPin(this@MainActivity, pin)
                            pinSet = true
                            unlocked = true
                            afterUnlock()
                        }

                        !unlocked -> PinEntry("Enter PIN", null, pinError) { pin ->
                            if (VaultCrypto.unlock(this@MainActivity, pin)) {
                                pinError = null
                                unlocked = true
                                afterUnlock()
                            } else pinError = "Wrong PIN"
                        }

                        !hasAllFiles -> PermissionScreen {
                            startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:$packageName")
                                )
                            )
                        }

                        else -> {
                            var confirmDelete by remember { mutableStateOf(false) }
                            VaultScreen(
                                photos = photos,
                                busy = busy,
                                onAdd = {
                                    suppressLock = true
                                    picker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                onLock = { lockNow() },
                                onOpen = { viewing = it }
                            )
                            viewing?.let { f ->
                                Viewer(
                                    f,
                                    onClose = { viewing = null },
                                    onRestore = { restoreToGallery(f) },
                                    onDelete = { confirmDelete = true }
                                )
                                if (confirmDelete) {
                                    AlertDialog(
                                        onDismissRequest = { confirmDelete = false },
                                        title = { Text("Delete permanently?") },
                                        text = { Text("This photo will be gone for good.") },
                                        confirmButton = {
                                            TextButton(onClick = {
                                                confirmDelete = false
                                                deleteFromVault(f)
                                            }) { Text("Delete") }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
                                        }
                                    )
                                }
                            }
                        }
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
        unlocked = false
        viewing = null
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
        viewing = null
        photos = photos - f
    }

    private fun restoreToGallery(f: File) {
        lifecycleScope.launch {
            busy = true
            val ok = withContext(Dispatchers.IO) { restore(f) }
            busy = false
            if (ok) {
                viewing = null
                photos = photos - f
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

// ================================================================== UI

@Composable
fun PinEntry(title: String, hint: String?, error: String?, onFull: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocus() }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (hint != null) {
            Spacer(Modifier.height(8.dp))
            Text(hint, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { v ->
                val d = v.filter { it.isDigit() }.take(6)
                pin = d
                if (d.length == 6) {
                    pin = ""
                    onFull(d)
                }
            },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.focusRequester(fr)
        )
        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
fun SetupPin(onDone: (String) -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    PinEntry(
        title = if (first == null) "Create a 6-digit PIN" else "Confirm your PIN",
        hint = "If you forget this PIN, the photos cannot be recovered.",
        error = err
    ) { p ->
        if (first == null) {
            first = p
            err = null
        } else if (first == p) {
            onDone(p)
        } else {
            first = null
            err = "PINs did not match. Start again."
        }
    }
}

@Composable
fun PermissionScreen(onOpenSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Allow \"All files access\" so the vault can keep its .MyVault folder.")
        Spacer(Modifier.height(16.dp))
        Button(onClick = onOpenSettings) { Text("Open settings") }
    }
}

@Composable
fun VaultScreen(
    photos: List<File>,
    busy: Boolean,
    onAdd: () -> Unit,
    onLock: () -> Unit,
    onOpen: (File) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "My Vault (${photos.size})",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onAdd) { Text("+ Add") }
            TextButton(onClick = onLock) { Text("Lock") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (photos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Share photos from Gallery to this app")
            }
        } else {
            LazyVerticalGrid(GridCells.Fixed(3)) {
                items(photos, key = { it.name }) { f -> Thumb(f) { onOpen(f) } }
            }
        }
    }
}

@Composable
fun Thumb(f: File, onClick: () -> Unit) {
    val bmp by produceState<Bitmap?>(null, f) {
        value = withContext(Dispatchers.IO) { loadBitmap(f, 300) }
    }
    Box(Modifier.aspectRatio(1f).padding(1.dp).background(Color.DarkGray).clickable(onClick = onClick)) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
fun Viewer(f: File, onClose: () -> Unit, onRestore: () -> Unit, onDelete: () -> Unit) {
    val bmp by produceState<Bitmap?>(null, f) {
        value = withContext(Dispatchers.IO) { loadBitmap(f, 2048) }
    }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onClose,
        // SecureOn = no screenshots inside the viewer either
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)
    ) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Box(
                Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                }
            ) {
                bmp?.let {
                    Image(
                        it.asImageBitmap(), null,
                        Modifier.fillMaxSize().graphicsLayer(
                            scaleX = scale, scaleY = scale,
                            translationX = offset.x, translationY = offset.y
                        ),
                        contentScale = ContentScale.Fit
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = onClose) { Text("Close") }
                TextButton(onClick = onRestore) { Text("Restore") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

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
