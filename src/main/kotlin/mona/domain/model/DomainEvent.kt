package mona.domain.model

import java.time.Instant
import java.time.LocalDate

sealed class DomainEvent {
    abstract val occurredAt: Instant

    data class InvoiceSent(
        val invoiceId: InvoiceId,
        val invoiceNumber: InvoiceNumber,
        val clientId: ClientId,
        val userId: UserId,
        override val occurredAt: Instant,
    ) : DomainEvent()

    data class InvoicePaid(
        val invoiceId: InvoiceId,
        val invoiceNumber: InvoiceNumber,
        val amount: Cents,
        val paidDate: LocalDate,
        val method: PaymentMethod,
        val activityType: ActivityType,
        val userId: UserId,
        override val occurredAt: Instant,
    ) : DomainEvent()

    data class InvoiceOverdue(
        val invoiceId: InvoiceId,
        val invoiceNumber: InvoiceNumber,
        val dueDate: LocalDate,
        val userId: UserId,
        override val occurredAt: Instant,
    ) : DomainEvent()

    data class InvoiceCancelled(
        val invoiceId: InvoiceId,
        val invoiceNumber: InvoiceNumber,
        val creditNote: CreditNote?,
        val userId: UserId,
        override val occurredAt: Instant,
    ) : DomainEvent()

    data class DraftDeleted(
        val invoiceId: InvoiceId,
        val invoiceNumber: InvoiceNumber,
        val userId: UserId,
        override val occurredAt: Instant,
    ) : DomainEvent()
}

data class TransitionResult(
    val invoice: Invoice,
    val events: List<DomainEvent>,
)
