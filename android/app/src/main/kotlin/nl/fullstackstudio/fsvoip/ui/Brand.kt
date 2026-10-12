// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.sipengine.RegistrationFailure
import nl.fullstackstudio.fsvoip.sipengine.RegistrationState

/**
 * FullStack Studio house style for FSVoip (the same values as iOS `Brand.swift`). System colours carry the app; lime is
 * the accent and is spent on exactly three things: the call button, the "connected" light of a line and the primary
 * action of the pairing flow.
 */
object Brand {
    /** `#c7ff4a` */
    val lime = Color(0xFFC7FF4A)

    /** `#101317`, text on lime and the in-call background. */
    val ink = Color(0xFF101317)

    /** `#1d232b`, the lighter end of the in-call background. */
    val inkRaised = Color(0xFF1D232B)

    /** "Connecting": a warm amber that reads on light and dark. */
    val amber = Color(0xFFE8A23A)

    /** Hang up / decline. */
    val hangUp = Color(0xFFEB4034)
}

@Composable
fun FsVoipTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) {
        darkColorScheme(
            primary = Color.White,
            onPrimary = Brand.ink,
            secondary = Brand.lime,
            onSecondary = Brand.ink,
            error = Brand.hangUp,
            background = Color.Black,
            surface = Color.Black,
            surfaceContainer = Color(0xFF1C1C1E),
            surfaceVariant = Color(0xFF2C2C2E),
            secondaryContainer = Color(0xFF2C2C2E),
            onSecondaryContainer = Color.White,
        )
    } else {
        // Neutral, iOS-like greys instead of Material's tinted surfaces: the lime accent stays the only colour.
        lightColorScheme(
            primary = Brand.ink,
            onPrimary = Color.White,
            secondary = Brand.lime,
            onSecondary = Brand.ink,
            error = Brand.hangUp,
            background = Color.White,
            surface = Color.White,
            surfaceContainer = Color(0xFFF7F7F9),
            surfaceVariant = Color(0xFFF0F0F3),
            secondaryContainer = Color(0xFFE9E9ED),
            onSecondaryContainer = Brand.ink,
        )
    }

    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Brand.lime, contentColor = Brand.ink, disabledContainerColor = Brand.lime.copy(alpha = 0.4f), disabledContentColor = Brand.ink.copy(alpha = 0.6f)),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Box(Modifier.size(8.dp))
        }
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Box(Modifier.size(8.dp))
        }
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

val RegistrationState.tint: Color
    get() = when (this) {
        RegistrationState.Registered -> Brand.lime
        RegistrationState.Registering -> Brand.amber
        is RegistrationState.Failed -> Brand.hangUp
        RegistrationState.Unregistered -> Color(0xFFC7C7CC)
    }

@Composable
fun RegistrationState.label(): String = stringResource(
    when (this) {
        RegistrationState.Registered -> R.string.line_registered
        RegistrationState.Registering -> R.string.line_registering
        RegistrationState.Unregistered -> R.string.line_unregistered
        is RegistrationState.Failed -> when (failure) {
            RegistrationFailure.Authentication -> R.string.line_failed_authentication
            RegistrationFailure.Network -> R.string.line_failed_network
            is RegistrationFailure.Other -> R.string.line_failed_other
        }
    },
)

/** The light of a line: lime = connected, amber = connecting, red = problem, grey = off. */
@Composable
fun StatusLight(state: RegistrationState, size: Dp = 9.dp) {
    // Lime alone disappears on white; a hairline of ink keeps it visible in light mode.
    Box(
        Modifier
            .size(size)
            .background(state.tint, CircleShape)
            .border(BorderStroke(0.75.dp, Brand.ink.copy(alpha = 0.18f)), CircleShape),
    )
}

/** Brand mark: a handset in a lime tile. */
@Composable
fun BrandMark(size: Dp = 64.dp) {
    Box(Modifier.size(size).background(Brand.lime, RoundedCornerShape(size * 0.28f)), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Phone, contentDescription = null, tint = Brand.ink, modifier = Modifier.size(size * 0.46f))
    }
}
