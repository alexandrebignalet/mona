package mona.golden

import mona.infrastructure.llm.ClaudeApiClient
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestFactory

/**
 * Golden test suite — LLM context resolution accuracy.
 *
 * Tests pronoun resolution, anaphora, corrections, and other context-dependent
 * messages using conversation history. Each case provides prior turns so Claude
 * can resolve implicit references.
 *
 * Skipped automatically when ANTHROPIC_API_KEY is not set.
 *
 * Selective execution:
 *   ./gradlew test --tests "*.GoldenContextTest"
 *   ./gradlew test -Dgolden.categories=context_resolution
 */
@Tag("golden-context")
class GoldenContextTest {
    companion object {
        private val apiKey: String? = System.getenv("ANTHROPIC_API_KEY")
        private val client: ClaudeApiClient? = apiKey?.let { ClaudeApiClient.fromEnv() }
        private val allCases: List<GoldenTestCase> =
            GoldenTestLoader.load("/golden/context_cases.json")
    }

    @TestFactory
    fun contextResolutionCases(): List<DynamicTest> {
        Assumptions.assumeTrue(
            client != null,
            "Skipping golden context tests: ANTHROPIC_API_KEY not set",
        )
        return allCases
            .filter { matchesFilter(it.category) }
            .map { case ->
                DynamicTest.dynamicTest("[${case.category}] ${case.id}: ${case.description}") {
                    val result = GoldenTestLoader.runCase(client!!, case)
                    GoldenTestLoader.validateResult(case, result)
                }
            }
    }

    private fun matchesFilter(category: String): Boolean {
        val filter = System.getProperty("golden.categories") ?: return true
        return filter.split(",").map { it.trim() }.contains(category)
    }
}
