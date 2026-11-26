/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.clip

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FavoriteEntity): Long

    @Query("UPDATE ${FavoriteEntity.TABLE_NAME} SET text=:text WHERE id=:id")
    suspend fun updateText(
        id: Int,
        text: String,
    )

    @Query("UPDATE ${FavoriteEntity.TABLE_NAME} SET timestamp=:timestamp WHERE id=:id")
    suspend fun updateTimestamp(
        id: Int,
        timestamp: Long,
    )

    @Query("DELETE FROM ${FavoriteEntity.TABLE_NAME} WHERE id=:id")
    suspend fun delete(id: Int)

    @Query("DELETE FROM ${FavoriteEntity.TABLE_NAME}")
    suspend fun deleteAll()

    @Query("SELECT * FROM ${FavoriteEntity.TABLE_NAME} ORDER BY sort_order DESC, timestamp DESC")
    fun all(): PagingSource<Int, FavoriteEntity>

    @Query(
        """
        SELECT * FROM ${FavoriteEntity.TABLE_NAME}
        WHERE (:category = '' OR category = :category)
        ORDER BY sort_order DESC, timestamp DESC
        """,
    )
    fun allFlow(category: String = ""): Flow<List<FavoriteEntity>>

    @Query("SELECT DISTINCT category FROM ${FavoriteEntity.TABLE_NAME} WHERE category != '' ORDER BY category ASC")
    fun categoriesFlow(): Flow<List<String>>

    @Query("UPDATE ${FavoriteEntity.TABLE_NAME} SET category=:category, note=:note WHERE id=:id")
    suspend fun updateMeta(
        id: Int,
        category: String,
        note: String,
    )

    @Query("UPDATE ${FavoriteEntity.TABLE_NAME} SET sort_order=:sortOrder WHERE id=:id")
    suspend fun updateSortOrder(
        id: Int,
        sortOrder: Long,
    )

    @Query("SELECT * FROM ${FavoriteEntity.TABLE_NAME} WHERE id=:id LIMIT 1")
    suspend fun get(id: Int): FavoriteEntity?

    @Query("SELECT * FROM ${FavoriteEntity.TABLE_NAME} WHERE id=:rowId LIMIT 1")
    suspend fun get(rowId: Long): FavoriteEntity?

    @Query("SELECT * FROM ${FavoriteEntity.TABLE_NAME} WHERE text=:text LIMIT 1")
    suspend fun find(text: String): FavoriteEntity?
}
