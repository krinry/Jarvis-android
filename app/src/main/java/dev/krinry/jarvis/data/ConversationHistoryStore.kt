package dev.krinry.jarvis.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ConversationHistoryStore — Persists full conversation turns, action summaries,
 * timestamps, and context details offline in a local SQLite database.
 *
 * Enables Jarvis to:
 * 1. Remember conversation context across restarts & reconnections.
 * 2. Log timestamps, user requests, assistant replies, and completed actions.
 * 3. Provide concise conversational memory to inject into new live sessions.
 */
class ConversationHistoryStore(context: Context) {

    companion object {
        private const val TAG = "ConversationHistory"
        private const val DB_NAME = "jarvis_history.db"
        private const val DB_VERSION = 1

        private const val TABLE_CONVERSATIONS = "conversations"
        private const val COL_ID = "id"
        private const val COL_SESSION_ID = "session_id"
        private const val COL_TIMESTAMP = "timestamp"
        private const val COL_FORMATTED_TIME = "formatted_time"
        private const val COL_SPEAKER = "speaker" // "user", "jarvis", "system"
        private const val COL_TEXT = "text"
        private const val COL_ACTION_TYPE = "action_type" // e.g. "open_app", "task_done"
        private const val COL_ACTION_SUMMARY = "action_summary"

        @Volatile
        private var instance: ConversationHistoryStore? = null

        fun getInstance(context: Context): ConversationHistoryStore {
            return instance ?: synchronized(this) {
                instance ?: ConversationHistoryStore(context.applicationContext).also { instance = it }
            }
        }
    }

    private val dbHelper = DatabaseHelper(context)
    private val timeFormat = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())

    data class MessageEntry(
        val id: Long,
        val sessionId: String,
        val timestamp: Long,
        val formattedTime: String,
        val speaker: String,
        val text: String,
        val actionType: String?,
        val actionSummary: String?
    )

    private class DatabaseHelper(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            val createQuery = """
                CREATE TABLE $TABLE_CONVERSATIONS (
                    $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_SESSION_ID TEXT NOT NULL,
                    $COL_TIMESTAMP INTEGER NOT NULL,
                    $COL_FORMATTED_TIME TEXT NOT NULL,
                    $COL_SPEAKER TEXT NOT NULL,
                    $COL_TEXT TEXT NOT NULL,
                    $COL_ACTION_TYPE TEXT,
                    $COL_ACTION_SUMMARY TEXT
                )
            """.trimIndent()
            db.execSQL(createQuery)
            db.execSQL("CREATE INDEX idx_session ON $TABLE_CONVERSATIONS ($COL_SESSION_ID)")
            db.execSQL("CREATE INDEX idx_timestamp ON $TABLE_CONVERSATIONS ($COL_TIMESTAMP)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_CONVERSATIONS")
            onCreate(db)
        }
    }

    /**
     * Save a user speech turn into the offline database.
     */
    fun saveUserMessage(sessionId: String, text: String) {
        if (text.isBlank()) return
        saveEntry(sessionId, "user", text, null, null)
    }

    /**
     * Save an assistant response turn into the offline database.
     */
    fun saveAssistantMessage(sessionId: String, text: String) {
        if (text.isBlank()) return
        saveEntry(sessionId, "jarvis", text, null, null)
    }

    /**
     * Save a completed task action into the offline database.
     */
    fun saveAction(sessionId: String, actionType: String, actionSummary: String) {
        saveEntry(sessionId, "action", actionSummary, actionType, actionSummary)
    }

    private fun saveEntry(
        sessionId: String,
        speaker: String,
        text: String,
        actionType: String?,
        actionSummary: String?
    ) {
        try {
            val db = dbHelper.writableDatabase
            val now = System.currentTimeMillis()
            val formatted = timeFormat.format(Date(now))

            val values = ContentValues().apply {
                put(COL_SESSION_ID, sessionId)
                put(COL_TIMESTAMP, now)
                put(COL_FORMATTED_TIME, formatted)
                put(COL_SPEAKER, speaker)
                put(COL_TEXT, text)
                put(COL_ACTION_TYPE, actionType)
                put(COL_ACTION_SUMMARY, actionSummary)
            }

            db.insert(TABLE_CONVERSATIONS, null, values)
            Log.d(TAG, "[$speaker] Saved to offline history: ${text.take(60)}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save message to history", e)
        }
    }

    /**
     * Generates a clean conversational context summary from the last N interactions.
     * This context is injected into new live sessions so Jarvis remembers past discussions.
     */
    fun getRecentContextForLivePrompt(maxTurns: Int = 8): String {
        val entries = getRecentMessages(maxTurns)
        if (entries.isEmpty()) return ""

        val sb = StringBuilder()
        for (entry in entries) {
            when (entry.speaker) {
                "user" -> sb.append("[${entry.formattedTime}] User: ${entry.text}\n")
                "jarvis" -> sb.append("[${entry.formattedTime}] Jarvis: ${entry.text}\n")
                "action" -> sb.append("[${entry.formattedTime}] Action Completed: ${entry.actionSummary}\n")
            }
        }
        return sb.toString().trim()
    }

    /**
     * Retrieves the latest N messages ordered chronologically.
     */
    fun getRecentMessages(limit: Int = 20): List<MessageEntry> {
        val list = mutableListOf<MessageEntry>()
        try {
            val db = dbHelper.readableDatabase
            val cursor = db.query(
                TABLE_CONVERSATIONS,
                null,
                null,
                null,
                null,
                null,
                "$COL_TIMESTAMP DESC",
                limit.toString()
            )

            cursor.use {
                while (it.moveToNext()) {
                    list.add(
                        MessageEntry(
                            id = it.getLong(it.getColumnIndexOrThrow(COL_ID)),
                            sessionId = it.getString(it.getColumnIndexOrThrow(COL_SESSION_ID)),
                            timestamp = it.getLong(it.getColumnIndexOrThrow(COL_TIMESTAMP)),
                            formattedTime = it.getString(it.getColumnIndexOrThrow(COL_FORMATTED_TIME)),
                            speaker = it.getString(it.getColumnIndexOrThrow(COL_SPEAKER)),
                            text = it.getString(it.getColumnIndexOrThrow(COL_TEXT)),
                            actionType = it.getString(it.getColumnIndexOrThrow(COL_ACTION_TYPE)),
                            actionSummary = it.getString(it.getColumnIndexOrThrow(COL_ACTION_SUMMARY))
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query conversation history", e)
        }
        // Reverse so it is chronological (oldest to newest)
        return list.reversed()
    }

    /**
     * Clear all conversation history.
     */
    fun clearAllHistory() {
        try {
            val db = dbHelper.writableDatabase
            db.delete(TABLE_CONVERSATIONS, null, null)
            Log.d(TAG, "All conversation history cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear history", e)
        }
    }

    /**
     * Returns total number of recorded conversation entries.
     */
    fun getMessageCount(): Int {
        return try {
            val db = dbHelper.readableDatabase
            val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_CONVERSATIONS", null)
            cursor.use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        } catch (e: Exception) {
            0
        }
    }
}
