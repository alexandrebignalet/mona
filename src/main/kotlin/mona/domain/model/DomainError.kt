package mona.domain.model

sealed class DomainError(val message: String) {
    class InvalidTransition(val from: InvoiceStatus, val to: String) :
        DomainError("Cannot transition from ${from::class.simpleName} to $to")

    class InvoiceNumberGap(val expected: String, val got: String) :
        DomainError("Expected $expected, got $got")

    class EmptyLineItems :
        DomainError("Invoice must have at least one line item")

    class NegativeAmount(val cents: Long) :
        DomainError("Amount cannot be negative: $cents")

    class ClientNotFound(val id: String) :
        DomainError("Client $id not found")

    class InvoiceNotFound(val id: InvoiceId) :
        DomainError("Invoice ${id.value} not found")

    class InvoiceNotCancellable(val number: InvoiceNumber) :
        DomainError("Invoice ${number.value} cannot be cancelled without credit note")

    class CreditNoteAmountMismatch(val invoiceAmount: Cents, val creditAmount: Cents) :
        DomainError("Credit note amount must match invoice")

    class SirenRequired :
        DomainError("SIREN is required to finalize invoice")

    class ProfileIncomplete(val missing: List<String>) :
        DomainError("Missing: ${missing.joinToString()}")

    class EmailDeliveryFailed(val statusCode: Int, val responseBody: String) :
        DomainError("Email delivery failed with HTTP $statusCode: $responseBody")

    class SirenNotFound(val siren: Siren) :
        DomainError("SIREN ${siren.value} not found in SIRENE database")

    class SireneLookupFailed(val reason: String) :
        DomainError(reason)

    class LlmUnavailable(val reason: String) :
        DomainError("LLM unavailable: $reason")
}
