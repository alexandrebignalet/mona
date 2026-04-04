package mona.infrastructure.sirene

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets

internal class SireneTokenRefreshException(message: String) : Exception(message)

internal data class TokenFetchResult(val accessToken: String, val expiresInSeconds: Long)

internal fun interface TokenFetcher {
    suspend fun fetch(): TokenFetchResult
}

internal class SireneTokenProvider(
    private val clock: () -> Long = System::currentTimeMillis,
    private val tokenFetcher: TokenFetcher,
) {
    private val mutex = Mutex()
    private var cachedToken: String? = null
    private var expiresAtMillis: Long = 0L

    companion object {
        private const val SAFETY_MARGIN_MS = 60_000L
        private val json = Json { ignoreUnknownKeys = true }

        fun create(
            clientId: String,
            clientSecret: String,
            tokenUrl: String = "https://api.insee.fr/token",
        ): SireneTokenProvider {
            val httpClient = HttpClient.newHttpClient()
            return SireneTokenProvider(
                tokenFetcher = {
                    withContext(Dispatchers.IO) {
                        try {
                            val body =
                                "grant_type=client_credentials" +
                                    "&client_id=${URLEncoder.encode(clientId, StandardCharsets.UTF_8)}" +
                                    "&client_secret=${URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)}"
                            val request =
                                HttpRequest.newBuilder()
                                    .uri(URI.create(tokenUrl))
                                    .header("Content-Type", "application/x-www-form-urlencoded")
                                    .POST(HttpRequest.BodyPublishers.ofString(body))
                                    .build()
                            val response = httpClient.send(request, BodyHandlers.ofString())
                            if (response.statusCode() != 200) {
                                throw SireneTokenRefreshException("Token refresh failed: HTTP ${response.statusCode()}")
                            }
                            val parsed = json.parseToJsonElement(response.body()).jsonObject
                            val token =
                                parsed["access_token"]?.jsonPrimitive?.content
                                    ?: throw SireneTokenRefreshException("Token refresh failed: missing access_token in response")
                            val expiresIn = parsed["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 604800L
                            TokenFetchResult(accessToken = token, expiresInSeconds = expiresIn)
                        } catch (e: SireneTokenRefreshException) {
                            throw e
                        } catch (e: Exception) {
                            throw SireneTokenRefreshException("Token refresh failed: ${e.message}")
                        }
                    }
                },
            )
        }
    }

    suspend fun getToken(): String =
        mutex.withLock {
            val now = clock()
            val cached = cachedToken
            if (cached != null && now < expiresAtMillis - SAFETY_MARGIN_MS) {
                return@withLock cached
            }
            val result = tokenFetcher.fetch()
            cachedToken = result.accessToken
            expiresAtMillis = clock() + result.expiresInSeconds * 1000L
            result.accessToken
        }

    suspend fun invalidate() =
        mutex.withLock {
            expiresAtMillis = 0L
        }
}
