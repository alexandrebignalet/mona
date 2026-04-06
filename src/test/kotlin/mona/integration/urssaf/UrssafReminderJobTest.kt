package mona.integration.urssaf

import kotlinx.coroutines.runBlocking
import mona.application.urssaf.UrssafReminderJob
import mona.domain.model.DeclarationPeriodicity
import mona.domain.model.UrssafReminderRecord
import mona.integration.IntegrationTestBase
import mona.integration.TEST_USER_ID
import java.time.Instant
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// MONTHLY user: ref = today - 1 month; deadline = last day of current month = April 30
// today = April 23 → daysUntil = 7
private val TODAY_7_DAYS = LocalDate.of(2026, 4, 23)

// today = April 29 → daysUntil = 1
private val TODAY_1_DAY = LocalDate.of(2026, 4, 29)

// today = April 15 → daysUntil = 15 → no reminder
private val TODAY_NOT_NEAR = LocalDate.of(2026, 4, 15)

class UrssafReminderJobTest : IntegrationTestBase() {
    private fun useCase() = UrssafReminderJob(userRepo, invoiceRepo, urssafReminderRepo, conversationRepo, messagingPort)

    @BeforeTest
    fun seedData() =
        setup {
            createTestUser() // has declarationPeriodicity=MONTHLY
        }

    // The deadline for MONTHLY April 30 is for March's declaration → periodKey = "2026-03"
    private val marchPeriodKey = "2026-03"

    @Test
    fun `7 days before deadline sends d7 reminder and records it`() =
        runBlocking {
            useCase().execute(TODAY_7_DAYS)

            val messages = messagingPort.allMessages()
            assertEquals(1, messages.size)
            assertTrue(messages.first().text.contains("7 jours"))

            val record = urssafReminderRepo.findByUserAndPeriod(TEST_USER_ID, marchPeriodKey)
            assertNotNull(record)
            assertNotNull(record.d7SentAt)
        }

    @Test
    fun `1 day before deadline sends d1 reminder when not acknowledged`() =
        runBlocking {
            // Pre-seed the d7 reminder record (sent 6 days ago)
            val d7SentAt = Instant.parse("2026-04-23T10:00:00Z")
            urssafReminderRepo.save(UrssafReminderRecord(TEST_USER_ID, marchPeriodKey, d7SentAt = d7SentAt))
            // No conversation message after d7SentAt → not acknowledged

            useCase().execute(TODAY_1_DAY)

            val messages = messagingPort.allMessages()
            assertEquals(1, messages.size)
            assertTrue(messages.first().text.contains("demain"))

            val record = urssafReminderRepo.findByUserAndPeriod(TEST_USER_ID, marchPeriodKey)
            assertNotNull(record?.d1SentAt)
        }

    @Test
    fun `not near deadline sends no reminder`() =
        runBlocking {
            useCase().execute(TODAY_NOT_NEAR)
            assertTrue(messagingPort.allMessages().isEmpty())
        }

    @Test
    fun `already reminded at d7 does not send duplicate`() =
        runBlocking {
            val d7SentAt = Instant.parse("2026-04-20T10:00:00Z")
            urssafReminderRepo.save(UrssafReminderRecord(TEST_USER_ID, marchPeriodKey, d7SentAt = d7SentAt))

            useCase().execute(TODAY_7_DAYS)

            assertTrue(messagingPort.allMessages().isEmpty())
        }

    @Test
    fun `quarterly user receives reminder 7 days before quarterly deadline`() =
        runBlocking {
            // Update user to QUARTERLY
            val user = userRepo.findById(TEST_USER_ID)!!
            userRepo.save(user.copy(declarationPeriodicity = DeclarationPeriodicity.QUARTERLY))

            // Q1 deadline = April 30 → 7 days before = April 23
            useCase().execute(TODAY_7_DAYS)

            val messages = messagingPort.allMessages()
            assertEquals(1, messages.size)
            // For quarterly, periodKey = 2026-Q1
            val record = urssafReminderRepo.findByUserAndPeriod(TEST_USER_ID, "2026-Q1")
            assertNotNull(record?.d7SentAt)
        }
}
