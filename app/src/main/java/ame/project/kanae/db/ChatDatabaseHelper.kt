package ame.project.kanae.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ame.project.kanae.overlay.ChatOverlayManager.ChatMessage
import ame.project.kanae.model.TikTokEmote
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ChatDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    private val gson = Gson()

    companion object {
        private const val DATABASE_NAME = "chat_history.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_NAME = "chat_history"
        
        private const val COLUMN_ID = "id"
        private const val COLUMN_NICKNAME = "nickname"
        private const val COLUMN_MESSAGE = "message"
        private const val COLUMN_COLOR = "color"
        private const val COLUMN_EMOTES = "emotes_json"
        private const val COLUMN_TIMESTAMP = "timestamp"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = ("CREATE TABLE $TABLE_NAME (" +
                "$COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT," +
                "$COLUMN_NICKNAME TEXT," +
                "$COLUMN_MESSAGE TEXT," +
                "$COLUMN_COLOR INTEGER," +
                "$COLUMN_EMOTES TEXT," +
                "$COLUMN_TIMESTAMP INTEGER)")
        db.execSQL(createTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }

    fun insertChat(chat: ChatMessage) {
        if (chat.isDummy) return
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_NICKNAME, chat.nickname)
            put(COLUMN_MESSAGE, chat.message)
            put(COLUMN_COLOR, chat.color)
            put(COLUMN_EMOTES, gson.toJson(chat.emotes))
            put(COLUMN_TIMESTAMP, chat.timestamp)
        }
        db.insert(TABLE_NAME, null, values)
    }

    fun getChatHistory(limit: Int, offset: Int): List<ChatMessage> {
        val chatList = mutableListOf<ChatMessage>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_NAME,
            null,
            null,
            null,
            null,
            null,
            "$COLUMN_TIMESTAMP DESC",
            "$offset, $limit"
        )

        val emoteType = object : TypeToken<List<TikTokEmote>>() {}.type

        if (cursor.moveToFirst()) {
            do {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID))
                val nickname = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NICKNAME))
                val message = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MESSAGE))
                val color = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_COLOR))
                val emotesJson = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_EMOTES))
                val timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP))
                
                val emotes: List<TikTokEmote> = gson.fromJson(emotesJson, emoteType)
                
                chatList.add(ChatMessage(id, nickname, message, color, emotes, false, timestamp))
            } while (cursor.moveToNext())
        }
        cursor.close()
        return chatList.reversed() // Reverse to show in chronological order for the recycler
    }

    fun clearHistory() {
        writableDatabase.delete(TABLE_NAME, null, null)
    }
}
