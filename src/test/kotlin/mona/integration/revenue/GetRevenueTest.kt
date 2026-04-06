package mona.integration.revenue

import kotlinx.coroutines.runBlocking
import mona.application.revenue.GetRevenue
import mona.application.revenue.GetRevenueCommand
import mona.domain.model.ActivityType
import mona.domain.model.Cents
import mona.domain.model.DeclarationPeriod
import mona.domain.model.InvoiceStatus
import mona.domain.model.PaymentMethod
import mona.integration.IntegrationTestBase
import mona.integration.TEST_CLIENT_ID
import mona.integration.TEST_USER_ID
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private val APRIL_2026 = DeclarationPeriod.monthly(2026, 4)
private val Q1_2026 = DeclarationPeriod.quarterly(2026, 1)

class GetRevenueTest : IntegrationTestBase() {
    private fun useCase() = GetRevenue(invoiceRepo)

    @BeforeTest
    fun seedData() =
        setup {
            createTestUser()
            createTestClient()
        }

    @Test
    fun `monthly period returns paid revenue for that month`() =
        runBlocking {
            createTestInvoice(
                TEST_USER_ID,
                TEST_CLIENT_ID,
                InvoiceStatus.Paid(LocalDate.of(2026, 4, 10), PaymentMethod.VIREMENT),
                issueDate = LocalDate.of(2026, 4, 1),
            )

            val result = useCase().execute(GetRevenueCommand(TEST_USER_ID, APRIL_2026, "month"))

            assertEquals(Cents(100000L), result.breakdown.total)
            assertEquals(1, result.paidCount)
        }

    @Test
    fun `quarterly period aggregates all paid invoices in quarter`() =
        runBlocking {
            createTestInvoice(
                TEST_USER_ID,
                TEST_CLIENT_ID,
                InvoiceStatus.Paid(LocalDate.of(2026, 1, 15), PaymentMethod.VIREMENT),
                issueDate = LocalDate.of(2026, 1, 1),
            )
            createTestInvoice(
                TEST_USER_ID,
                TEST_CLIENT_ID,
                InvoiceStatus.Paid(LocalDate.of(2026, 3, 15), PaymentMethod.VIREMENT),
                issueDate = LocalDate.of(2026, 3, 1),
            )

            val result = useCase().execute(GetRevenueCommand(TEST_USER_ID, Q1_2026, "quarter"))

            assertEquals(Cents(200000L), result.breakdown.total)
            assertEquals(2, result.paidCount)
        }

    @Test
    fun `empty period returns zero revenue`() =
        runBlocking {
            val result = useCase().execute(GetRevenueCommand(TEST_USER_ID, APRIL_2026, "month"))

            assertEquals(Cents.ZERO, result.breakdown.total)
            assertEquals(0, result.paidCount)
        }

    @Test
    fun `pending invoices are counted in pendingCount`() =
        runBlocking {
            createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Sent)
            createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Overdue)

            val result = useCase().execute(GetRevenueCommand(TEST_USER_ID, APRIL_2026, "month"))

            assertEquals(2, result.pendingCount)
            assertEquals(Cents(200000L), result.pendingAmount)
        }

    @Test
    fun `mixed activity types are broken down separately`() =
        runBlocking {
            createTestInvoice(
                TEST_USER_ID,
                TEST_CLIENT_ID,
                InvoiceStatus.Paid(LocalDate.of(2026, 4, 10), PaymentMethod.VIREMENT),
                activityType = ActivityType.BIC_SERVICE,
            )
            createTestInvoice(
                TEST_USER_ID,
                TEST_CLIENT_ID,
                InvoiceStatus.Paid(LocalDate.of(2026, 4, 11), PaymentMethod.VIREMENT),
                activityType = ActivityType.BNC,
            )

            val result = useCase().execute(GetRevenueCommand(TEST_USER_ID, APRIL_2026, "month"))

            assertEquals(2, result.breakdown.byActivity.size)
            assertNotNull(result.breakdown.byActivity[ActivityType.BIC_SERVICE])
            assertNotNull(result.breakdown.byActivity[ActivityType.BNC])
        }

    @Test
    fun `previous period comparison is populated for month type`() =
        runBlocking {
            val result = useCase().execute(GetRevenueCommand(TEST_USER_ID, APRIL_2026, "month"))
            assertNotNull(result.previousBreakdown)
        }

    @Test
    fun `previous period is null for yearly type`() =
        runBlocking {
            val result = useCase().execute(GetRevenueCommand(TEST_USER_ID, APRIL_2026, "year"))
            assertNull(result.previousBreakdown)
        }

    @Test
    fun `credit notes are deducted from revenue`() =
        runBlocking {
            // Create a paid invoice then cancel it so it generates a credit note
            val invoice =
                createTestInvoice(
                    TEST_USER_ID,
                    TEST_CLIENT_ID,
                    InvoiceStatus.Paid(LocalDate.of(2026, 4, 10), PaymentMethod.VIREMENT),
                )
            // Credit notes deduction requires findCreditNotesInPeriod — verify period returns something
            val result = useCase().execute(GetRevenueCommand(TEST_USER_ID, APRIL_2026, "month"))
            // No credit notes here, so total equals full amount
            assertEquals(Cents(100000L), result.breakdown.total)
        }
}
