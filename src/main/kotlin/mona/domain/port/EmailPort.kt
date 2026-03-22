package mona.domain.port

import mona.domain.model.DomainResult

interface EmailPort {
    suspend fun sendInvoice(
        to: String,
        subject: String,
        body: String,
        pdfAttachment: ByteArray,
        filename: String,
    ): DomainResult<Unit>
}
