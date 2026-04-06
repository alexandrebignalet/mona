package mona.domain.model

sealed class DomainError(val message: String) {
    data class InvalidTransition(val from: InvoiceStatus, val to: String) :
        DomainError("Cannot transition from ${from::class.simpleName} to $to")

    data class InvoiceNumberGap(val expected: String, val got: String) :
        DomainError("Expected $expected, got $got")

    data object EmptyLineItems :
        DomainError("Invoice must have at least one line item")

    data class NegativeAmount(val cents: Long) :
        DomainError("Amount cannot be negative: $cents")

    data class ClientNotFound(val id: String) :
        DomainError("Client $id not found")

    data class InvoiceNotFound(val id: InvoiceId) :
        DomainError("Invoice ${id.value} not found")

    data class InvoiceNotCancellable(val number: InvoiceNumber) :
        DomainError("Invoice ${number.value} cannot be cancelled without credit note")

    data class CreditNoteAmountMismatch(val invoiceAmount: Cents, val creditAmount: Cents) :
        DomainError("Credit note amount must match invoice")

    data object SirenRequired :
        DomainError("SIREN is required to finalize invoice")

    data class ProfileIncomplete(val missing: List<String>) :
        DomainError("Missing: ${missing.joinToString()}")

    data class EmailDeliveryFailed(val statusCode: Int, val responseBody: String) :
        DomainError("Email delivery failed with HTTP $statusCode: $responseBody")

    data class SirenNotFound(val siren: Siren) :
        DomainError("SIREN ${siren.value} not found in SIRENE database")

    data class SireneLookupFailed(val reason: String) :
        DomainError(reason)

    data class LlmUnavailable(val reason: String) :
        DomainError("LLM unavailable: $reason")

    data class UnknownSetting(val name: String) :
        DomainError("Unknown setting: $name")

    data class InvalidSettingValue(val setting: String, val value: String) :
        DomainError("Invalid value '$value' for setting '$setting'")

    data class PdfGenerationFailed(val reason: String) :
        DomainError("PDF generation failed: $reason")
}
