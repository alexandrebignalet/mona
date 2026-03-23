package mona.domain.model

import java.time.Instant
import java.time.LocalDate

data class Invoice(
    val id: InvoiceId,
    val userId: UserId,
    val clientId: ClientId,
    val number: InvoiceNumber,
    val status: InvoiceStatus,
    val issueDate: LocalDate,
    val dueDate: LocalDate,
    val activityType: ActivityType,
    val lineItems: List<LineItem>,
    val pdfPath: String?,
    val creditNote: CreditNote?,
    val createdAt: Instant,
) {
    val amountHt: Cents
        get() = Cents(lineItems.sumOf { it.totalHt.value })

    /** DRAFT -> SENT */
    fun send(now: Instant): DomainResult<TransitionResult> {
        if (status !is InvoiceStatus.Draft) return invalidTransition("Sent")
        val updated = copy(status = InvoiceStatus.Sent)
        return DomainResult.Ok(
            TransitionResult(
                updated,
                listOf(DomainEvent.InvoiceSent(id, number, clientId, userId, now)),
            ),
        )
    }

    /** DRAFT|SENT|OVERDUE -> PAID */
    fun markPaid(
        date: LocalDate,
        method: PaymentMethod,
        now: Instant,
    ): DomainResult<TransitionResult> =
        when (status) {
            is InvoiceStatus.Draft, is InvoiceStatus.Sent, is InvoiceStatus.Overdue -> {
                val updated = copy(status = InvoiceStatus.Paid(date, method))
                DomainResult.Ok(
                    TransitionResult(
                        updated,
                        listOf(
                            DomainEvent.InvoicePaid(
                                id,
                                number,
                                amountHt,
                                date,
                                method,
                                activityType,
                                userId,
                                now,
                            ),
                        ),
                    ),
                )
            }
            else -> invalidTransition("Paid")
        }

    /** SENT -> DRAFT (on email bounce — delivery failed) */
    fun revertToDraft(): DomainResult<Invoice> {
        if (status !is InvoiceStatus.Sent) return invalidTransition("Draft (bounce)")
        return DomainResult.Ok(copy(status = InvoiceStatus.Draft))
    }

    /** SENT -> OVERDUE */
    fun markOverdue(now: Instant): DomainResult<TransitionResult> {
        if (status !is InvoiceStatus.Sent) return invalidTransition("Overdue")
        val updated = copy(status = InvoiceStatus.Overdue)
        return DomainResult.Ok(
            TransitionResult(
                updated,
                listOf(DomainEvent.InvoiceOverdue(id, number, dueDate, userId, now)),
            ),
        )
    }

    /** Updates mutable fields on a DRAFT invoice; status stays Draft. */
    fun updateDraft(
        clientId: ClientId = this.clientId,
        lineItems: List<LineItem> = this.lineItems,
        issueDate: LocalDate = this.issueDate,
        paymentDelay: PaymentDelayDays? = null,
        activityType: ActivityType = this.activityType,
    ): DomainResult<Invoice> {
        if (status !is InvoiceStatus.Draft) return invalidTransition("Draft (updateDraft)")
        if (lineItems.isEmpty()) return DomainResult.Err(DomainError.EmptyLineItems())
        val total = lineItems.fold(Cents.ZERO) { acc, it -> acc + it.totalHt }
        if (total.isZero() || total.isNegative()) return DomainResult.Err(DomainError.NegativeAmount(total.value))
        val delayDays =
            paymentDelay?.value
                ?: java.time.temporal.ChronoUnit.DAYS.between(this.issueDate, this.dueDate).toInt()
        return DomainResult.Ok(
            copy(
                clientId = clientId,
                lineItems = lineItems,
                issueDate = issueDate,
                dueDate = issueDate.plusDays(delayDays.toLong()),
                activityType = activityType,
            ),
        )
    }

    /** DRAFT -> CANCELLED (no credit note), SENT|OVERDUE|PAID -> CANCELLED (requires credit note) */
    fun cancel(
        creditNote: CreditNote?,
        now: Instant,
    ): DomainResult<TransitionResult> =
        when {
            status is InvoiceStatus.Draft -> {
                val updated = copy(status = InvoiceStatus.Cancelled)
                DomainResult.Ok(
                    TransitionResult(
                        updated,
                        listOf(DomainEvent.DraftDeleted(id, number, userId, now)),
                    ),
                )
            }
            (
                status is InvoiceStatus.Sent ||
                    status is InvoiceStatus.Overdue ||
                    status is InvoiceStatus.Paid
            ) && creditNote != null -> {
                val updated = copy(status = InvoiceStatus.Cancelled, creditNote = creditNote)
                DomainResult.Ok(
                    TransitionResult(
                        updated,
                        listOf(DomainEvent.InvoiceCancelled(id, number, creditNote, userId, now)),
                    ),
                )
            }
            else -> DomainResult.Err(DomainError.InvoiceNotCancellable(number))
        }

    private fun <T> invalidTransition(to: String): DomainResult<T> = DomainResult.Err(DomainError.InvalidTransition(status, to))

    companion object {
        /** Factory — validates invariants, returns DRAFT invoice */
        fun create(
            id: InvoiceId,
            userId: UserId,
            clientId: ClientId,
            number: InvoiceNumber,
            issueDate: LocalDate,
            paymentDelay: PaymentDelayDays,
            activityType: ActivityType,
            lineItems: List<LineItem>,
            now: Instant,
        ): DomainResult<Invoice> {
            if (lineItems.isEmpty()) return DomainResult.Err(DomainError.EmptyLineItems())
            val total = lineItems.fold(Cents.ZERO) { acc, it -> acc + it.totalHt }
            if (total.isZero() || total.isNegative()) {
                return DomainResult.Err(DomainError.NegativeAmount(total.value))
            }
            if (!InvoiceNumbering.validate(number)) {
                return DomainResult.Err(
                    DomainError.InvoiceNumberGap("valid F-YYYY-MM-NNN", number.value),
                )
            }
            return DomainResult.Ok(
                Invoice(
                    id, userId, clientId, number, InvoiceStatus.Draft,
                    issueDate, issueDate.plusDays(paymentDelay.value.toLong()),
                    activityType, lineItems, null, null, now,
                ),
            )
        }
    }
}
