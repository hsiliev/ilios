package ilios

import java.net.URI

internal const val MAX_PORT = 65_535

/** HTTP port from the PORT environment variable; 8081 when unset or blank. A set but invalid value fails fast. */
fun portFrom(value: String?): Int {
    val v = value?.trim().orEmpty()
    if (v.isEmpty()) return 8081
    return v.toIntOrNull()?.takeIf { it in 1..MAX_PORT } ?: error("PORT must be a number between 1 and 65535, got '$v'")
}

/** Public address for reset links from BASE_URL (default http://localhost:PORT); must be an http(s) URL with a host. */
fun baseUrlFrom(value: String?, port: Int): String {
    val v = value?.trim().orEmpty().trimEnd('/')
    if (v.isEmpty()) return "http://localhost:$port"
    val uri = runCatching { URI(v) }.getOrNull()
    require(uri != null && uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()) {
        "BASE_URL must be an http(s) URL, got '$v'"
    }
    return v
}

internal val PORT = portFrom(System.getenv("PORT"))
internal val BASE_URL = baseUrlFrom(System.getenv("BASE_URL"), PORT) // used in password-reset links
internal val HTTPS = BASE_URL.startsWith("https://") // behind HTTPS (e.g. Caddy): Secure cookie and HSTS
