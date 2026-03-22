package mona.domain

import mona.domain.model.CreditNoteNumber
import mona.domain.model.CreditNoteNumbering
import mona.domain.model.DomainResult
import mona.domain.model.InvoiceNumber
import mona.domain.model.InvoiceNumbering
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InvoiceNumberingTest {
    private val march2026 = YearMonth.of(2026, 3)

    @Test
    fun `next returns 001 when no previous`() {
        val result = InvoiceNumbering.next(march2026, null)
        assertIs<DomainResult.Ok<InvoiceNumber>>(result)
        assertEquals("F-2026-03-001", result.value.value)
    }

    @Test
    fun `next increments from last`() {
        val result = InvoiceNumbering.next(march2026, InvoiceNumber("F-2026-03-005"))
        assertIs<DomainResult.Ok<InvoiceNumber>>(result)
        assertEquals("F-2026-03-006", result.value.value)
    }

    @Test
    fun `next returns error on sequence overflow`() {
        val result = InvoiceNumbering.next(march2026, InvoiceNumber("F-2026-03-999"))
        assertIs<DomainResult.Err>(result)
    }

    @Test
    fun `validate accepts valid format`() {
        assertTrue(InvoiceNumbering.validate(InvoiceNumber("F-2026-03-001")))
        assertTrue(InvoiceNumbering.validate(InvoiceNumber("F-2025-12-999")))
    }

    @Test
    fun `validate rejects invalid format`() {
        assertFalse(InvoiceNumbering.validate(InvoiceNumber("A-2026-03-001")))
        assertFalse(InvoiceNumbering.validate(InvoiceNumber("F-26-03-001")))
        assertFalse(InvoiceNumbering.validate(InvoiceNumber("F-2026-3-001")))
        assertFalse(InvoiceNumbering.validate(InvoiceNumber("F-2026-03-01")))
        assertFalse(InvoiceNumbering.validate(InvoiceNumber("invalid")))
    }

    @Test
    fun `isContiguous detects consecutive numbers`() {
        assertTrue(
            InvoiceNumbering.isContiguous(
                InvoiceNumber("F-2026-03-001"),
                InvoiceNumber("F-2026-03-002"),
            ),
        )
    }

    @Test
    fun `isContiguous detects gaps`() {
        assertFalse(
            InvoiceNumbering.isContiguous(
                InvoiceNumber("F-2026-03-001"),
                InvoiceNumber("F-2026-03-003"),
            ),
        )
    }

    @Test
    fun `month rollover produces correct prefix`() {
        val jan = YearMonth.of(2027, 1)
        val result = InvoiceNumbering.next(jan, null)
        assertIs<DomainResult.Ok<InvoiceNumber>>(result)
        assertEquals("F-2027-01-001", result.value.value)
    }

    // Credit Note Numbering
    @Test
    fun `credit note next returns 001 when no previous`() {
        val result = CreditNoteNumbering.next(march2026, null)
        assertIs<DomainResult.Ok<CreditNoteNumber>>(result)
        assertEquals("A-2026-03-001", result.value.value)
    }

    @Test
    fun `credit note next increments`() {
        val result = CreditNoteNumbering.next(march2026, CreditNoteNumber("A-2026-03-002"))
        assertIs<DomainResult.Ok<CreditNoteNumber>>(result)
        assertEquals("A-2026-03-003", result.value.value)
    }
}
