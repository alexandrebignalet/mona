package mona.integration.onboarding

import kotlinx.coroutines.runBlocking
import mona.application.onboarding.OnboardingRecoveryJob
import mona.domain.model.ClientId
import mona.domain.model.InvoiceStatus
import mona.domain.model.OnboardingReminderRecord
import mona.integration.IntegrationTestBase
import mona.integration.TEST_CLIENT_ID
import mona.integration.TEST_USER_ID
import mona.integration.TEST_USER_MINIMAL_ID
import java.time.Instant
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Minimal user has no SIREN → qualifies for onboarding recovery
private val RECOVERY_USER_ID = TEST_USER_MINIMAL_ID
private val RECOVERY_CLIENT_ID = ClientId("recovery-client-001")

// Dates relative to TODAY = April 6
private val TODAY = LocalDate.of(2026, 4, 6)
private val DRAFT_DATE_1_DAY_AGO = LocalDate.of(2026, 4, 5)
private val DRAFT_DATE_3_DAYS_AGO = LocalDate.of(2026, 4, 3)

class OnboardingRecoveryJobTest : IntegrationTestBase() {
    private fun useCase() = OnboardingRecoveryJob(userRepo, invoiceRepo, onboardingReminderRepo, conversationRepo, messagingPort)

    @BeforeTest
    fun seedData() =
        setup {
            createTestUserMinimal() // no SIREN
            // Save a client for the minimal user
            clientRepo.save(testClient(RECOVERY_USER_ID).copy(id = RECOVERY_CLIENT_ID))
        }

    @Test
    fun `day-1 reminder sent when oldest draft is 1+ days old`() =
        runBlocking {
            createTestInvoice(RECOVERY_USER_ID, RECOVERY_CLIENT_ID, InvoiceStatus.Draft, issueDate = DRAFT_DATE_1_DAY_AGO)

            useCase().execute(TODAY)

            val messages = messagingPort.allMessages()
            assertTrue(messages.size == 1)
            assertTrue(messages.first().text.contains("brouillon"))

            val record = onboardingReminderRepo.findByUser(RECOVERY_USER_ID)
            assertNotNull(record?.r1SentAt)
        }

    @Test
    fun `day-3 reminder sent when draft is 3+ days old and not acknowledged`() =
        runBlocking {
            createTestInvoice(RECOVERY_USER_ID, RECOVERY_CLIENT_ID, InvoiceStatus.Draft, issueDate = DRAFT_DATE_3_DAYS_AGO)

            // Pre-seed r1 reminder sent 2 days ago (no acknowledgement in conversation)
            val r1SentAt = Instant.parse("2026-04-04T10:00:00Z")
            onboardingReminderRepo.save(OnboardingReminderRecord(RECOVERY_USER_ID, r1SentAt = r1SentAt))

            useCase().execute(TODAY)

            val messages = messagingPort.allMessages()
            assertTrue(messages.size == 1)
            assertTrue(messages.first().text.contains("brouillon"))

            val record = onboardingReminderRepo.findByUser(RECOVERY_USER_ID)
            assertNotNull(record?.r2SentAt)
        }

    @Test
    fun `user with SIREN is skipped by job`() =
        runBlocking {
            // Create a fully onboarded user (has SIREN) with a draft — should be excluded by findAllWithoutSiren
            createTestUser()
            createTestClient()
            createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Draft)

            useCase().execute(TODAY)

            // SIREN user is NOT in findAllWithoutSiren → no message for that user
            val messagesForSirenUser = messagingPort.allMessages().filter { it.userId == TEST_USER_ID }
            assertTrue(messagesForSirenUser.isEmpty())
        }

    @Test
    fun `already reminded at r1 and r2 does not send any more reminders`() =
        runBlocking {
            createTestInvoice(RECOVERY_USER_ID, RECOVERY_CLIENT_ID, InvoiceStatus.Draft, issueDate = DRAFT_DATE_3_DAYS_AGO)

            // Both reminders already sent
            onboardingReminderRepo.save(
                OnboardingReminderRecord(
                    RECOVERY_USER_ID,
                    r1SentAt = Instant.parse("2026-04-03T10:00:00Z"),
                    r2SentAt = Instant.parse("2026-04-04T10:00:00Z"),
                ),
            )

            useCase().execute(TODAY)

            assertTrue(messagingPort.allMessages().isEmpty())
        }
}
