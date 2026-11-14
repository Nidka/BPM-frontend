package pe.bpm.core.offline

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import pe.bpm.core.offline.sql.OfflineDb
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/** Mismo contrato sobre SQLite real (el motor que usan Android e iOS). */
class SqlOfflineDatabaseTest : OfflineDatabaseContract() {
    override fun create(): OfflineDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OfflineDb.Schema.create(driver)
        return SqlOfflineDatabase(driver)
    }

    @Test fun dataSurvivesReopeningTheFile() = runTest {
        val file = File.createTempFile("offline", ".db").also { it.deleteOnExit() }
        val first = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        OfflineDb.Schema.create(first)
        val one = SqlOfflineDatabase(first)
        one.putRecord("s", "c", StoredRecord("k", "{}", 1))
        one.insertOperation("s", OutboxOperation("op-1", "HTTP", "o", "e", "idem-1234567", 1, "{}"))
        first.close()
        val second = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        OfflineDb.Schema.create(second) // IF NOT EXISTS: abrir de nuevo no destruye datos
        val two = SqlOfflineDatabase(second)
        assertEquals("{}", two.getRecord("s", "c", "k")?.json)
        assertEquals(listOf("op-1"), two.operations("s").map { it.id })
        second.close()
    }
}
