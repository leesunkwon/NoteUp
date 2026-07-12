package com.kotlinsun.noteup.ui.canvas

sealed interface CanvasEvent {
    data class PendingPersisted(val token: Long) : CanvasEvent
    data class PendingDiscarded(val token: Long) : CanvasEvent
    data object RefreshStrokes : CanvasEvent
}
