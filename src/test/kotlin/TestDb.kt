package ilios

import java.nio.file.Files

/** A throwaway database path that is deleted when the test JVM exits, so test runs leave nothing behind in /tmp. */
fun tempDbPath(): String {
    val dir = Files.createTempDirectory("ilios-test")
    Runtime.getRuntime().addShutdownHook(Thread { dir.toFile().deleteRecursively() })
    return dir.resolve("db").toString()
}
