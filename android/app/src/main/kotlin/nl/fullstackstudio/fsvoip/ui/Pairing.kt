// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The whole "add a line" flow in one full-screen sheet: scan (or paste) → confirm → pairing → done / problem. Nothing is
// claimed on the server before the user confirms (a scanned or tapped link only shows the confirmation).

package nl.fullstackstudio.fsvoip.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.AppModel.PairingPhase
import nl.fullstackstudio.fsvoip.R

@Composable
fun PairingSheet(model: AppModel) {
    val phase by model.pairing.collectAsStateWithLifecycle()
    val busy = phase is PairingPhase.Pairing

    Dialog(
        onDismissRequest = { if (!busy) model.dismissScanner() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !busy, dismissOnClickOutside = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            BackHandler(enabled = !busy) { model.dismissScanner() }
            DialogSystemBars()

            when (val current = phase) {
                PairingPhase.Idle -> ScannerStep(model)
                is PairingPhase.LinkReceived -> ConfirmStep(model)
                is PairingPhase.Pairing -> ProgressStep()
                is PairingPhase.Paired -> DoneStep(model, current)
                is PairingPhase.Failed -> FailedStep(model, current)
            }
        }
    }
}

/** The sheet draws behind the system bars: give them dark icons on the light sheet (and light ones in dark mode). */
@Composable
private fun DialogSystemBars() {
    val view = androidx.compose.ui.platform.LocalView.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()

    androidx.compose.runtime.SideEffect {
        val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window ?: return@SideEffect
        androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

// MARK: - Scanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScannerStep(model: AppModel) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val error by model.scannerError.collectAsStateWithLifecycle()
    var granted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var asked by remember { mutableStateOf(false) }
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    var pasted by remember { mutableStateOf("") }
    var showsPasteField by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result
        asked = true
    }

    LaunchedEffect(Unit) {
        if (hasCamera && !granted) permission.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(paused) {
        if (paused) {
            // Not ours: listen again after a moment.
            delay(1_500)
            paused = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scanner_title)) },
                navigationIcon = { TextButton(onClick = { model.dismissScanner() }) { Text(stringResource(R.string.action_cancel)) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                when {
                    !hasCamera -> CameraPlaceholder(Icons.Filled.CameraAlt, stringResource(R.string.scanner_camera_unavailable))
                    granted -> {
                        QrScannerView(paused = paused, modifier = Modifier.fillMaxSize().testTag("qr-scanner")) { code ->
                            if (model.handleScanned(code)) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            } else {
                                paused = true
                            }
                        }
                        ViewfinderCorners(Modifier.fillMaxSize().padding(36.dp))
                    }
                    !asked -> CameraPlaceholder(Icons.Filled.CameraAlt, stringResource(R.string.scanner_camera_asking))
                    else -> CameraPlaceholder(Icons.Filled.CameraAlt, stringResource(R.string.scanner_camera_denied), stringResource(R.string.scanner_camera_openSettings)) {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }

            error?.let {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("scanner-error")) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = Brand.hangUp)
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = Brand.hangUp)
                }
            }

            Text(stringResource(R.string.scanner_instructions), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (showsPasteField || !granted || !hasCamera) {
                Text(stringResource(R.string.scanner_field), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = pasted,
                    onValueChange = {
                        pasted = it
                        model.clearScannerError()
                    },
                    placeholder = { Text("https://fullstackstudio.nl/fsvoip/pair?t=…") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().testTag("link-field"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondaryButton(stringResource(R.string.scanner_paste), onClick = { clipboardText(context)?.let { pasted = it } }, icon = Icons.Filled.ContentPaste, modifier = Modifier.weight(1f))
                    PrimaryButton(stringResource(R.string.scanner_use), onClick = { model.handleScanned(pasted) }, enabled = pasted.isNotBlank(), modifier = Modifier.weight(1f).testTag("use-link-button"))
                }
            } else {
                TextButton(onClick = { showsPasteField = true }) { Text(stringResource(R.string.scanner_pasteInstead), fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun CameraPlaceholder(icon: ImageVector, text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(34.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) { Text(action, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** Four corner brackets: the target for the QR code. */
@Composable
private fun ViewfinderCorners(modifier: Modifier) {
    Canvas(modifier) {
        val side = minOf(size.width, size.height)
        val left = (size.width - side) / 2
        val top = (size.height - side) / 2
        val arm = side * 0.16f
        val stroke = 5.dp.toPx()
        fun line(a: Offset, b: Offset) = drawLine(Brand.lime, a, b, stroke, StrokeCap.Round)

        line(Offset(left, top + arm), Offset(left, top)); line(Offset(left, top), Offset(left + arm, top))
        line(Offset(left + side - arm, top), Offset(left + side, top)); line(Offset(left + side, top), Offset(left + side, top + arm))
        line(Offset(left + side, top + side - arm), Offset(left + side, top + side)); line(Offset(left + side, top + side), Offset(left + side - arm, top + side))
        line(Offset(left + arm, top + side), Offset(left, top + side)); line(Offset(left, top + side), Offset(left, top + side - arm))
    }
}

// MARK: - Steps

@Composable
private fun ConfirmStep(model: AppModel) {
    StepLayout(Icons.Filled.Link, stringResource(R.string.pairing_confirm_title), stringResource(R.string.pairing_confirm_body)) {
        PrimaryButton(stringResource(R.string.pairing_confirm_action), onClick = { model.confirmPairing() }, modifier = Modifier.testTag("pair-button"))
        SecondaryButton(stringResource(R.string.action_cancel), onClick = { model.closePairing() }, modifier = Modifier.testTag("discard-button"))
    }
}

@Composable
private fun ProgressStep() {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Text(stringResource(R.string.pairing_progress), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 18.dp))
        Text(stringResource(R.string.pairing_progress_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun DoneStep(model: AppModel, phase: PairingPhase.Paired) {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(Unit) { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }

    StepLayout(Icons.Filled.Check, stringResource(R.string.pairing_done_title), stringResource(R.string.pairing_done_body)) {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)).padding(16.dp)) {
            Text(phase.account.displayLabel, style = MaterialTheme.typography.titleMedium)
            Text(phase.account.customerName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.size(8.dp))
        PrimaryButton(stringResource(R.string.pairing_done_action), onClick = { model.closePairing() }, modifier = Modifier.testTag("pairing-done-button"))
    }
}

@Composable
private fun FailedStep(model: AppModel, phase: PairingPhase.Failed) {
    val canRetry = phase.link != null && phase.failure.isRetryable

    StepLayout(Icons.Filled.PriorityHigh, stringResource(R.string.pairing_failed_title), model.message(phase.failure), tint = Brand.hangUp.copy(alpha = 0.14f), symbolColor = Brand.hangUp) {
        if (canRetry) {
            PrimaryButton(stringResource(R.string.action_retry), onClick = { model.confirmPairing() })
        } else {
            PrimaryButton(stringResource(R.string.pairing_failed_scanAgain), onClick = { model.restartScan() })
        }
        SecondaryButton(stringResource(R.string.action_close), onClick = { model.closePairing() })
    }
}

/** Icon, title, text, actions at the bottom: the shape every pairing step shares. */
@Composable
private fun StepLayout(
    icon: ImageVector,
    title: String,
    body: String,
    tint: Color = Brand.lime,
    symbolColor: Color = Brand.ink,
    actions: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(64.dp).background(tint, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = symbolColor, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.size(24.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.size(10.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { actions() }
    }
}
