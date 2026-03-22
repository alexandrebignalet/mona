package mona.domain.model

import java.time.YearMonth

object InvoiceNumbering {
    fun next(
        yearMonth: YearMonth,
        lastInMonth: InvoiceNumber?,
    ): DomainResult<InvoiceNumber> {
        val prefix = "F-${yearMonth.year}-${"%02d".format(yearMonth.monthValue)}-"
        if (lastInMonth == null) return DomainResult.Ok(InvoiceNumber("${prefix}001"))
        val seq =
            parseSequence(lastInMonth)
                ?: return DomainResult.Err(
                    DomainError.InvoiceNumberGap("valid F-YYYY-MM-NNN", lastInMonth.value),
                )
        if (seq + 1 > 999) {
            return DomainResult.Err(
                DomainError.InvoiceNumberGap("sequence overflow", lastInMonth.value),
            )
        }
        return DomainResult.Ok(InvoiceNumber("$prefix${"%03d".format(seq + 1)}"))
    }

    fun validate(number: InvoiceNumber): Boolean = number.value.matches(Regex("^F-\\d{4}-\\d{2}-\\d{3}$"))

    fun isContiguous(
        last: InvoiceNumber,
        next: InvoiceNumber,
    ): Boolean {
        val a = parseSequence(last) ?: return false
        val b = parseSequence(next) ?: return false
        return b == a + 1
    }

    private fun parseSequence(n: InvoiceNumber): Int? = n.value.split("-").takeIf { it.size == 4 }?.get(3)?.toIntOrNull()
}

object CreditNoteNumbering {
    fun next(
        yearMonth: YearMonth,
        lastInMonth: CreditNoteNumber?,
    ): DomainResult<CreditNoteNumber> {
        val prefix = "A-${yearMonth.year}-${"%02d".format(yearMonth.monthValue)}-"
        if (lastInMonth == null) return DomainResult.Ok(CreditNoteNumber("${prefix}001"))
        val seq =
            lastInMonth.value.split("-").getOrNull(3)?.toIntOrNull()
                ?: return DomainResult.Err(
                    DomainError.InvoiceNumberGap("valid A-YYYY-MM-NNN", lastInMonth.value),
                )
        return DomainResult.Ok(CreditNoteNumber("$prefix${"%03d".format(seq + 1)}"))
    }
}
