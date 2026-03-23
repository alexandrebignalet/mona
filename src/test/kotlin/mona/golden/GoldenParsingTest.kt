package mona.golden

import mona.infrastructure.llm.ClaudeApiClient
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestFactory

/**
 * Golden test suite — LLM parsing accuracy.
 *
 * Runs each French message through the real Claude API and verifies the correct
 * tool is selected and required parameters are extracted.
 *
 * Skipped automatically when ANTHROPIC_API_KEY is not set (CI without credentials).
 *
 * Selective execution:
 *   ./gradlew test --tests "*.GoldenParsingTest"
 *   ./gradlew test -Dgolden.categories=create_invoice,mark_paid
 */
@Tag("golden-parsing")
class GoldenParsingTest {
    companion object {
        private val apiKey: String? = System.getenv("ANTHROPIC_API_KEY")
        private val client: ClaudeApiClient? = apiKey?.let { ClaudeApiClient.fromEnv() }
        private val allCases: List<GoldenTestCase> =
            GoldenTestLoader.load("/golden/parsing_cases.json")
    }

    @TestFactory
    fun parsingCases(): List<DynamicTest> {
        Assumptions.assumeTrue(
            client != null,
            "Skipping golden parsing tests: ANTHROPIC_API_KEY not set",
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
