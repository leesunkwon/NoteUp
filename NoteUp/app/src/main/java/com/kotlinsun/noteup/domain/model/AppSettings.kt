package com.kotlinsun.noteup.domain.model

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val canvasAppearance: CanvasAppearance = CanvasAppearance.WHITE_PAPER,
    val defaultPageTemplate: PageTemplate = PageTemplate.BLANK,
    val pageSwipeEnabled: Boolean = true,
    val keepScreenOn: Boolean = false,
    val hapticFeedbackEnabled: Boolean = true,
)

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

enum class CanvasAppearance {
    WHITE_PAPER,
    DARK_PAPER,
}
