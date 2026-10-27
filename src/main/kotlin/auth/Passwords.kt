package ilios.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private const val KEY_BITS = 256
private const val STORED_PARTS = 3 // iterations, salt and hash

private const val ITERATIONS = 120_000

internal val rng = SecureRandom()
internal val b64 = Base64.getUrlEncoder().withoutPadding()
internal val b64d = Base64.getUrlDecoder()

private fun pbkdf2(pw: String, salt: ByteArray, iter: Int): ByteArray =
    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        .generateSecret(PBEKeySpec(pw.toCharArray(), salt, iter, KEY_BITS))
        .encoded

fun hashPassword(pw: String): String {
    val salt = ByteArray(16).also(rng::nextBytes)
    return "$ITERATIONS:${b64.encodeToString(salt)}:${b64.encodeToString(pbkdf2(pw, salt, ITERATIONS))}"
}

fun verifyPassword(pw: String, stored: String): Boolean {
    val p = stored.split(':')
    if (p.size != STORED_PARTS) return false
    val expected = b64d.decode(p[2])
    return MessageDigest.isEqual(expected, pbkdf2(pw, b64d.decode(p[1]), p[0].toInt()))
}
