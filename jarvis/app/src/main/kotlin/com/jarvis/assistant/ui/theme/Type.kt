package com.jarvis.assistant.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val JarvisTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Light, fontSize = 34.sp, letterSpacing = 2.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 24.sp, letterSpacing = 1.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 18.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.5.sp)
)
