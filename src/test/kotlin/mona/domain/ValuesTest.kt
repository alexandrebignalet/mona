package mona.domain

import mona.domain.model.Cents
import mona.domain.model.DeclarationPeriod
import mona.domain.model.Email
import mona.domain.model.PaymentDelayDays
import mona.domain.model.PostalAddress
import mona.domain.model.Siren
import mona.domain.model.Siret
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValuesTest {
    // --- Cents ---
    @Test
    fun `Cents addition works`() {
        assertEquals(Cents(300), Cents(100) + Cents(200))
    }

    @Test
    fun `Cents subtraction works`() {
        assertEquals(Cents(50), Cents(200) - Cents(150))
    }

    @Test
    fun `Cents multiplication works`() {
        assertEquals(Cents(600), Cents(200) * 3L)
    }

    @Test
    fun `Cents ZERO is zero`() {
        assertTrue(Cents.ZERO.isZero())
        assertFalse(Cents.ZERO.isNegative())
    }

    @Test
    fun `Cents isNegative detects negative`() {
        assertTrue(Cents(-1).isNegative())
        assertFalse(Cents(1).isNegative())
    }

    @Test
    fun `Cents addition overflow throws ArithmeticException`() {
        assertFailsWith<ArithmeticException> {
            Cents(Long.MAX_VALUE) + Cents(1)
        }
    }

    @Test
    fun `Cents subtraction overflow throws ArithmeticException`() {
        assertFailsWith<ArithmeticException> {
            Cents(Long.MIN_VALUE) - Cents(1)
        }
    }

    @Test
    fun `Cents multiplication overflow throws ArithmeticException`() {
        assertFailsWith<ArithmeticException> {
            Cents(Long.MAX_VALUE) * 2L
        }
    }

    // --- Siren ---
    @Test
    fun `Siren accepts valid 9-digit string`() {
        val siren = Siren("123456789")
        assertEquals("123456789", siren.value)
    }

    @Test
    fun `Siren rejects non-9-digit input`() {
        assertFailsWith<IllegalArgumentException> { Siren("12345") }
        assertFailsWith<IllegalArgumentException> { Siren("1234567890") }
        assertFailsWith<IllegalArgumentException> { Siren("12345678a") }
    }

    // --- Siret ---
    @Test
    fun `Siret accepts valid 14-digit string`() {
        val siret = Siret("12345678901234")
        assertEquals("12345678901234", siret.value)
    }

    @Test
    fun `Siret rejects non-14-digit input`() {
        assertFailsWith<IllegalArgumentException> { Siret("123456789") }
        assertFailsWith<IllegalArgumentException> { Siret("1234567890123a") }
    }

    @Test
    fun `Siret siren extracts first 9 digits`() {
        val siret = Siret("12345678901234")
        assertEquals(Siren("123456789"), siret.siren)
    }

    // --- Email ---
    @Test
    fun `Email accepts valid email`() {
        val email = Email("test@example.com")
        assertEquals("test@example.com", email.value)
    }

    @Test
    fun `Email rejects string without at sign`() {
        assertFailsWith<IllegalArgumentException> { Email("testexample.com") }
    }

    @Test
    fun `Email rejects too short string`() {
        assertFailsWith<IllegalArgumentException> { Email("a@b") }
    }

    // --- PaymentDelayDays ---
    @Test
    fun `PaymentDelayDays accepts valid range`() {
        assertEquals(30, PaymentDelayDays(30).value)
        assertEquals(1, PaymentDelayDays(1).value)
        assertEquals(60, PaymentDelayDays(60).value)
    }

    @Test
    fun `PaymentDelayDays rejects out of range`() {
        assertFailsWith<IllegalArgumentException> { PaymentDelayDays(0) }
        assertFailsWith<IllegalArgumentException> { PaymentDelayDays(61) }
        assertFailsWith<IllegalArgumentException> { PaymentDelayDays(-1) }
    }

    // --- PostalAddress ---
    @Test
    fun `PostalAddress formatted produces multiline string`() {
        val address = PostalAddress("12 Rue de la Paix", "75002", "Paris")
        assertEquals("12 Rue de la Paix\n75002 Paris\nFrance", address.formatted())
    }

    // --- DeclarationPeriod ---
    @Test
    fun `DeclarationPeriod monthly covers full month`() {
        val period = DeclarationPeriod.monthly(2026, 2)
        assertEquals(LocalDate.of(2026, 2, 1), period.start)
        assertEquals(LocalDate.of(2026, 2, 28), period.endInclusive)
    }

    @Test
    fun `DeclarationPeriod monthly handles leap year`() {
        val period = DeclarationPeriod.monthly(2024, 2)
        assertEquals(LocalDate.of(2024, 2, 29), period.endInclusive)
    }

    @Test
    fun `DeclarationPeriod quarterly Q1`() {
        val period = DeclarationPeriod.quarterly(2026, 1)
        assertEquals(LocalDate.of(2026, 1, 1), period.start)
        assertEquals(LocalDate.of(2026, 3, 31), period.endInclusive)
    }

    @Test
    fun `DeclarationPeriod quarterly Q4`() {
        val period = DeclarationPeriod.quarterly(2026, 4)
        assertEquals(LocalDate.of(2026, 10, 1), period.start)
        assertEquals(LocalDate.of(2026, 12, 31), period.endInclusive)
    }

    @Test
    fun `DeclarationPeriod rejects invalid quarter`() {
        assertFailsWith<IllegalArgumentException> { DeclarationPeriod.quarterly(2026, 0) }
        assertFailsWith<IllegalArgumentException> { DeclarationPeriod.quarterly(2026, 5) }
    }

    @Test
    fun `DeclarationPeriod rejects end before start`() {
        assertFailsWith<IllegalArgumentException> {
            DeclarationPeriod(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 2, 1))
        }
    }
}
