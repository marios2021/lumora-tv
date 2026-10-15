package com.lumora.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme as M3Theme
import androidx.compose.material3.darkColorScheme as m3DarkColorScheme

object LumoraColors {
    val Brand = Color(0xFF7C6CF2)
    val BrandDim = Color(0xFF3A3370)
    val Background = Color(0xFF0D0F16)
    val Surface = Color(0xFF151824)
    val SurfaceHigh = Color(0xFF1F2333)
    val OnSurface = Color(0xFFE8E9F0)
    val OnSurfaceDim = Color(0xFF9A9DB0)
    val Favorite = Color(0xFFFFC547)
    val Error = Color(0xFFFF6B6B)
}

/** Wraps both TV Material (lists, buttons) and Material 3 (text fields, progress) in one dark scheme. */
@Composable
fun LumoraTheme(content: @Composable () -> Unit) {
    val tvColors = darkColorScheme(
        primary = LumoraColors.Brand,
        onPrimary = Color.White,
        primaryContainer = LumoraColors.BrandDim,
        onPrimaryContainer = Color.White,
        secondary = LumoraColors.Brand,
        onSecondary = Color.White,
        background = LumoraColors.Background,
        onBackground = LumoraColors.OnSurface,
        surface = LumoraColors.Surface,
        onSurface = LumoraColors.OnSurface,
        surfaceVariant = LumoraColors.SurfaceHigh,
        onSurfaceVariant = LumoraColors.OnSurfaceDim,
        inverseSurface = LumoraColors.OnSurface,
        inverseOnSurface = LumoraColors.Background,
        error = LumoraColors.Error,
    )
    val m3Colors = m3DarkColorScheme(
        primary = LumoraColors.Brand,
        onPrimary = Color.White,
        background = LumoraColors.Background,
        onBackground = LumoraColors.OnSurface,
        surface = LumoraColors.Surface,
        onSurface = LumoraColors.OnSurface,
        surfaceVariant = LumoraColors.SurfaceHigh,
        onSurfaceVariant = LumoraColors.OnSurfaceDim,
        outline = LumoraColors.OnSurfaceDim,
        error = LumoraColors.Error,
    )
    M3Theme(colorScheme = m3Colors) {
        MaterialTheme(colorScheme = tvColors, content = content)
    }
}
