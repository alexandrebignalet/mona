package mona.infrastructure

import kotlinx.serialization.json.Json
import mona.domain.port.LlmToolDefinition
import mona.infrastructure.llm.ActionParser
import mona.infrastructure.llm.ParsedAction
import mona.infrastructure.llm.ToolDefinitions
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActionParserTest {
    // --- create_invoice ---

    @Test
    fun `parses create_invoice with required fields`() {
        val json =
            """{"client_name":"Acme","line_items":[{"description":"Dev web","quantity":1,"unit_price_euros":800}]}"""
        val action = ActionParser.parse("create_invoice", json)
        assertIs<ParsedAction.CreateInvoice>(action)
        assertEquals("Acme", action.clientName)
        assertEquals(1, action.lineItems.size)
        assertEquals("Dev web", action.lineItems[0].description)
        assertEquals(BigDecimal("1"), action.lineItems[0].quantity)
        assertEquals(BigDecimal("800"), action.lineItems[0].unitPriceEuros)
        assertNull(action.issueDate)
        assertNull(action.activityType)
        assertNull(action.paymentDelayDays)
    }

    @Test
    fun `parses create_invoice with all optional fields`() {
        val lineItems = """[{"description":"Conseil","quantity":2,"unit_price_euros":500.50}]"""
        val json =
            """{"client_name":"Durand","line_items":$lineItems,""" +
                """"issue_date":"2024-03-15","activity_type":"BNC","payment_delay_days":45}"""
        val action = ActionParser.parse("create_invoice", json)
        assertIs<ParsedAction.CreateInvoice>(action)
        assertEquals("Durand", action.clientName)
        assertEquals("2024-03-15", action.issueDate)
        assertEquals("BNC", action.activityType)
        assertEquals(45, action.paymentDelayDays)
        assertEquals(BigDecimal("500.50"), action.lineItems[0].unitPriceEuros)
        assertEquals(BigDecimal("2"), action.lineItems[0].quantity)
    }

    @Test
    fun `parses create_invoice with multiple line items`() {
        val json =
            """{"client_name":"Client","line_items":[""" +
                """{"description":"A","quantity":1,"unit_price_euros":100},""" +
                """{"description":"B","quantity":3,"unit_price_euros":50}]}"""
        val action = ActionParser.parse("create_invoice", json)
        assertIs<ParsedAction.CreateInvoice>(action)
        assertEquals(2, action.lineItems.size)
        assertEquals("A", action.lineItems[0].description)
        assertEquals("B", action.lineItems[1].description)
    }

    // --- send_invoice ---

    @Test
    fun `parses send_invoice with invoice number`() {
        val json = """{"invoice_number":"F-2024-01-001"}"""
        val action = ActionParser.parse("send_invoice", json)
        assertIs<ParsedAction.SendInvoice>(action)
        assertEquals("F-2024-01-001", action.invoiceNumber)
        assertNull(action.clientName)
    }

    @Test
    fun `parses send_invoice with client name only`() {
        val json = """{"client_name":"Martin"}"""
        val action = ActionParser.parse("send_invoice", json)
        assertIs<ParsedAction.SendInvoice>(action)
        assertNull(action.invoiceNumber)
        assertEquals("Martin", action.clientName)
    }

    // --- mark_paid ---

    @Test
    fun `parses mark_paid with required payment_method`() {
        val json = """{"payment_method":"VIREMENT","client_name":"Durand"}"""
        val action = ActionParser.parse("mark_paid", json)
        assertIs<ParsedAction.MarkPaid>(action)
        assertEquals("VIREMENT", action.paymentMethod)
        assertEquals("Durand", action.clientName)
        assertNull(action.invoiceNumber)
        assertNull(action.paymentDate)
    }

    @Test
    fun `parses mark_paid with all fields`() {
        val json =
            """{"invoice_number":"F-2024-02-003","payment_method":"CHEQUE","payment_date":"2024-02-28"}"""
        val action = ActionParser.parse("mark_paid", json)
        assertIs<ParsedAction.MarkPaid>(action)
        assertEquals("F-2024-02-003", action.invoiceNumber)
        assertEquals("CHEQUE", action.paymentMethod)
        assertEquals("2024-02-28", action.paymentDate)
    }

    // --- update_draft ---

    @Test
    fun `parses update_draft with partial fields`() {
        val json = """{"client_name":"Acme","payment_delay_days":15}"""
        val action = ActionParser.parse("update_draft", json)
        assertIs<ParsedAction.UpdateDraft>(action)
        assertEquals("Acme", action.clientName)
        assertEquals(15, action.paymentDelayDays)
        assertNull(action.lineItems)
        assertNull(action.invoiceNumber)
        assertNull(action.newClientName)
    }

    @Test
    fun `parses update_draft with line items`() {
        val json =
            """{"invoice_number":"F-2024-01-001",""" +
                """"line_items":[{"description":"New","quantity":1,"unit_price_euros":900}],""" +
                """"activity_type":"BIC_SERVICE"}"""
        val action = ActionParser.parse("update_draft", json)
        assertIs<ParsedAction.UpdateDraft>(action)
        assertEquals("F-2024-01-001", action.invoiceNumber)
        assertNotNull(action.lineItems)
        assertEquals(1, action.lineItems!!.size)
        assertEquals(BigDecimal("900"), action.lineItems!![0].unitPriceEuros)
        assertEquals("BIC_SERVICE", action.activityType)
    }

    // --- delete_draft ---

    @Test
    fun `parses delete_draft`() {
        val json = """{"invoice_number":"F-2024-01-001"}"""
        val action = ActionParser.parse("delete_draft", json)
        assertIs<ParsedAction.DeleteDraft>(action)
        assertEquals("F-2024-01-001", action.invoiceNumber)
        assertNull(action.clientName)
    }

    // --- cancel_invoice ---

    @Test
    fun `parses cancel_invoice with reason`() {
        val json = """{"invoice_number":"F-2024-01-002","reason":"Commande annulee"}"""
        val action = ActionParser.parse("cancel_invoice", json)
        assertIs<ParsedAction.CancelInvoice>(action)
        assertEquals("F-2024-01-002", action.invoiceNumber)
        assertEquals("Commande annulee", action.reason)
    }

    @Test
    fun `parses cancel_invoice without reason`() {
        val json = """{"client_name":"Durand"}"""
        val action = ActionParser.parse("cancel_invoice", json)
        assertIs<ParsedAction.CancelInvoice>(action)
        assertEquals("Durand", action.clientName)
        assertNull(action.reason)
    }

    // --- correct_invoice ---

    @Test
    fun `parses correct_invoice`() {
        val json =
            """{"invoice_number":"F-2024-01-003",""" +
                """"line_items":[{"description":"Correction","quantity":1,"unit_price_euros":1200}]}"""
        val action = ActionParser.parse("correct_invoice", json)
        assertIs<ParsedAction.CorrectInvoice>(action)
        assertEquals("F-2024-01-003", action.invoiceNumber)
        assertNotNull(action.lineItems)
        assertEquals(BigDecimal("1200"), action.lineItems!![0].unitPriceEuros)
    }

    // --- get_revenue ---

    @Test
    fun `parses get_revenue for month`() {
        val json = """{"period_type":"month","year":2024,"month":3}"""
        val action = ActionParser.parse("get_revenue", json)
        assertIs<ParsedAction.GetRevenue>(action)
        assertEquals("month", action.periodType)
        assertEquals(2024, action.year)
        assertEquals(3, action.month)
        assertNull(action.quarter)
    }

    @Test
    fun `parses get_revenue for quarter`() {
        val json = """{"period_type":"quarter","year":2024,"quarter":2}"""
        val action = ActionParser.parse("get_revenue", json)
        assertIs<ParsedAction.GetRevenue>(action)
        assertEquals("quarter", action.periodType)
        assertEquals(2, action.quarter)
        assertNull(action.month)
    }

    @Test
    fun `parses get_revenue for year`() {
        val json = """{"period_type":"year","year":2023}"""
        val action = ActionParser.parse("get_revenue", json)
        assertIs<ParsedAction.GetRevenue>(action)
        assertEquals("year", action.periodType)
        assertEquals(2023, action.year)
    }

    // --- export_invoices and get_unpaid ---

    @Test
    fun `parses export_invoices`() {
        val action = ActionParser.parse("export_invoices", "{}")
        assertIs<ParsedAction.ExportInvoices>(action)
    }

    @Test
    fun `parses get_unpaid`() {
        val action = ActionParser.parse("get_unpaid", "{}")
        assertIs<ParsedAction.GetUnpaid>(action)
    }

    // --- update_client ---

    @Test
    fun `parses update_client with required client_name`() {
        val json =
            """{"client_name":"Dupont","email":"dupont@example.com",""" +
                """"address_street":"10 rue de la Paix","address_postal_code":"75001","address_city":"Paris"}"""
        val action = ActionParser.parse("update_client", json)
        assertIs<ParsedAction.UpdateClient>(action)
        assertEquals("Dupont", action.clientName)
        assertEquals("dupont@example.com", action.email)
        assertEquals("10 rue de la Paix", action.addressStreet)
        assertEquals("75001", action.addressPostalCode)
        assertEquals("Paris", action.addressCity)
        assertNull(action.newName)
        assertNull(action.siret)
    }

    @Test
    fun `parses update_client with siret and company name`() {
        val json =
            """{"client_name":"SARL Test","new_name":"SARL Test Renomme",""" +
                """"company_name":"SARL Test Renomme","siret":"12345678901234"}"""
        val action = ActionParser.parse("update_client", json)
        assertIs<ParsedAction.UpdateClient>(action)
        assertEquals("SARL Test Renomme", action.newName)
        assertEquals("SARL Test Renomme", action.companyName)
        assertEquals("12345678901234", action.siret)
    }

    // --- update_profile ---

    @Test
    fun `parses update_profile with siren`() {
        val json = """{"siren":"123456789"}"""
        val action = ActionParser.parse("update_profile", json)
        assertIs<ParsedAction.UpdateProfile>(action)
        assertEquals("123456789", action.siren)
        assertNull(action.name)
        assertNull(action.iban)
    }

    @Test
    fun `parses update_profile with multiple fields`() {
        val json =
            """{"name":"Sophie Martin","activity_type":"BNC",""" +
                """"iban":"FR7630001007941234567890185","payment_delay_days":30,"declaration_periodicity":"QUARTERLY"}"""
        val action = ActionParser.parse("update_profile", json)
        assertIs<ParsedAction.UpdateProfile>(action)
        assertEquals("Sophie Martin", action.name)
        assertEquals("BNC", action.activityType)
        assertEquals("FR7630001007941234567890185", action.iban)
        assertEquals(30, action.paymentDelayDays)
        assertEquals("QUARTERLY", action.declarationPeriodicity)
    }

    // --- configure_setting ---

    @Test
    fun `parses configure_setting confirm_before_create`() {
        val json = """{"setting":"confirm_before_create","value":"false"}"""
        val action = ActionParser.parse("configure_setting", json)
        assertIs<ParsedAction.ConfigureSetting>(action)
        assertEquals("confirm_before_create", action.setting)
        assertEquals("false", action.value)
    }

    @Test
    fun `parses configure_setting default_payment_delay_days`() {
        val json = """{"setting":"default_payment_delay_days","value":"15"}"""
        val action = ActionParser.parse("configure_setting", json)
        assertIs<ParsedAction.ConfigureSetting>(action)
        assertEquals("default_payment_delay_days", action.setting)
        assertEquals("15", action.value)
    }

    // --- list_clients ---

    @Test
    fun `parses list_clients`() {
        val action = ActionParser.parse("list_clients", "{}")
        assertIs<ParsedAction.ListClients>(action)
    }

    // --- client_history ---

    @Test
    fun `parses client_history`() {
        val json = """{"client_name":"Renault"}"""
        val action = ActionParser.parse("client_history", json)
        assertIs<ParsedAction.ClientHistory>(action)
        assertEquals("Renault", action.clientName)
    }

    // --- conversational ---

    @Test
    fun `parses conversational`() {
        val json = """{"response":"Bonjour ! Comment puis-je t'aider ?"}"""
        val action = ActionParser.parse("conversational", json)
        assertIs<ParsedAction.Conversational>(action)
        assertEquals("Bonjour ! Comment puis-je t'aider ?", action.response)
    }

    // --- unknown ---

    @Test
    fun `parses unknown`() {
        val json = """{"clarification":"Tu peux reformuler ? Je n'ai pas compris."}"""
        val action = ActionParser.parse("unknown", json)
        assertIs<ParsedAction.Unknown>(action)
        assertEquals("Tu peux reformuler ? Je n'ai pas compris.", action.clarification)
    }

    @Test
    fun `returns Unknown for unrecognized tool name`() {
        val action = ActionParser.parse("nonexistent_tool", "{}")
        assertIs<ParsedAction.Unknown>(action)
        assertTrue(action.clarification.contains("nonexistent_tool"))
    }

    // --- ToolDefinitions ---

    @Test
    fun `ToolDefinitions contains exactly 19 tools`() {
        assertEquals(19, ToolDefinitions.all.size)
    }

    @Test
    fun `all tool definitions have valid JSON schemas`() {
        val jsonParser = Json { ignoreUnknownKeys = true }
        ToolDefinitions.all.forEach { tool ->
            val element = jsonParser.parseToJsonElement(tool.inputSchemaJson)
            assertNotNull(element, "Schema for ${tool.name} should parse as valid JSON")
        }
    }

    @Test
    fun `all 19 action tool names are present`() {
        val names = ToolDefinitions.all.map(LlmToolDefinition::name).toSet()
        val expected =
            setOf(
                "create_invoice",
                "send_invoice",
                "mark_paid",
                "update_draft",
                "delete_draft",
                "cancel_invoice",
                "correct_invoice",
                "get_revenue",
                "export_invoices",
                "get_unpaid",
                "update_client",
                "update_profile",
                "search_siren",
                "configure_setting",
                "list_clients",
                "client_history",
                "delete_account",
                "conversational",
                "unknown",
            )
        assertEquals(expected, names)
    }
}
