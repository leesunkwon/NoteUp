package com.kotlinsun.noteup.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "applied_recovery_operations",
    indices = [Index(value = ["appliedAt"])],
)
data class AppliedRecoveryOperationEntity(
    @PrimaryKey val operationId: String,
    val appliedAt: Long,
)
