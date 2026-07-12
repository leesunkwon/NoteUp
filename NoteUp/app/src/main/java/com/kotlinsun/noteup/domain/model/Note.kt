package com.kotlinsun.noteup.domain.model

data class Note(
    val id: Long,
    val notebookId: Long?,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)
