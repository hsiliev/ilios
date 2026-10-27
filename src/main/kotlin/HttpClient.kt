package ilios

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

private const val REQUEST_TIMEOUT_MS = 15_000L

/** The client for the outside APIs (Open-Meteo, SolaX): fails on error statuses and gives up after 15 seconds. */
fun newHttpClient(json: Json) = HttpClient(CIO) {
    expectSuccess = true
    install(ContentNegotiation) { json(json) }
    install(HttpTimeout) { requestTimeoutMillis = REQUEST_TIMEOUT_MS }
}
