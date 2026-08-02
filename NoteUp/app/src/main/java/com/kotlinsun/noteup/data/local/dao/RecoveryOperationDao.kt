package com.kotlinsun.noteup.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kotlinsun.noteup.data.local.entity.AppliedRecoveryOperationEntity

@Dao
interface RecoveryOperationDao {
    @Query("SELECT EXISTS(SELECT 1 FROM applied_recovery_operations WHERE operationId = :operationId)")
    suspend fun isApplied(operationId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markApplied(value: AppliedRecoveryOperationEntity): Long

    @Query("DELETE FROM applied_recovery_operations WHERE appliedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}
