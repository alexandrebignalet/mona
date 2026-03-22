package mona.domain

import mona.domain.model.ActivityType
import mona.domain.model.Cents
import mona.domain.model.CreditNoteNumber
import mona.domain.model.CreditNoteSnapshot
import mona.domain.model.InvoiceId
import mona.domain.model.PaidInvoiceSnapshot
import mona.domain.service.RevenueCalculation
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RevenueCalculationTest {
    @Test
    fun `empty input returns zero revenue`() {
        val result = RevenueCalculation.compute(emptyList(), emptyList())
        assertEquals(Cents.ZERO, result.total)
        assertTrue(result.byActivity.isEmpty())
    }

    @Test
    fun `single activity sums correctly`() {
        val invoices =
            listOf(
                PaidInvoiceSnapshot(InvoiceId("1"), Cents(50000), LocalDate.of(2026, 1, 15), ActivityType.BNC),
                PaidInvoiceSnapshot(InvoiceId("2"), Cents(30000), LocalDate.of(2026, 2, 10), ActivityType.BNC),
            )
        val result = RevenueCalculation.compute(invoices, emptyList())
        assertEquals(Cents(80000), result.total)
        assertEquals(Cents(80000), result.byActivity[ActivityType.BNC])
    }

    @Test
    fun `credit notes offset revenue`() {
        val invoices =
            listOf(
                PaidInvoiceSnapshot(InvoiceId("1"), Cents(100000), LocalDate.of(2026, 1, 15), ActivityType.BNC),
            )
        val creditNotes =
            listOf(
                CreditNoteSnapshot(CreditNoteNumber("A-2026-01-001"), Cents(-20000), LocalDate.of(2026, 1, 20), ActivityType.BNC),
            )
        val result = RevenueCalculation.compute(invoices, creditNotes)
        assertEquals(Cents(80000), result.total)
    }

    @Test
    fun `mixed activities break down correctly`() {
        val invoices =
            listOf(
                PaidInvoiceSnapshot(InvoiceId("1"), Cents(50000), LocalDate.of(2026, 1, 15), ActivityType.BNC),
                PaidInvoiceSnapshot(InvoiceId("2"), Cents(70000), LocalDate.of(2026, 1, 20), ActivityType.BIC_VENTE),
            )
        val result = RevenueCalculation.compute(invoices, emptyList())
        assertEquals(Cents(120000), result.total)
        assertEquals(Cents(50000), result.byActivity[ActivityType.BNC])
        assertEquals(Cents(70000), result.byActivity[ActivityType.BIC_VENTE])
    }
}
