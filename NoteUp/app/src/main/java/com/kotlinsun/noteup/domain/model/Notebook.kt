package com.kotlinsun.noteup.domain.model

data class Notebook(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)
