package dev.jarvis.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.jarvis.data.db.JarvisDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * Upgrading the app must keep chat history. Builds a real v1 database from the exported v1 schema
 * (app/schemas/.../1.json), then opens it with the current version so Room runs the auto-migration.
 */
class MigrationTest {
    private val schemaDir = File("schemas/dev.jarvis.data.db.JarvisDatabase")

    private fun createV1(path: String) {
        val schema = Json.parseToJsonElement(File(schemaDir, "1.json").readText()).jsonObject["database"]!!.jsonObject
        val conn = BundledSQLiteDriver().open(path)
        try {
            schema["entities"]!!.jsonArray.forEach { e ->
                val table = e.jsonObject["tableName"]!!.jsonPrimitive.content
                conn.execSQL(e.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                e.jsonObject["indices"]?.jsonArray?.forEach { i ->
                    conn.execSQL(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema["setupQueries"]!!.jsonArray.forEach { conn.execSQL(it.jsonPrimitive.content) }
            conn.execSQL("INSERT INTO conversations (id, title, createdAt, updatedAt) VALUES (1, 'Net worth', 10, 20)")
            conn.execSQL(
                "INSERT INTO messages (conversationId, role, text, createdAt, model, costUsd, dataSources, failed) " +
                    "VALUES (1, 'user', 'what''s my net worth', 10, NULL, NULL, NULL, 0)"
            )
            conn.execSQL(
                "INSERT INTO messages (conversationId, role, text, createdAt, model, costUsd, dataSources, failed) " +
                    "VALUES (1, 'assistant', 'About 12 lakh.', 20, 'claude-haiku-5-5', 0.0001, NULL, 0)"
            )
            conn.execSQL("PRAGMA user_version = 1")
        } finally {
            conn.close()
        }
    }

    @Test fun v1HistorySurvivesTheUpgradeToV2() = runBlocking {
        val file = File.createTempFile("jarvis-migration", ".db").apply { delete() }
        createV1(file.path)
        val db = Room.databaseBuilder<JarvisDatabase>(file.path)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        try {
            val messages = db.history().messages(1)
            assertEquals(listOf("what's my net worth", "About 12 lakh."), messages.map { it.text })
            assertFalse(messages.any { it.sensitive || it.screenOnly }) // new columns default to false
            assertEquals("Net worth", db.history().observeConversations().first().single().title)
        } finally {
            db.close()
            file.delete()
        }
    }
}
