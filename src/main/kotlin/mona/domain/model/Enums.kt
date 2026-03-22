package mona.domain.model

import java.time.LocalDate

enum class PaymentMethod { VIREMENT, CHEQUE, ESPECES, CARTE, AUTRE }

enum class ActivityType { BIC_VENTE, BIC_SERVICE, BNC }

enum class DeclarationPeriodicity { MONTHLY, QUARTERLY }

sealed class InvoiceStatus {
    data object Draft : InvoiceStatus()

    data object Sent : InvoiceStatus()

    data class Paid(val date: LocalDate, val method: PaymentMethod) : InvoiceStatus()

    data object Overdue : InvoiceStatus()

    data object Cancelled : InvoiceStatus()
}
