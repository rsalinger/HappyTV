package com.rysal.happytv.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rysal.happytv.data.entity.LoginProfile
import kotlinx.coroutines.flow.Flow

/**
 * Single-row account table — [LoginProfile.id] is always
 * [LoginProfile.SINGLE_ROW_ID], and reads take the first row defensively so a
 * stray second insert can never wedge startup.
 */
@Dao
abstract class LoginProfileDao {

    /** Emits on every change; `null` when nobody is logged in yet. */
    @Query("SELECT * FROM login_profiles LIMIT 1")
    abstract fun observe(): Flow<LoginProfile?>

    /** One-shot read for startup hydration (no Flow needed). */
    @Query("SELECT * FROM login_profiles LIMIT 1")
    abstract suspend fun get(): LoginProfile?

    /** Replaces the single row (same id → REPLACE conflict strategy). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(profile: LoginProfile)

    /** Logout / wipe — also called by the security layer's credential wipe. */
    @Query("DELETE FROM login_profiles")
    abstract suspend fun clear()
}
