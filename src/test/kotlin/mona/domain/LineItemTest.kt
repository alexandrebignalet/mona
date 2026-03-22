package mona.domain

import mona.domain.model.Cents
import mona.domain.model.LineItem
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class LineItemTest {
    @Test
    fun `totalHt with integer quantity`() {
        val item = LineItem("Service", BigDecimal(3), Cents(10000))
        assertEquals(Cents(30000), item.totalHt)
    }

    @Test
    fun `totalHt with fractional quantity`() {
        val item = LineItem("Heures", BigDecimal("2.5"), Cents(5000))
        assertEquals(Cents(12500), item.totalHt)
    }

    @Test
    fun `totalHt rounds HALF_UP`() {
        // 3 * 333 = 999, but with fractional: 3.33 * 100 = 333.0
        val item = LineItem("Odd", BigDecimal("3.33"), Cents(100))
        assertEquals(Cents(333), item.totalHt)

        // 1.5 * 101 = 151.5 -> rounds to 152
        val item2 = LineItem("Round up", BigDecimal("1.5"), Cents(101))
        assertEquals(Cents(152), item2.totalHt)
    }

    @Test
    fun `totalHt with single quantity`() {
        val item = LineItem("Consultation", BigDecimal.ONE, Cents(80000))
        assertEquals(Cents(80000), item.totalHt)
    }
}
