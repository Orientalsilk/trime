/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.clip

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    version = 2,
    entities = [
        ClipboardEntity::class,
        FavoriteEntity::class,
    ],
)
abstract class ClipDatabase : RoomDatabase() {
    abstract fun clipboardDao(): ClipboardDao

    abstract fun favoriteDao(): FavoriteDao

    companion object {
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE ${ClipboardEntity.TABLE_NAME} ADD COLUMN category TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE ${ClipboardEntity.TABLE_NAME} ADD COLUMN note TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE ${ClipboardEntity.TABLE_NAME} ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("UPDATE ${ClipboardEntity.TABLE_NAME} SET sort_order = timestamp")

                    db.execSQL("ALTER TABLE ${FavoriteEntity.TABLE_NAME} ADD COLUMN category TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE ${FavoriteEntity.TABLE_NAME} ADD COLUMN note TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE ${FavoriteEntity.TABLE_NAME} ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("UPDATE ${FavoriteEntity.TABLE_NAME} SET sort_order = timestamp")
                }
            }
    }
}
