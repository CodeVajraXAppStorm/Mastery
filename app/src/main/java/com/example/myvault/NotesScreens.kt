package com.example.myvault

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** The note being edited. Kept by the activity so it can be saved when the app locks. */
class NoteDraft(var id: String?, title: String, body: String) {
    var title by mutableStateOf(title)
    var body by mutableStateOf(body)
}

// ============================================================ notes list

@Composable
fun NotesScreen(
    notes: List<NoteRow>,
    unlocked: Boolean,
    onMenu: () -> Unit,
    onTitleLongPress: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (NoteRow) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconCircleButton(Icons.Filled.Menu, accent = false, onClick = onMenu)
            Spacer(Modifier.width(12.dp))
            // Press and hold this title to open the password page
            Text(
                "Notes",
                color = TextPri, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .weight(1f)
                    .pointerInput(Unit) { detectTapGestures(onLongPress = { onTitleLongPress() }) }
                    .padding(vertical = 10.dp)
            )
            IconCircleButton(Icons.Filled.Add, accent = true, onClick = onAdd)
        }

        if (notes.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(40.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("No notes yet", color = TextPri, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tap + to write your first note.",
                    color = TextSec, fontSize = 14.sp, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
                GradientButton("New note", Icons.Filled.Add, onAdd)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(notes, key = { it.id }) { n -> NoteCard(n, unlocked) { onOpen(n) } }
            }
        }
    }
}

@Composable
private fun NoteCard(row: NoteRow, unlocked: Boolean, onClick: () -> Unit) {
    val d = row.data
    val readable = unlocked && d != null
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surf)
            .then(if (readable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp)
    ) {
        if (readable && d != null) {
            Text(
                d.title.ifBlank { "Untitled" },
                color = TextPri, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (d.body.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    d.body,
                    color = TextSec, fontSize = 14.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            // Locked: show the real encrypted bytes
            Text(
                row.cipher.take(24),
                color = TextPri.copy(alpha = 0.8f), fontSize = 16.sp,
                fontFamily = FontFamily.Monospace, maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            Text(
                row.cipher.drop(24),
                color = TextSec, fontSize = 13.sp,
                fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(row.modified)),
            color = TextSec.copy(alpha = 0.7f), fontSize = 11.sp
        )
    }
}

// ============================================================ note editor

@Composable
fun NoteEditor(draft: NoteDraft, onClose: () -> Unit, onSave: () -> Unit, onDelete: () -> Unit) {
    BackHandler(onBack = onClose)
    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .pointerInput(Unit) { detectTapGestures(onTap = { }) }
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconCircleButton(Icons.Filled.Close, accent = false, onClick = onClose)
            Spacer(Modifier.weight(1f))
            IconCircleButton(Icons.Filled.Delete, accent = false, onClick = onDelete)
            Spacer(Modifier.width(8.dp))
            IconCircleButton(Icons.Filled.Check, accent = true, onClick = onSave)
        }
        BasicTextField(
            value = draft.title,
            onValueChange = { draft.title = it },
            textStyle = TextStyle(color = TextPri, fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
            cursorBrush = SolidColor(Accent1),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box {
                    if (draft.title.isEmpty()) {
                        Text("Title", color = TextSec, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    }
                    inner()
                }
            }
        )
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            BasicTextField(
                value = draft.body,
                onValueChange = { draft.body = it },
                textStyle = TextStyle(color = TextPri, fontSize = 17.sp, lineHeight = 26.sp),
                cursorBrush = SolidColor(Accent1),
                modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp),
                decorationBox = { inner ->
                    Box {
                        if (draft.body.isEmpty()) {
                            Text("Write something…", color = TextSec, fontSize = 17.sp)
                        }
                        inner()
                    }
                }
            )
        }
    }
}

// ============================================================ notes password page

@Composable
fun NotesPasswordPage(
    setup: Boolean,
    onSetup: suspend (String) -> Unit,
    onUnlock: suspend (String) -> String?,
    onCancel: () -> Unit
) {
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }

    BackHandler(onBack = onCancel)
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { focus.requestFocus() }
    }

    fun submit() {
        if (busy) return
        if (setup) {
            if (pw.length < 4) {
                error = "Use at least 4 characters"
                return
            }
            if (pw != pw2) {
                error = "Passwords did not match"
                return
            }
            busy = true
            scope.launch { onSetup(pw) }
        } else {
            if (pw.isEmpty()) return
            busy = true
            scope.launch {
                val e = onUnlock(pw)
                if (e != null) {
                    error = e
                    pw = ""
                    busy = false
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .pointerInput(Unit) { detectTapGestures(onTap = { }) }
            .systemBarsPadding()
            .imePadding()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AppBadge(64.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            if (setup) "Create a password" else "Enter password",
            color = TextPri, fontSize = 20.sp, fontWeight = FontWeight.SemiBold
        )
        if (setup) {
            Spacer(Modifier.height(6.dp))
            Text(
                "If you forget it, these notes cannot be recovered.",
                color = TextSec, fontSize = 13.sp, textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = pw,
            onValueChange = {
                pw = it
                error = null
            },
            label = { Text("Password") },
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = if (setup) ImeAction.Next else ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus)
        )
        if (setup) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = pw2,
                onValueChange = {
                    pw2 = it
                    error = null
                },
                label = { Text("Confirm password") },
                singleLine = true,
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth()
            )
        }
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = Danger, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(24.dp))
        GradientButton(
            if (busy) "Please wait…" else if (setup) "Save" else "Unlock",
            null
        ) { submit() }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onCancel) { Text("Cancel", color = TextSec) }
    }
}

// ============================================================ recovery code screen

@Composable
fun RecoveryScreen(code: String?, banner: Boolean, onMenu: () -> Unit, onNewCode: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconCircleButton(Icons.Filled.Menu, accent = false, onClick = onMenu)
            Spacer(Modifier.width(12.dp))
            Text("Recovery code", color = TextPri, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }

        if (banner) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Danger.copy(alpha = 0.15f))
                    .padding(16.dp)
            ) {
                Text(
                    "Write this code down now and keep it safe. It is the only way to get your photos, " +
                        "videos and notes back if you uninstall the app or forget your PIN.",
                    color = TextPri, fontSize = 14.sp
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Surf).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (code == null) {
                Text("Code not available. Create a new one below.", color = TextSec, textAlign = TextAlign.Center)
            } else {
                code.split("-").chunked(3).forEach { row ->
                    Text(
                        row.joinToString("   "),
                        color = TextPri, fontSize = 22.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        if (code != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                GradientButton("Copy code", null) {
                    clipboard.setText(AnnotatedString(code))
                    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = onNewCode) { Text("Create a new code", color = TextSec) }
        }

        Spacer(Modifier.height(20.dp))
        Text("How to restore", color = TextPri, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "1. Install the app again.\n" +
                "2. Open the calculator and press and hold the numbers on the screen.\n" +
                "3. Enter this code and choose a new PIN.\n\n" +
                "The .MyVault folder must still be on the phone. Do not delete it.",
            color = TextSec, fontSize = 14.sp, lineHeight = 22.sp
        )
        Spacer(Modifier.height(32.dp))
    }
}

// ============================================================ restore page (opened from the calculator)

@Composable
fun RecoverPage(
    hasAllFiles: Boolean,
    onGrant: () -> Unit,
    onRecover: suspend (String, String) -> String?,
    onCancel: () -> Unit
) {
    var code by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onCancel)

    fun submit() {
        if (busy) return
        val c = code.uppercase().filter { it.isLetterOrDigit() }
        when {
            c.length != 24 -> error = "The code has 24 letters and numbers"
            pin.length < 4 -> error = "PIN must be 4 to 8 digits"
            pin != pin2 -> error = "PINs did not match"
            else -> {
                busy = true
                error = null
                scope.launch {
                    val e = onRecover(code, pin)
                    if (e != null) {
                        error = e
                        busy = false
                    }
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .pointerInput(Unit) { detectTapGestures(onTap = { }) }
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        AppBadge(64.dp)
        Spacer(Modifier.height(20.dp))
        Text("Restore your vault", color = TextPri, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Enter the recovery code you saved. Your photos, videos and notes will come back.",
            color = TextSec, fontSize = 13.sp, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))

        if (!hasAllFiles) {
            Text(
                "First allow \"All files access\" so the app can find your .MyVault folder.",
                color = TextSec, fontSize = 14.sp, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            GradientButton("Grant access", null, onGrant)
        } else {
            OutlinedTextField(
                value = code,
                onValueChange = {
                    code = it.uppercase().take(40)
                    error = null
                },
                label = { Text("Recovery code") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = {
                    pin = it.filter { c -> c.isDigit() }.take(8)
                    error = null
                },
                label = { Text("New PIN (4 to 8 digits)") },
                singleLine = true,
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = pin2,
                onValueChange = {
                    pin2 = it.filter { c -> c.isDigit() }.take(8)
                    error = null
                },
                label = { Text("Confirm PIN") },
                singleLine = true,
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth()
            )
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = Danger, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(24.dp))
            GradientButton(if (busy) "Restoring…" else "Restore", null) { submit() }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onCancel) { Text("Cancel", color = TextSec) }
    }
}
