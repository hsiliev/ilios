package ilios.db

import ilios.tempDbPath
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseCompactTest {
    @Test
    fun `opening the database gives back the space of deleted rows and keeps the rest`() {
        val path = tempDbPath()
        initDb(path)
        transaction {
            exec("CREATE TABLE junk(id INT PRIMARY KEY, text VARCHAR)")
            exec("INSERT INTO junk SELECT x, RANDOM_UUID() || RANDOM_UUID() FROM SYSTEM_RANGE(1, 100000)")
            exec("INSERT INTO users(email, pass_hash) VALUES ('keep@example.com', 'x')")
        }
        transaction { exec("DELETE FROM junk") }
        val file = File("$path.mv.db")
        val bloated = file.length()
        initDb(path)
        assertTrue(file.length() < bloated / 2, "compacted: ${file.length()} of $bloated bytes")
        val kept = transaction {
            exec("SELECT COUNT(*) FROM users WHERE email = 'keep@example.com'") {
                it.next()
                it.getInt(1)
            }
        }
        assertEquals(1, kept)
    }
}
