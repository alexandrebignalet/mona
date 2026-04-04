package mona.infrastructure

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import mona.infrastructure.sirene.SireneTokenProvider
import mona.infrastructure.sirene.SireneTokenRefreshException
import mona.infrastructure.sirene.TokenFetchResult
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SireneTokenProviderTest {
    private fun provider(
        clock: () -> Long = System::currentTimeMillis,
        fetchResult: () -> TokenFetchResult,
    ): SireneTokenProvider {
        return SireneTokenProvider(
            clock = clock,
            tokenFetcher = { fetchResult() },
        )
    }

    @Test
    fun `getToken returns cached token within expiry`() =
        runTest {
            val fetchCount = AtomicInteger(0)
            val now = System.currentTimeMillis()
            // token valid for 1 hour
            val p =
                provider(clock = { now }) {
                    fetchCount.incrementAndGet()
                    TokenFetchResult(accessToken = "token-abc", expiresInSeconds = 3600)
                }

            val first = p.getToken()
            val second = p.getToken()

            assertEquals("token-abc", first)
            assertEquals("token-abc", second)
            assertEquals(1, fetchCount.get(), "should only fetch once when token is still valid")
        }

    @Test
    fun `getToken refreshes token after expiry`() =
        runTest {
            val fetchCount = AtomicInteger(0)
            var currentTime = 0L
            val p =
                provider(clock = { currentTime }) {
                    fetchCount.incrementAndGet()
                    TokenFetchResult(accessToken = "token-${fetchCount.get()}", expiresInSeconds = 100)
                }

            // First fetch
            val first = p.getToken()
            assertEquals("token-1", first)
            assertEquals(1, fetchCount.get())

            // Advance clock past expiry (100s * 1000ms - 60s safety margin = 40s → advance 41s)
            currentTime += 41_000L

            // Should refresh
            val second = p.getToken()
            assertEquals("token-2", second)
            assertEquals(2, fetchCount.get())
        }

    @Test
    fun `getToken does not refresh when within safety margin`() =
        runTest {
            val fetchCount = AtomicInteger(0)
            var currentTime = 0L
            val p =
                provider(clock = { currentTime }) {
                    fetchCount.incrementAndGet()
                    TokenFetchResult(accessToken = "token-${fetchCount.get()}", expiresInSeconds = 100)
                }

            p.getToken()
            assertEquals(1, fetchCount.get())

            // Advance clock to exactly at safety margin boundary (39s < 40s threshold)
            currentTime += 39_000L

            p.getToken()
            assertEquals(1, fetchCount.get(), "should not refresh when still within safety margin")
        }

    @Test
    fun `getToken refreshes at expiry minus 60 second safety margin`() =
        runTest {
            val fetchCount = AtomicInteger(0)
            var currentTime = 0L
            val p =
                provider(clock = { currentTime }) {
                    fetchCount.incrementAndGet()
                    TokenFetchResult(accessToken = "token-${fetchCount.get()}", expiresInSeconds = 3600)
                }

            p.getToken()
            assertEquals(1, fetchCount.get())

            // At exactly 60s before expiry: still valid
            currentTime += (3600 - 60 - 1) * 1000L
            p.getToken()
            assertEquals(1, fetchCount.get())

            // Past the 60s safety margin: should refresh
            currentTime += 2000L
            p.getToken()
            assertEquals(2, fetchCount.get())
        }

    @Test
    fun `invalidate causes next getToken to fetch fresh token`() =
        runTest {
            val fetchCount = AtomicInteger(0)
            val now = System.currentTimeMillis()
            val p =
                provider(clock = { now }) {
                    fetchCount.incrementAndGet()
                    TokenFetchResult(accessToken = "token-${fetchCount.get()}", expiresInSeconds = 3600)
                }

            val first = p.getToken()
            assertEquals("token-1", first)
            assertEquals(1, fetchCount.get())

            p.invalidate()

            val second = p.getToken()
            assertEquals("token-2", second)
            assertEquals(2, fetchCount.get())
        }

    @Test
    fun `concurrent calls do not double-refresh`() =
        runTest {
            val fetchCount = AtomicInteger(0)
            val now = System.currentTimeMillis()
            val p =
                provider(clock = { now }) {
                    fetchCount.incrementAndGet()
                    TokenFetchResult(accessToken = "token-${fetchCount.get()}", expiresInSeconds = 3600)
                }

            // Launch many coroutines simultaneously before any token is cached
            val tokens = mutableListOf<String>()
            val jobs =
                (1..20).map {
                    launch {
                        tokens.add(p.getToken())
                    }
                }
            jobs.forEach { it.join() }

            assertEquals(1, fetchCount.get(), "Mutex should prevent concurrent double-refresh")
            assertEquals(20, tokens.size)
            tokens.forEach { assertEquals("token-1", it) }
        }

    @Test
    fun `fetch failure throws SireneTokenRefreshException`() =
        runTest {
            val p =
                provider {
                    throw SireneTokenRefreshException("Token refresh failed: HTTP 401")
                }

            assertFailsWith<SireneTokenRefreshException> {
                p.getToken()
            }
        }

    @Test
    fun `fetch failure message is preserved`() =
        runTest {
            val p =
                provider {
                    throw SireneTokenRefreshException("Token refresh failed: HTTP 503")
                }

            val exception =
                assertFailsWith<SireneTokenRefreshException> {
                    p.getToken()
                }
            assertEquals("Token refresh failed: HTTP 503", exception.message)
        }
}
