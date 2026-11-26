/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.clip

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = FavoriteEntity.TABLE_NAME)
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val text: String,
    @ColumnInfo(defaultValue = "-1")
    val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "")
    val category: String = "",
    @ColumnInfo(defaultValue = "")
    val note: String = "",
    @ColumnInfo(name = "sort_order", defaultValue = "0")
    val sortOrder: Long = timestamp,
) {
    companion object {
        const val TABLE_NAME = "favorite"
    }
}
