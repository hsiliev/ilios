package ilios.auth

import ilios.db.Tokens
import ilios.db.Users
import ilios.web.userId
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest

private const val TOKEN_BYTES = 32

private const val SESSION_MS = 14L * 24 * 3600 * 1000
private const val RESET_MS = 3600L * 1000
private val dummyHash = hashPassword("dummy") // equalises timing for unknown emails

private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") {
    "%02x".format(it)
}
private fun newToken() = b64.encodeToString(ByteArray(TOKEN_BYTES).also(rng::nextBytes))

class AuthService {
    /** Returns the new user id, or null if the email is already registered. */
    fun register(email: String, password: String): Long? = try {
        transaction {
            if (!Users.selectAll().where { Users.email eq email }.empty()) return@transaction null
            val id = Users.insert {
                it[Users.email] = email
                it[passHash] = hashPassword(password)
            }[Users.id]
            id
        }
    } catch (expected: ExposedSQLException) {
        null // lost a race against the unique index
    }

    /** Returns a new session token, or null on bad credentials. */
    fun login(email: String, password: String): String? {
        val row = transaction { Users.selectAll().where { Users.email eq email }.singleOrNull() }
        val ok = verifyPassword(password, row?.get(Users.passHash) ?: dummyHash)
        return if (row != null && ok) issue(row[Users.id], "session", SESSION_MS) else null
    }

    fun userFor(token: String): Long? = transaction {
        Tokens.selectAll().where {
            Tokens.hash eq sha256(token) and (Tokens.kind eq "session") and
                (Tokens.expires greater System.currentTimeMillis())
        }.singleOrNull()?.get(Tokens.userId)
    }

    fun logout(token: String) = transaction {
        Tokens.deleteWhere { hash eq sha256(token) }
        Unit
    }

    fun email(userId: Long): String = transaction {
        Users.selectAll().where { Users.id eq userId }.single()[Users.email]
    }

    /** Returns a reset token if the email exists. The caller must never reveal that to the requester. */
    fun createReset(email: String): String? {
        val id =
            transaction { Users.selectAll().where { Users.email eq email }.singleOrNull()?.get(Users.id) }
                ?: return null
        return issue(id, "reset", RESET_MS)
    }

    fun reset(token: String, password: String): Boolean = transaction {
        val h = sha256(token)
        val uid = Tokens.selectAll().where {
            Tokens.hash eq h and (Tokens.kind eq "reset") and (Tokens.expires greater System.currentTimeMillis())
        }.singleOrNull()?.get(Tokens.userId) ?: return@transaction false
        Users.update({ Users.id eq uid }) { it[passHash] = hashPassword(password) }
        Tokens.deleteWhere { userId eq uid } // invalidates all sessions and reset tokens
        true
    }

    private fun issue(userId: Long, kind: String, ttl: Long): String {
        val token = newToken()
        transaction {
            Tokens.deleteWhere { Tokens.expires less System.currentTimeMillis() }
            Tokens.insert {
                it[hash] = sha256(token)
                it[Tokens.userId] = userId
                it[Tokens.kind] = kind
                it[expires] = System.currentTimeMillis() + ttl
            }
        }
        return token
    }
}
