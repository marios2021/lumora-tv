package com.lumora.tv.ui.common

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** True on Android TV / Google TV; false on phones and tablets (touch first). */
val LocalIsTv = staticCompositionLocalOf { true }

/** Every screen is laid out for a TV: 960 × 540 dp. */
private const val DESIGN_HEIGHT_DP = 540f

fun isTvDevice(context: Context): Boolean {
    val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
    return uiMode.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}

/**
 * Makes the TV layout fit shorter screens. A phone in landscape is only ~360–420 dp tall, so the
 * density is reduced until the screen is 540 dp tall: the same layout, proportionally smaller.
 * TVs and tablets (540 dp or taller) are left as they are.
 */
@Composable
fun AdaptiveLayout(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val isTv = remember(context) { isTvDevice(context) }
    val heightDp = LocalConfiguration.current.screenHeightDp.toFloat()
    val density = LocalDensity.current
    val scaled = if (heightDp in 1f..<DESIGN_HEIGHT_DP) {
        Density(density.density * heightDp / DESIGN_HEIGHT_DP, density.fontScale)
    } else {
        density
    }
    CompositionLocalProvider(LocalIsTv provides isTv, LocalDensity provides scaled, content = content)
}
