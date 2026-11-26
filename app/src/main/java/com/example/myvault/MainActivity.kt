@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.media3.common.util.UnstableApi::class
)

package com.example.myvault

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.math.MathContext

// ============================================================ theme

private const val APP_NAME = "Anshu Jainam Namya"

internal val Bg = Color(0xFF0A0E1A)
internal val Surf = Color(0xFF131A2B)
internal val Accent1 = Color(0xFF7C5CFF)
internal val Accent2 = Color(0xFF4F8CFF)
internal val TextPri = Color(0xFFF2F4FA)
internal val TextSec = Color(0xFF8E98B3)
internal val Danger = Color(0xFFFF5C7A)
internal val AccentBrush = Brush.linearGradient(listOf(Accent1, Accent2))

private val VaultColors = darkColorScheme(
    primary = Accent1,
    onPrimary = Color.White,
    background = Bg,
    onBackground = TextPri,
    surface = Surf,
    onSurface = TextPri
)

object ThumbCache {
    private val cache = object : LruCache<String, ItemInfo>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: ItemInfo) = value.thumb?.byteCount ?: 1024
    }
    fun get(k: String): ItemInfo? = cache.get(k)
    fun put(k: String, v: ItemInfo) { cache.put(k, v) }
    fun clear() = cache.evictAll()
}

// ============================================================ activity

class MainActivity : ComponentActivity() {

    private var pinSet by mutableStateOf(false)
    private var unlocked by mutableStateOf(false)
    private var hasAllFiles by mutableStateOf(false)
    private var photos by mutableStateOf<List<File>>(emptyList())
    private var viewIndex by mutableStateOf<Int?>(null)
    private var busyText by mutableStateOf<String?>(null)
    private var calcKey by mutableIntStateOf(0)

    // Notes
    private var section by mutableIntStateOf(0) // 0 = photos & videos, 1 = notes
    private var notes by mutableStateOf<List<NoteRow>>(emptyList())
    private var notesUnlocked by mutableStateOf(false)
    private var notesPasswordOpen by mutableStateOf(false)
    private var editing by mutableStateOf<NoteDraft?>(null)

    private val pendingShared = mutableListOf<Uri>()
    private val pendingDelete = mutableListOf<Uri>()
    private var importing = 0
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
        pinSet = VaultCrypto.isPinSet(this)
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            MaterialTheme(colorScheme = VaultColors) {
                var toDelete by remember { mutableStateOf<File?>(null) }
                var confirmNoteDelete by remember { mutableStateOf(false) }
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val uiScope = rememberCoroutineScope()
                val isUnlocked = unlocked
                // Screenshots and Recents preview are blocked only while the vault is on screen
                SideEffect { setSecure(isUnlocked) }
                LaunchedEffect(isUnlocked) {
                    if (!isUnlocked) drawerState.snapTo(DrawerValue.Closed)
                }

                Box(Modifier.fillMaxSize().background(if (isUnlocked) Bg else Color.Black)) {
                    when {
                        !isUnlocked -> Box(Modifier.fillMaxSize().systemBarsPadding()) {
                            key(calcKey) {
                                CalculatorScreen(
                                    setupMode = !pinSet,
                                    onSetup = { pin -> doSetup(pin) },
                                    onUnlock = { pin -> tryUnlock(pin) }
                                )
                            }
                        }

                        !hasAllFiles -> Box(Modifier.fillMaxSize().systemBarsPadding()) {
                            PermissionScreen {
                                startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:$packageName")
                                    )
                                )
                            }
                        }

                        else -> ModalNavigationDrawer(
                            drawerState = drawerState,
                            drawerContent = {
                                ModalDrawerSheet(drawerContainerColor = Surf) {
                                    Row(
                                        Modifier.padding(start = 24.dp, end = 24.dp, top = 32.dp, bottom = 24.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        AppBadge(48.dp)
                                        Spacer(Modifier.width(14.dp))
                                        Text(
                                            APP_NAME,
                                            color = TextPri, fontSize = 17.sp, fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    val itemColors = NavigationDrawerItemDefaults.colors(
                                        selectedContainerColor = Accent1.copy(alpha = 0.25f),
                                        unselectedContainerColor = Color.Transparent,
                                        selectedIconColor = Accent1,
                                        unselectedIconColor = TextSec,
                                        selectedTextColor = TextPri,
                                        unselectedTextColor = TextSec
                                    )
                                    NavigationDrawerItem(
                                        label = { Text("Photos & Videos") },
                                        icon = { Icon(Icons.Filled.Lock, null) },
                                        selected = section == 0,
                                        onClick = {
                                            selectSection(0)
                                            uiScope.launch { drawerState.close() }
                                        },
                                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                                        colors = itemColors
                                    )
                                    NavigationDrawerItem(
                                        label = { Text("Notes") },
                                        icon = { Icon(Icons.Filled.Edit, null) },
                                        selected = section == 1,
                                        onClick = {
                                            selectSection(1)
                                            uiScope.launch { drawerState.close() }
                                        },
                                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                                        colors = itemColors
                                    )
                                }
                            }
                        ) {
                            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                                if (section == 0) {
                                    VaultScreen(
                                        photos = photos,
                                        busyText = busyText,
                                        onMenu = { uiScope.launch { drawerState.open() } },
                                        onAdd = {
                                            suppressLock = true
                                            picker.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                            )
                                        },
                                        onLock = { lockNow() },
                                        onOpen = { viewIndex = it }
                                    )
                                } else {
                                    NotesScreen(
                                        notes = notes,
                                        unlocked = notesUnlocked,
                                        onMenu = { uiScope.launch { drawerState.open() } },
                                        onTitleLongPress = { onNotesTitleLongPress() },
                                        onAdd = { editing = NoteDraft(null, "", "") },
                                        onOpen = { row ->
                                            row.data?.let { editing = NoteDraft(row.id, it.title, it.body) }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    val vi = viewIndex
                    if (isUnlocked && hasAllFiles && section == 0 && vi != null && photos.isNotEmpty()) {
                        Viewer(
                            photos = photos,
                            startIndex = vi,
                            onClose = { viewIndex = null },
                            onRestore = { restoreToGallery(it) },
                            onDelete = { toDelete = it }
                        )
                    }

                    val ed = editing
                    if (isUnlocked && notesUnlocked && ed != null) {
                        NoteEditor(
                            draft = ed,
                            onClose = { closeEditor() },
                            onDelete = { confirmNoteDelete = true }
                        )
                    }

                    if (isUnlocked && notesPasswordOpen) {
                        NotesPasswordPage(
                            setup = !NotesCrypto.isSet(this@MainActivity),
                            onSetup = { pw -> notesSetup(pw) },
                            onUnlock = { pw -> notesUnlock(pw) },
                            onCancel = { notesPasswordOpen = false }
                        )
                    }

                    toDelete?.let { f ->
                        AlertDialog(
                            onDismissRequest = { toDelete = null },
                            containerColor = Surf,
                            title = { Text("Delete permanently?") },
                            text = { Text("This item will be gone for good. This cannot be undone.", color = TextSec) },
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

                    if (confirmNoteDelete && ed != null) {
                        AlertDialog(
                            onDismissRequest = { confirmNoteDelete = false },
                            containerColor = Surf,
                            title = { Text("Delete this note?") },
                            text = { Text("It will be gone for good. This cannot be undone.", color = TextSec) },
                            confirmButton = {
                                TextButton(onClick = {
                                    confirmNoteDelete = false
                                    deleteEditingNote()
                                }) { Text("Delete", color = Danger) }
                            },
                            dismissButton = {
                                TextButton(onClick = { confirmNoteDelete = false }) { Text("Cancel", color = TextSec) }
                            }
                        )
                    }
                }
            }
        }
    }

    private fun setSecure(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
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
        flushDeletes()
        if (unlocked && hasAllFiles) afterUnlock()
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app (Home / Recents / screen off) locks the vault and the notes
        if (!suppressLock) lockNow()
    }

    // ------------------------------------------------------------ lock / unlock

    private fun lockNow() {
        lockNotes(refresh = false)
        section = 0
        unlocked = false
        viewIndex = null
        calcKey++ // clears whatever was typed on the calculator
        ThumbCache.clear()
        // If an import is still running, the key is cleared as soon as it finishes
        if (importing == 0) VaultCrypto.lock()
    }

    private suspend fun tryUnlock(pin: String): Boolean {
        if (VaultCrypto.lockoutMs(this) > 0) return false
        val ok = withContext(Dispatchers.Default) { VaultCrypto.unlock(this@MainActivity, pin) }
        if (ok) {
            unlocked = true
            afterUnlock()
        }
        return ok
    }

    private suspend fun doSetup(pin: String) {
        withContext(Dispatchers.Default) { VaultCrypto.setupPin(this@MainActivity, pin) }
        pinSet = true
        unlocked = true
        afterUnlock()
    }

    private fun afterUnlock() {
        hasAllFiles = Environment.isExternalStorageManager()
        if (!hasAllFiles) return
        lifecycleScope.launch {
            photos = withContext(Dispatchers.IO) { listSorted() }
            processPending()
        }
    }

    private fun listSorted() = VaultCrypto.listItems().sortedByDescending { it.lastModified() }

    // ------------------------------------------------------------ notes

    private fun selectSection(i: Int) {
        if (section == 1 && i != 1) lockNotes()
        section = i
        if (i == 1) refreshNotes()
    }

    private fun refreshNotes() {
        lifecycleScope.launch {
            notes = withContext(Dispatchers.IO) { NotesCrypto.list(this@MainActivity) }
        }
    }

    // Press and hold the "Notes" title: opens the password page, or locks the notes again
    private fun onNotesTitleLongPress() {
        if (notesUnlocked) lockNotes() else notesPasswordOpen = true
    }

    private fun lockNotes(refresh: Boolean = true) {
        saveDraftNow() // never lose what was being typed
        NotesCrypto.lock()
        notesUnlocked = false
        notesPasswordOpen = false
        editing = null
        notes = notes.map { NoteRow(it.id, it.modified, it.cipher, null) } // drop readable text from memory
        if (refresh) refreshNotes()
    }

    private suspend fun notesSetup(pw: String) {
        withContext(Dispatchers.Default) { NotesCrypto.setPassword(this@MainActivity, pw) }
        onNotesUnlocked()
    }

    private suspend fun notesUnlock(pw: String): String? {
        val wait = NotesCrypto.lockoutMs(this)
        if (wait > 0) return "Too many attempts. Try again in ${(wait + 999) / 1000}s"
        val ok = withContext(Dispatchers.Default) { NotesCrypto.unlock(this@MainActivity, pw) }
        if (ok) {
            onNotesUnlocked()
            return null
        }
        val w2 = NotesCrypto.lockoutMs(this)
        return if (w2 > 0) "Too many attempts. Try again in ${(w2 + 999) / 1000}s" else "Incorrect password"
    }

    private fun onNotesUnlocked() {
        notesUnlocked = true
        notesPasswordOpen = false
        refreshNotes()
    }

    private fun saveDraftNow() {
        val d = editing ?: return
        if (!NotesCrypto.isUnlocked()) return
        if (d.id == null && d.title.isBlank() && d.body.isBlank()) return
        runCatching { NotesCrypto.save(this, d.id, d.title, d.body) }.onSuccess { d.id = it }
    }

    private fun closeEditor() {
        saveDraftNow()
        editing = null
        refreshNotes()
    }

    private fun deleteEditingNote() {
        val d = editing ?: return
        d.id?.let { NotesCrypto.delete(this, it) }
        editing = null
        refreshNotes()
    }

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

    // Shared items wait here until the vault is opened
    private fun processPending() {
        if (!unlocked || !hasAllFiles || pendingShared.isEmpty()) return
        val uris = pendingShared.toList()
        pendingShared.clear()
        importUris(uris, deleteOriginals = true)
    }

    private fun importUris(uris: List<Uri>, deleteOriginals: Boolean) {
        if (!unlocked || !hasAllFiles) return
        lifecycleScope.launch {
            importing++
            val done = ArrayList<Uri>()
            var failed = 0
            try {
                for ((i, u) in uris.withIndex()) {
                    busyText = "Importing ${i + 1} of ${uris.size}…"
                    val ok = withContext(Dispatchers.IO) {
                        runCatching { VaultCrypto.importMedia(this@MainActivity, u) }.isSuccess
                    }
                    if (ok) {
                        done.add(u)
                        photos = withContext(Dispatchers.IO) { listSorted() }
                    } else failed++
                }
            } finally {
                busyText = null
                importing--
                if (importing == 0 && !unlocked) VaultCrypto.lock()
            }
            if (failed > 0) {
                Toast.makeText(this@MainActivity, "$failed file(s) could not be imported", Toast.LENGTH_LONG).show()
            }
            if (deleteOriginals) {
                pendingDelete.addAll(done.filter { it.authority == "media" })
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) flushDeletes()
            }
        }
    }

    private fun flushDeletes() {
        if (pendingDelete.isEmpty()) return
        val l = pendingDelete.toList()
        pendingDelete.clear()
        requestDelete(l)
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
        VaultCrypto.deleteItem(f)
        photos = photos - f
        if (photos.isEmpty()) viewIndex = null
    }

    private fun restoreToGallery(f: File) {
        lifecycleScope.launch {
            busyText = "Restoring…"
            val ok = withContext(Dispatchers.IO) { restore(f) }
            busyText = null
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
        val info = VaultCrypto.info(f)
        val mime = info.mime
        val ext = when (val sub = mime.substringAfter('/').substringBefore(';')) {
            "jpeg" -> "jpg"
            "quicktime" -> "mov"
            "x-matroska" -> "mkv"
            "3gpp" -> "3gp"
            else -> sub
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Restored_${System.currentTimeMillis()}.$ext")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                if (info.isVideo) "Movies/Restored" else "Pictures/Restored"
            )
        }
        val collection = if (info.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = contentResolver.insert(collection, values)
            ?: throw IllegalStateException("insert failed")
        try {
            contentResolver.openOutputStream(uri)!!.use { VaultCrypto.copyDecrypted(f, it) }
        } catch (e: Throwable) {
            contentResolver.delete(uri, null, null)
            throw e
        }
        VaultCrypto.deleteItem(f) // remove from vault only after the Gallery copy is written
        true
    }.getOrDefault(false)
}

// ============================================================ calculator (the disguise)

private val CalcBg = Color(0xFF000000)
private val CalcKey = Color(0xFF2B2B2F)
private val CalcFn = Color(0xFF4A4A50)
private val CalcOp = Color(0xFFFF9500)
private val CalcGray = Color(0xFF8E8E93)
private const val OPS = "+−×÷"

@Composable
fun CalculatorScreen(
    setupMode: Boolean,
    onSetup: suspend (String) -> Unit,
    onUnlock: suspend (String) -> Boolean
) {
    var expr by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf("") }
    var evaluated by remember { mutableStateOf(false) }
    var first by remember { mutableStateOf<String?>(null) }
    var hint by remember {
        mutableStateOf<String?>(if (setupMode) "Set a password: type 4-8 digits, then press =" else null)
    }
    val scope = rememberCoroutineScope()

    fun clearAll() {
        expr = ""
        shown = ""
        evaluated = false
    }

    fun onDigit(c: String) {
        if (evaluated || expr == "Error") {
            expr = if (c == ".") "0." else c
            shown = ""
            evaluated = false
            return
        }
        if (expr.length >= 40) return
        if (c == ".") {
            val seg = expr.takeLastWhile { it.isDigit() || it == '.' }
            if (seg.contains('.')) return
            expr += if (seg.isEmpty()) "0." else "."
            return
        }
        val last = expr.lastOrNull()
        expr += if (last == ')' || last == '%') "×$c" else c
    }

    fun onOp(o: String) {
        if (expr == "Error") return
        if (evaluated) {
            evaluated = false
            shown = ""
        }
        if (expr.isEmpty()) {
            if (o == "−") expr = "−"
            return
        }
        val last = expr.last()
        when {
            o == "−" && (last == '×' || last == '÷') -> expr += o
            OPS.contains(last) -> {
                if (expr.length == 1) return
                expr = expr.dropLast(1) + o
            }
            last == '(' -> if (o == "−") expr += o
            else -> expr += o
        }
    }

    fun onPercent() {
        if (expr == "Error" || expr.isEmpty()) return
        if (evaluated) {
            evaluated = false
            shown = ""
        }
        val last = expr.last()
        if (last.isDigit() || last == ')' || last == '%') expr += "%"
    }

    fun onParen() {
        if (evaluated || expr == "Error") {
            expr = "("
            shown = ""
            evaluated = false
            return
        }
        val open = expr.count { it == '(' }
        val close = expr.count { it == ')' }
        val last = expr.lastOrNull()
        val valueEnd = last != null && (last.isDigit() || last == ')' || last == '%')
        expr += when {
            open > close && valueEnd -> ")"
            valueEnd || last == '.' -> "×("
            else -> "("
        }
    }

    fun onBack() {
        if (evaluated || expr == "Error") {
            clearAll()
            return
        }
        expr = expr.dropLast(1)
    }

    fun onEquals() {
        if (evaluated || expr == "Error" || expr.isEmpty()) return
        val e = expr
        if (e.all { it.isDigit() }) {
            if (setupMode) {
                if (e.length < 4 || e.length > 8) {
                    hint = "Password must be 4 to 8 digits"
                    expr = ""
                    return
                }
                val f = first
                when {
                    f == null -> {
                        first = e
                        expr = ""
                        hint = "Confirm your password, then press ="
                    }
                    f == e -> {
                        expr = ""
                        hint = null
                        scope.launch { onSetup(e) }
                    }
                    else -> {
                        first = null
                        expr = ""
                        hint = "Passwords did not match. Enter a password, then press ="
                    }
                }
                return
            }
            if (e.length in 4..8) {
                scope.launch {
                    if (onUnlock(e)) {
                        clearAll()
                    } else {
                        // Wrong code: behave exactly like a normal calculator
                        shown = e
                        evaluated = true
                    }
                }
                return
            }
        }
        val r = calcEvaluate(e)
        shown = e
        expr = r ?: "Error"
        evaluated = true
    }

    Column(Modifier.fillMaxSize().background(CalcBg).padding(horizontal = 16.dp)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(bottom = 8.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.End
        ) {
            val h = hint
            if (h != null) {
                Text(
                    h,
                    color = CalcGray, fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                )
            }
            if (shown.isNotEmpty()) {
                Text(shown, color = CalcGray, fontSize = 22.sp, maxLines = 1)
            }
            val big = if (expr.isEmpty()) "0" else expr
            Text(
                big,
                color = Color.White,
                fontSize = when {
                    big.length > 14 -> 34.sp
                    big.length > 9 -> 48.sp
                    else -> 64.sp
                },
                maxLines = 2,
                textAlign = TextAlign.End
            )
        }

        CalcRow {
            CalcButton("AC", CalcFn) { clearAll() }
            CalcButton("( )", CalcFn) { onParen() }
            CalcButton("%", CalcFn) { onPercent() }
            CalcButton("÷", CalcOp) { onOp("÷") }
        }
        CalcRow {
            CalcButton("7", CalcKey) { onDigit("7") }
            CalcButton("8", CalcKey) { onDigit("8") }
            CalcButton("9", CalcKey) { onDigit("9") }
            CalcButton("×", CalcOp) { onOp("×") }
        }
        CalcRow {
            CalcButton("4", CalcKey) { onDigit("4") }
            CalcButton("5", CalcKey) { onDigit("5") }
            CalcButton("6", CalcKey) { onDigit("6") }
            CalcButton("−", CalcOp) { onOp("−") }
        }
        CalcRow {
            CalcButton("1", CalcKey) { onDigit("1") }
            CalcButton("2", CalcKey) { onDigit("2") }
            CalcButton("3", CalcKey) { onDigit("3") }
            CalcButton("+", CalcOp) { onOp("+") }
        }
        CalcRow {
            CalcButton("←", CalcFn) { onBack() }
            CalcButton("0", CalcKey) { onDigit("0") }
            CalcButton(".", CalcKey) { onDigit(".") }
            CalcButton("=", CalcOp) { onEquals() }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun CalcRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
private fun RowScope.CalcButton(label: String, bg: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Medium)
    }
}

/** Evaluates an expression like 12×15+3% . Returns null if it is invalid. */
fun calcEvaluate(s: String): String? = try {
    var t = s
    while (t.isNotEmpty() && OPS.contains(t.last())) t = t.dropLast(1)
    val missing = t.count { it == '(' } - t.count { it == ')' }
    if (missing > 0) t += ")".repeat(missing)
    if (t.isEmpty()) {
        null
    } else {
        var r = CalcParser(t).parse().round(MathContext(15)).stripTrailingZeros()
        if (r.signum() == 0) r = BigDecimal.ZERO
        r.toPlainString().replace('-', '−')
    }
} catch (e: Exception) {
    null
}

private class CalcParser(private val s: String) {
    private var i = 0

    fun parse(): BigDecimal {
        val v = expr()
        if (i != s.length) throw IllegalArgumentException("bad expression")
        return v
    }

    private fun expr(): BigDecimal {
        var v = term()
        while (i < s.length && (s[i] == '+' || s[i] == '−')) {
            val op = s[i++]
            val start = i
            val r = term()
            val seg = s.substring(start, i)
            // 200+10% means 200 + 10% of 200
            val pct = seg.endsWith("%") && !seg.contains('×') && !seg.contains('÷')
            val rr = if (pct) v.multiply(r) else r
            v = if (op == '+') v.add(rr) else v.subtract(rr)
        }
        return v
    }

    private fun term(): BigDecimal {
        var v = unary()
        while (i < s.length && (s[i] == '×' || s[i] == '÷')) {
            val op = s[i++]
            val r = unary()
            v = if (op == '×') {
                v.multiply(r)
            } else {
                if (r.signum() == 0) throw ArithmeticException("divide by zero")
                v.divide(r, MathContext(15))
            }
        }
        return v
    }

    private fun unary(): BigDecimal {
        if (i < s.length && s[i] == '−') {
            i++
            return unary().negate()
        }
        if (i < s.length && s[i] == '+') {
            i++
            return unary()
        }
        return postfix()
    }

    private fun postfix(): BigDecimal {
        var v = primary()
        while (i < s.length && s[i] == '%') {
            i++
            v = v.divide(BigDecimal(100))
        }
        return v
    }

    private fun primary(): BigDecimal {
        if (i < s.length && s[i] == '(') {
            i++
            val v = expr()
            if (i >= s.length || s[i] != ')') throw IllegalArgumentException("missing )")
            i++
            return v
        }
        val st = i
        while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
        if (st == i) throw IllegalArgumentException("number expected")
        return BigDecimal(s.substring(st, i))
    }
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
                "Your files stay encrypted at all times.",
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
    busyText: String?,
    onMenu: () -> Unit,
    onAdd: () -> Unit,
    onLock: () -> Unit,
    onOpen: (Int) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconCircleButton(Icons.Filled.Menu, accent = false, onClick = onMenu)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(APP_NAME, color = TextPri, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    if (photos.isEmpty()) "Private vault" else "${photos.size} items  •  AES-256 encrypted",
                    color = TextSec, fontSize = 12.sp, maxLines = 1
                )
            }
            IconCircleButton(Icons.Filled.Add, accent = true, onClick = onAdd)
            Spacer(Modifier.width(8.dp))
            IconCircleButton(Icons.Filled.Lock, accent = false, onClick = onLock)
        }

        if (busyText != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Accent1, trackColor = Surf)
            Text(
                busyText,
                color = TextSec, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )
        } else {
            Spacer(Modifier.height(2.dp))
        }

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
            "Share photos and videos from your Gallery to this app, or tap below. They'll be locked away safely.",
            color = TextSec, fontSize = 14.sp, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        GradientButton("Add photos & videos", Icons.Filled.Add, onAdd)
    }
}

@Composable
fun PlayBadge(modifier: Modifier) {
    Box(
        modifier.size(28.dp).clip(CircleShape).background(Color(0x99000000)),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun Thumb(f: File, onClick: () -> Unit) {
    val info by produceState<ItemInfo?>(ThumbCache.get(f.name), f) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { runCatching { VaultCrypto.info(f) }.getOrNull() }
                ?.also { ThumbCache.put(f.name, it) }
        }
    }
    val i = info
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(Surf)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val t = i?.thumb
        if (t != null) {
            Image(t.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else if (i != null) {
            Icon(Icons.Filled.Lock, null, tint = TextSec, modifier = Modifier.size(28.dp))
        }
        if (i?.isVideo == true) PlayBadge(Modifier.align(Alignment.BottomStart).padding(6.dp))
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
            photos.getOrNull(page)?.let { MediaPage(it, active = pager.currentPage == page) }
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
fun MediaPage(f: File, active: Boolean) {
    val info by produceState<ItemInfo?>(ThumbCache.get(f.name), f) {
        if (value == null) {
            val loaded = withContext(Dispatchers.IO) { runCatching { VaultCrypto.info(f) }.getOrNull() }
                ?: ItemInfo(false, "image/jpeg", null)
            ThumbCache.put(f.name, loaded)
            value = loaded
        }
    }
    val i = info
    when {
        i == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Accent1)
        }
        i.isVideo && f.extension == "vlt" -> Box(
            Modifier.fillMaxSize().padding(40.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "This video was saved by an older version of the app and can't be played here. " +
                    "Tap Restore to move it back to your Gallery.",
                color = Color.White, textAlign = TextAlign.Center
            )
        }
        i.isVideo -> if (active) ActivePlayer(f) else Box(
            Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) { PlayBadge(Modifier) }
        else -> ZoomableImage(f)
    }
}

@Composable
fun ActivePlayer(f: File) {
    val ctx = LocalContext.current
    val exo = remember(f) {
        ExoPlayer.Builder(ctx).build().apply {
            val src = ProgressiveMediaSource.Factory(DataSource.Factory { VaultDataSource() })
                .createMediaSource(MediaItem.fromUri(Uri.fromFile(f)))
            setMediaSource(src)
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(exo) { onDispose { exo.release() } }
    AndroidView(
        factory = { c ->
            PlayerView(c).apply {
                player = exo
                useController = true
            }
        },
        modifier = Modifier.fillMaxSize().padding(top = 64.dp, bottom = 84.dp)
    )
}

@Composable
fun ZoomableImage(f: File) {
    val loaded by produceState<Pair<Boolean, Bitmap?>?>(null, f) {
        value = true to withContext(Dispatchers.IO) { loadBitmap(f, 2048) }
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
        val l = loaded
        val b = l?.second
        when {
            l == null -> CircularProgressIndicator(color = Accent1)
            b == null -> Text("Can't display this file", color = TextSec)
            else -> Image(
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

fun loadBitmap(f: File, maxDim: Int): Bitmap? =
    runCatching { decodeSampled(VaultCrypto.readBytes(f), maxDim) }.getOrNull()

// ============================================================ video: decrypting data source

class VaultDataSource : BaseDataSource(false) {
    private var reader: VaultReader? = null
    private var currentUri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        currentUri = dataSpec.uri
        transferInitializing(dataSpec)
        val r = VaultCrypto.openReader(File(dataSpec.uri.path!!))
        reader = r
        position = dataSpec.position
        if (position > r.size) {
            r.close()
            reader = null
            throw IOException("Position out of range")
        }
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) r.size - position
        else minOf(dataSpec.length, r.size - position)
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val n = reader!!.readAt(position, buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (n <= 0) return C.RESULT_END_OF_INPUT
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        try {
            reader?.close()
        } finally {
            reader = null
            currentUri = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
