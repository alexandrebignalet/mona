package mona.infrastructure.pdf

import mona.domain.model.Cents
import mona.domain.model.Client
import mona.domain.model.CreditNote
import mona.domain.model.DomainError
import mona.domain.model.DomainResult
import mona.domain.model.Invoice
import mona.domain.model.InvoiceNumber
import mona.domain.model.InvoiceStatus
import mona.domain.model.PaymentMethod
import mona.domain.model.User
import mona.domain.port.PdfPort
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.util.Matrix
import java.io.ByteArrayOutputStream
import java.time.format.DateTimeFormatter
import kotlin.math.abs

object PdfGenerator : PdfPort {
    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private const val LEFT_MARGIN = 50f
    private const val RIGHT_MARGIN = 50f
    private val PAGE_WIDTH = PDRectangle.A4.width
    private val PAGE_HEIGHT = PDRectangle.A4.height
    private val RIGHT_EDGE = PAGE_WIDTH - RIGHT_MARGIN
    private val COL_MID = PAGE_WIDTH / 2f

    override fun generateCreditNote(
        creditNote: CreditNote,
        originalInvoiceNumber: InvoiceNumber,
        user: User,
        client: Client,
        plainIban: String?,
    ): DomainResult<ByteArray> {
        val document = PDDocument()
        return try {
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            val regular = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
            PDPageContentStream(document, page).use { cs ->
                renderCreditNote(cs, creditNote, originalInvoiceNumber, user, client, plainIban, regular, bold)
            }
            DomainResult.Ok(ByteArrayOutputStream().also { document.save(it) }.toByteArray())
        } catch (e: Exception) {
            DomainResult.Err(DomainError.PdfGenerationFailed(e.message ?: "unknown error"))
        } finally {
            document.close()
        }
    }

    override fun generateInvoice(
        invoice: Invoice,
        user: User,
        client: Client,
        plainIban: String?,
    ): DomainResult<ByteArray> {
        val document = PDDocument()
        return try {
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            val regular = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
            PDPageContentStream(document, page).use { cs ->
                if (invoice.status is InvoiceStatus.Draft) {
                    renderWatermark(cs, bold)
                }
                renderInvoice(cs, invoice, user, client, plainIban, regular, bold)
            }
            DomainResult.Ok(ByteArrayOutputStream().also { document.save(it) }.toByteArray())
        } catch (e: Exception) {
            DomainResult.Err(DomainError.PdfGenerationFailed(e.message ?: "unknown error"))
        } finally {
            document.close()
        }
    }

    @Suppress("LongMethod")
    private fun renderInvoice(
        cs: PDPageContentStream,
        invoice: Invoice,
        user: User,
        client: Client,
        plainIban: String?,
        regular: PDType1Font,
        bold: PDType1Font,
    ) {
        var y = PAGE_HEIGHT - LEFT_MARGIN - 20f

        // Header: title + invoice number
        text(cs, bold, 22f, LEFT_MARGIN, y, "FACTURE")
        textRight(cs, regular, 11f, RIGHT_EDGE, y, invoice.number.value)
        y -= 18f
        val dateLabel = "Date d'emission : ${invoice.issueDate.format(DATE_FMT)}"
        textRight(cs, regular, 10f, RIGHT_EDGE, y, dateLabel)
        y -= 30f

        // Seller (left) and buyer (right) columns
        val blockTopY = y
        var sellerY = blockTopY
        var buyerY = blockTopY

        // Seller block
        text(cs, bold, 10f, LEFT_MARGIN, sellerY, "De :")
        sellerY -= 14f
        text(cs, bold, 11f, LEFT_MARGIN, sellerY, user.name ?: "-")
        sellerY -= 13f
        text(cs, regular, 9f, LEFT_MARGIN, sellerY, "Entreprise Individuelle")
        if (user.siren != null) {
            sellerY -= 12f
            text(cs, regular, 9f, LEFT_MARGIN, sellerY, "SIREN : ${user.siren.value}")
        }
        if (user.address != null) {
            for (line in user.address.formatted().lines()) {
                sellerY -= 12f
                text(cs, regular, 9f, LEFT_MARGIN, sellerY, line)
            }
        }

        // Buyer block
        text(cs, bold, 10f, COL_MID, buyerY, "A :")
        buyerY -= 14f
        text(cs, bold, 11f, COL_MID, buyerY, client.name)
        if (client.companyName != null) {
            buyerY -= 13f
            text(cs, regular, 9f, COL_MID, buyerY, client.companyName)
        }
        if (client.siret != null) {
            buyerY -= 12f
            text(cs, regular, 9f, COL_MID, buyerY, "SIRET : ${client.siret.value}")
        }
        if (client.address != null) {
            for (line in client.address.formatted().lines()) {
                buyerY -= 12f
                text(cs, regular, 9f, COL_MID, buyerY, line)
            }
        }

        y = minOf(sellerY, buyerY) - 24f

        // Separator
        line(cs, LEFT_MARGIN, y, RIGHT_EDGE, y)
        y -= 16f

        // Line items table
        val colQty = LEFT_MARGIN + 265f
        val colUnit = LEFT_MARGIN + 380f

        text(cs, bold, 10f, LEFT_MARGIN, y, "Description")
        textRight(cs, bold, 10f, colQty, y, "Qte")
        textRight(cs, bold, 10f, colUnit, y, "Prix unit. HT")
        textRight(cs, bold, 10f, RIGHT_EDGE, y, "Total HT")
        y -= 5f
        line(cs, LEFT_MARGIN, y, RIGHT_EDGE, y)
        y -= 13f

        for (item in invoice.lineItems) {
            text(cs, regular, 10f, LEFT_MARGIN, y, item.description)
            textRight(cs, regular, 10f, colQty, y, item.quantity.stripTrailingZeros().toPlainString())
            textRight(cs, regular, 10f, colUnit, y, formatCents(item.unitPriceHt))
            textRight(cs, regular, 10f, RIGHT_EDGE, y, formatCents(item.totalHt))
            y -= 14f
        }

        y -= 4f
        line(cs, LEFT_MARGIN, y, RIGHT_EDGE, y)
        y -= 16f

        // Totals
        textRight(cs, bold, 10f, colUnit, y, "Total HT :")
        textRight(cs, bold, 10f, RIGHT_EDGE, y, formatCents(invoice.amountHt))
        y -= 14f
        textRight(cs, regular, 10f, colUnit, y, "Total TTC :")
        textRight(cs, regular, 10f, RIGHT_EDGE, y, formatCents(invoice.amountHt))
        y -= 20f

        // TVA mention
        text(cs, regular, 9f, LEFT_MARGIN, y, "TVA non applicable, article 293 B du CGI")
        y -= 22f

        // Payment terms
        text(cs, bold, 10f, LEFT_MARGIN, y, "Conditions de paiement")
        y -= 14f
        val delayDays = invoice.dueDate.toEpochDay() - invoice.issueDate.toEpochDay()
        val paymentTermsLine =
            "Paiement a $delayDays jours - Echeance : ${invoice.dueDate.format(DATE_FMT)}"
        text(cs, regular, 10f, LEFT_MARGIN, y, paymentTermsLine)
        y -= 14f
        text(cs, regular, 9f, LEFT_MARGIN, y, "Penalites de retard : 3 fois le taux d'interet legal en vigueur")
        y -= 12f
        val recoveryLine = "Indemnite forfaitaire pour frais de recouvrement en cas de retard : 40 EUR"
        text(cs, regular, 9f, LEFT_MARGIN, y, recoveryLine)
        y -= 18f

        // Payment method (for paid invoices)
        val paidStatus = invoice.status as? InvoiceStatus.Paid
        if (paidStatus != null) {
            text(cs, regular, 10f, LEFT_MARGIN, y, "Mode de reglement : ${paidStatus.method.toFrench()}")
            y -= 18f
        }

        // IBAN section
        if (plainIban != null) {
            line(cs, LEFT_MARGIN, y, RIGHT_EDGE, y)
            y -= 14f
            text(cs, bold, 10f, LEFT_MARGIN, y, "Coordonnees bancaires")
            y -= 14f
            text(cs, regular, 10f, LEFT_MARGIN, y, "IBAN : $plainIban")
        }
    }

    @Suppress("LongMethod")
    private fun renderCreditNote(
        cs: PDPageContentStream,
        creditNote: CreditNote,
        originalInvoiceNumber: InvoiceNumber,
        user: User,
        client: Client,
        plainIban: String?,
        regular: PDType1Font,
        bold: PDType1Font,
    ) {
        var y = PAGE_HEIGHT - LEFT_MARGIN - 20f

        // Header: title + credit note number
        text(cs, bold, 22f, LEFT_MARGIN, y, "AVOIR")
        textRight(cs, regular, 11f, RIGHT_EDGE, y, creditNote.number.value)
        y -= 18f
        val dateLabel = "Date d'emission : ${creditNote.issueDate.format(DATE_FMT)}"
        textRight(cs, regular, 10f, RIGHT_EDGE, y, dateLabel)
        y -= 14f
        val refLabel = "Avoir sur la facture : ${originalInvoiceNumber.value}"
        textRight(cs, regular, 10f, RIGHT_EDGE, y, refLabel)
        y -= 26f

        // Seller (left) and buyer (right) columns
        val blockTopY = y
        var sellerY = blockTopY
        var buyerY = blockTopY

        // Seller block
        text(cs, bold, 10f, LEFT_MARGIN, sellerY, "De :")
        sellerY -= 14f
        text(cs, bold, 11f, LEFT_MARGIN, sellerY, user.name ?: "-")
        sellerY -= 13f
        text(cs, regular, 9f, LEFT_MARGIN, sellerY, "Entreprise Individuelle")
        if (user.siren != null) {
            sellerY -= 12f
            text(cs, regular, 9f, LEFT_MARGIN, sellerY, "SIREN : ${user.siren.value}")
        }
        if (user.address != null) {
            for (line in user.address.formatted().lines()) {
                sellerY -= 12f
                text(cs, regular, 9f, LEFT_MARGIN, sellerY, line)
            }
        }

        // Buyer block
        text(cs, bold, 10f, COL_MID, buyerY, "A :")
        buyerY -= 14f
        text(cs, bold, 11f, COL_MID, buyerY, client.name)
        if (client.companyName != null) {
            buyerY -= 13f
            text(cs, regular, 9f, COL_MID, buyerY, client.companyName)
        }
        if (client.siret != null) {
            buyerY -= 12f
            text(cs, regular, 9f, COL_MID, buyerY, "SIRET : ${client.siret.value}")
        }
        if (client.address != null) {
            for (line in client.address.formatted().lines()) {
                buyerY -= 12f
                text(cs, regular, 9f, COL_MID, buyerY, line)
            }
        }

        y = minOf(sellerY, buyerY) - 24f

        // Separator
        line(cs, LEFT_MARGIN, y, RIGHT_EDGE, y)
        y -= 16f

        // Amount section
        val colUnit = LEFT_MARGIN + 380f
        text(cs, bold, 10f, LEFT_MARGIN, y, "Description")
        textRight(cs, bold, 10f, RIGHT_EDGE, y, "Montant HT")
        y -= 5f
        line(cs, LEFT_MARGIN, y, RIGHT_EDGE, y)
        y -= 13f

        val description = creditNote.reason.ifBlank { "Annulation facture ${originalInvoiceNumber.value}" }
        text(cs, regular, 10f, LEFT_MARGIN, y, description)
        textRight(cs, regular, 10f, RIGHT_EDGE, y, formatCents(creditNote.amountHt))
        y -= 14f

        y -= 4f
        line(cs, LEFT_MARGIN, y, RIGHT_EDGE, y)
        y -= 16f

        // Totals
        textRight(cs, bold, 10f, colUnit, y, "Total HT :")
        textRight(cs, bold, 10f, RIGHT_EDGE, y, formatCents(creditNote.amountHt))
        y -= 14f
        textRight(cs, regular, 10f, colUnit, y, "Total TTC :")
        textRight(cs, regular, 10f, RIGHT_EDGE, y, formatCents(creditNote.amountHt))
        y -= 20f

        // TVA mention
        text(cs, regular, 9f, LEFT_MARGIN, y, "TVA non applicable, article 293 B du CGI")
        y -= 22f

        // Late payment penalty mention (mandatory French legal mention)
        text(cs, regular, 9f, LEFT_MARGIN, y, "Penalites de retard : 3 fois le taux d'interet legal en vigueur")
        y -= 12f
        val recoveryLine = "Indemnite forfaitaire pour frais de recouvrement en cas de retard : 40 EUR"
        text(cs, regular, 9f, LEFT_MARGIN, y, recoveryLine)
        y -= 18f

        // IBAN section
        if (plainIban != null) {
            line(cs, LEFT_MARGIN, y, RIGHT_EDGE, y)
            y -= 14f
            text(cs, bold, 10f, LEFT_MARGIN, y, "Coordonnees bancaires")
            y -= 14f
            text(cs, regular, 10f, LEFT_MARGIN, y, "IBAN : $plainIban")
        }
    }

    private fun renderWatermark(
        cs: PDPageContentStream,
        font: PDType1Font,
    ) {
        cs.saveGraphicsState()
        cs.setNonStrokingColor(0.78f, 0.78f, 0.78f)
        cs.beginText()
        cs.setFont(font, 90f)
        val angle = Math.toRadians(45.0)
        val cosA = Math.cos(angle).toFloat()
        val sinA = Math.sin(angle).toFloat()
        val cx = PAGE_WIDTH / 2f - 170f
        val cy = PAGE_HEIGHT / 2f - 20f
        cs.setTextMatrix(Matrix(cosA, sinA, -sinA, cosA, cx, cy))
        cs.showText("BROUILLON")
        cs.endText()
        cs.restoreGraphicsState()
    }

    private fun text(
        cs: PDPageContentStream,
        font: PDType1Font,
        size: Float,
        x: Float,
        y: Float,
        txt: String,
    ) {
        cs.beginText()
        cs.setFont(font, size)
        cs.newLineAtOffset(x, y)
        cs.showText(txt)
        cs.endText()
    }

    private fun textRight(
        cs: PDPageContentStream,
        font: PDType1Font,
        size: Float,
        rightX: Float,
        y: Float,
        txt: String,
    ) {
        val width = font.getStringWidth(txt) / 1000f * size
        text(cs, font, size, rightX - width, y, txt)
    }

    private fun line(
        cs: PDPageContentStream,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
    ) {
        cs.setLineWidth(0.5f)
        cs.moveTo(x1, y1)
        cs.lineTo(x2, y2)
        cs.stroke()
    }

    private fun formatCents(cents: Cents): String {
        val absVal = abs(cents.value)
        val euros = absVal / 100
        val centPart = absVal % 100
        val sign = if (cents.value < 0) "-" else ""
        return "$sign$euros,${centPart.toString().padStart(2, '0')} EUR"
    }

    private fun PaymentMethod.toFrench(): String =
        when (this) {
            PaymentMethod.VIREMENT -> "Virement bancaire"
            PaymentMethod.CHEQUE -> "Cheque"
            PaymentMethod.ESPECES -> "Especes"
            PaymentMethod.CARTE -> "Carte bancaire"
            PaymentMethod.AUTRE -> "Autre"
        }
}
