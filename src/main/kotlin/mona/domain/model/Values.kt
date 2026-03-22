package mona.domain.model

import java.time.LocalDate

@JvmInline
value class Cents(val value: Long) {
    operator fun plus(other: Cents) = Cents(Math.addExact(value, other.value))

    operator fun minus(other: Cents) = Cents(Math.subtractExact(value, other.value))

    operator fun times(multiplier: Long) = Cents(Math.multiplyExact(value, multiplier))

    fun isNegative() = value < 0

    fun isZero() = value == 0L

    companion object {
        val ZERO = Cents(0)
    }
}

@JvmInline
value class Siren(val value: String) {
    init {
        require(value.length == 9 && value.all { it.isDigit() })
    }
}

@JvmInline
value class Siret(val value: String) {
    init {
        require(value.length == 14 && value.all { it.isDigit() })
    }

    val siren: Siren get() = Siren(value.substring(0, 9))
}

@JvmInline
value class Email(val value: String) {
    init {
        require(value.contains("@") && value.length >= 5)
    }
}

@JvmInline
value class PaymentDelayDays(val value: Int) {
    init {
        require(value in 1..60) { "Payment delay must be 1-60 days" }
    }
}

@JvmInline
value class InvoiceId(val value: String)

@JvmInline
value class ClientId(val value: String)

@JvmInline
value class UserId(val value: String)

@JvmInline
value class InvoiceNumber(val value: String)

@JvmInline
value class CreditNoteNumber(val value: String)

data class PostalAddress(
    val street: String,
    val postalCode: String,
    val city: String,
    val country: String = "France",
) {
    fun formatted(): String = "$street\n$postalCode $city\n$country"
}

data class DeclarationPeriod(
    val start: LocalDate,
    val endInclusive: LocalDate,
) {
    init {
        require(!endInclusive.isBefore(start))
    }

    companion object {
        fun monthly(
            year: Int,
            month: Int,
        ): DeclarationPeriod {
            val s = LocalDate.of(year, month, 1)
            return DeclarationPeriod(s, s.withDayOfMonth(s.lengthOfMonth()))
        }

        fun quarterly(
            year: Int,
            quarter: Int,
        ): DeclarationPeriod {
            require(quarter in 1..4)
            val startMonth = (quarter - 1) * 3 + 1
            val s = LocalDate.of(year, startMonth, 1)
            val e = s.plusMonths(2).let { it.withDayOfMonth(it.lengthOfMonth()) }
            return DeclarationPeriod(s, e)
        }
    }
}
