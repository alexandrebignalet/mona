package mona.infrastructure.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal

data class ParsedLineItem(
    val description: String,
    val quantity: BigDecimal,
    val unitPriceEuros: BigDecimal,
)

sealed class ParsedAction {
    data class CreateInvoice(
        val clientName: String,
        val lineItems: List<ParsedLineItem>,
        val issueDate: String?,
        val activityType: String?,
        val paymentDelayDays: Int?,
    ) : ParsedAction()

    data class SendInvoice(
        val invoiceNumber: String?,
        val clientName: String?,
    ) : ParsedAction()

    data class MarkPaid(
        val invoiceNumber: String?,
        val clientName: String?,
        val paymentMethod: String,
        val paymentDate: String?,
    ) : ParsedAction()

    data class UpdateDraft(
        val invoiceNumber: String?,
        val clientName: String?,
        val newClientName: String?,
        val lineItems: List<ParsedLineItem>?,
        val issueDate: String?,
        val paymentDelayDays: Int?,
        val activityType: String?,
    ) : ParsedAction()

    data class DeleteDraft(
        val invoiceNumber: String?,
        val clientName: String?,
    ) : ParsedAction()

    data class CancelInvoice(
        val invoiceNumber: String?,
        val clientName: String?,
        val reason: String?,
    ) : ParsedAction()

    data class CorrectInvoice(
        val invoiceNumber: String?,
        val clientName: String?,
        val newClientName: String?,
        val lineItems: List<ParsedLineItem>?,
        val issueDate: String?,
        val paymentDelayDays: Int?,
        val activityType: String?,
    ) : ParsedAction()

    data class GetRevenue(
        val periodType: String,
        val year: Int?,
        val month: Int?,
        val quarter: Int?,
    ) : ParsedAction()

    data object ExportInvoices : ParsedAction()

    data object GetUnpaid : ParsedAction()

    data class UpdateClient(
        val clientName: String,
        val newName: String?,
        val email: String?,
        val addressStreet: String?,
        val addressPostalCode: String?,
        val addressCity: String?,
        val companyName: String?,
        val siret: String?,
    ) : ParsedAction()

    data class UpdateProfile(
        val siren: String?,
        val name: String?,
        val activityType: String?,
        val email: String?,
        val iban: String?,
        val paymentDelayDays: Int?,
        val declarationPeriodicity: String?,
        val addressStreet: String?,
        val addressPostalCode: String?,
        val addressCity: String?,
    ) : ParsedAction()

    data class ConfigureSetting(
        val setting: String,
        val value: String,
    ) : ParsedAction()

    data object ListClients : ParsedAction()

    data class ClientHistory(
        val clientName: String,
    ) : ParsedAction()

    data class SearchSiren(
        val name: String,
        val city: String,
    ) : ParsedAction()

    data object DeleteAccount : ParsedAction()

    data class Conversational(
        val response: String,
    ) : ParsedAction()

    data class Unknown(
        val clarification: String,
    ) : ParsedAction()
}

object ActionParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(
        toolName: String,
        inputJson: String,
    ): ParsedAction {
        val obj = json.parseToJsonElement(inputJson).jsonObject
        return when (toolName) {
            "create_invoice" -> parseCreateInvoice(obj)
            "send_invoice" -> parseSendInvoice(obj)
            "mark_paid" -> parseMarkPaid(obj)
            "update_draft" -> parseUpdateDraft(obj)
            "delete_draft" -> ParsedAction.DeleteDraft(obj.strOrNull("invoice_number"), obj.strOrNull("client_name"))
            "cancel_invoice" -> parseCancelInvoice(obj)
            "correct_invoice" -> parseCorrectInvoice(obj)
            "get_revenue" -> parseGetRevenue(obj)
            "export_invoices" -> ParsedAction.ExportInvoices
            "get_unpaid" -> ParsedAction.GetUnpaid
            "update_client" -> parseUpdateClient(obj)
            "update_profile" -> parseUpdateProfile(obj)
            "search_siren" -> ParsedAction.SearchSiren(obj.str("name"), obj.str("city"))
            "configure_setting" -> ParsedAction.ConfigureSetting(obj.str("setting"), obj.str("value"))
            "list_clients" -> ParsedAction.ListClients
            "client_history" -> ParsedAction.ClientHistory(obj.str("client_name"))
            "delete_account" -> ParsedAction.DeleteAccount
            "conversational" -> ParsedAction.Conversational(obj.str("response"))
            "unknown" -> ParsedAction.Unknown(obj.str("clarification"))
            else -> ParsedAction.Unknown("Action non reconnue : $toolName")
        }
    }

    private fun parseCreateInvoice(obj: JsonObject) =
        ParsedAction.CreateInvoice(
            clientName = obj.str("client_name"),
            lineItems = obj["line_items"]?.jsonArray?.map { parseLineItem(it.jsonObject) } ?: emptyList(),
            issueDate = obj.strOrNull("issue_date"),
            activityType = obj.strOrNull("activity_type"),
            paymentDelayDays = obj.intOrNull("payment_delay_days"),
        )

    private fun parseSendInvoice(obj: JsonObject) =
        ParsedAction.SendInvoice(
            invoiceNumber = obj.strOrNull("invoice_number"),
            clientName = obj.strOrNull("client_name"),
        )

    private fun parseMarkPaid(obj: JsonObject) =
        ParsedAction.MarkPaid(
            invoiceNumber = obj.strOrNull("invoice_number"),
            clientName = obj.strOrNull("client_name"),
            paymentMethod = obj.str("payment_method"),
            paymentDate = obj.strOrNull("payment_date"),
        )

    private fun parseUpdateDraft(obj: JsonObject) =
        ParsedAction.UpdateDraft(
            invoiceNumber = obj.strOrNull("invoice_number"),
            clientName = obj.strOrNull("client_name"),
            newClientName = obj.strOrNull("new_client_name"),
            lineItems = obj["line_items"]?.jsonArray?.map { parseLineItem(it.jsonObject) },
            issueDate = obj.strOrNull("issue_date"),
            paymentDelayDays = obj.intOrNull("payment_delay_days"),
            activityType = obj.strOrNull("activity_type"),
        )

    private fun parseCancelInvoice(obj: JsonObject) =
        ParsedAction.CancelInvoice(
            invoiceNumber = obj.strOrNull("invoice_number"),
            clientName = obj.strOrNull("client_name"),
            reason = obj.strOrNull("reason"),
        )

    private fun parseCorrectInvoice(obj: JsonObject) =
        ParsedAction.CorrectInvoice(
            invoiceNumber = obj.strOrNull("invoice_number"),
            clientName = obj.strOrNull("client_name"),
            newClientName = obj.strOrNull("new_client_name"),
            lineItems = obj["line_items"]?.jsonArray?.map { parseLineItem(it.jsonObject) },
            issueDate = obj.strOrNull("issue_date"),
            paymentDelayDays = obj.intOrNull("payment_delay_days"),
            activityType = obj.strOrNull("activity_type"),
        )

    private fun parseGetRevenue(obj: JsonObject) =
        ParsedAction.GetRevenue(
            periodType = obj.str("period_type"),
            year = obj.intOrNull("year"),
            month = obj.intOrNull("month"),
            quarter = obj.intOrNull("quarter"),
        )

    private fun parseUpdateClient(obj: JsonObject) =
        ParsedAction.UpdateClient(
            clientName = obj.str("client_name"),
            newName = obj.strOrNull("new_name"),
            email = obj.strOrNull("email"),
            addressStreet = obj.strOrNull("address_street"),
            addressPostalCode = obj.strOrNull("address_postal_code"),
            addressCity = obj.strOrNull("address_city"),
            companyName = obj.strOrNull("company_name"),
            siret = obj.strOrNull("siret"),
        )

    private fun parseUpdateProfile(obj: JsonObject) =
        ParsedAction.UpdateProfile(
            siren = obj.strOrNull("siren"),
            name = obj.strOrNull("name"),
            activityType = obj.strOrNull("activity_type"),
            email = obj.strOrNull("email"),
            iban = obj.strOrNull("iban"),
            paymentDelayDays = obj.intOrNull("payment_delay_days"),
            declarationPeriodicity = obj.strOrNull("declaration_periodicity"),
            addressStreet = obj.strOrNull("address_street"),
            addressPostalCode = obj.strOrNull("address_postal_code"),
            addressCity = obj.strOrNull("address_city"),
        )

    private fun parseLineItem(obj: JsonObject) =
        ParsedLineItem(
            description = obj.str("description"),
            quantity = BigDecimal(obj["quantity"]?.jsonPrimitive?.content ?: "1"),
            unitPriceEuros = BigDecimal(obj["unit_price_euros"]?.jsonPrimitive?.content ?: "0"),
        )

    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.content ?: error("Missing required field: $key")

    private fun JsonObject.strOrNull(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.intOrNull(key: String): Int? = this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
}
