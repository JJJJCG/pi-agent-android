package com.pi.assistant.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 一条气泡。role: user / pi / error / system */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val text: String,
    val tools: Int? = null,
    val ms: Long? = null,
    val failed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_PI = "pi"
        const val ROLE_ERROR = "error"
        const val ROLE_SYSTEM = "system"
    }
}
