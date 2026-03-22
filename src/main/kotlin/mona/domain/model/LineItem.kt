package mona.domain.model

import java.math.BigDecimal
import java.math.RoundingMode

data class LineItem(
    val description: String,
    val quantity: BigDecimal,
    val unitPriceHt: Cents,
) {
    val totalHt: Cents
        get() =
            Cents(
                (quantity * BigDecimal(unitPriceHt.value))
                    .setScale(0, RoundingMode.HALF_UP)
                    .toLong(),
            )
}
