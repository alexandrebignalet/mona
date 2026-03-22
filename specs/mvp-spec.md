# Mona MVP — Product Specification

## Overview

Mona is a conversational assistant that lets French auto-entrepreneurs manage invoices and URSSAF declarations entirely through chat. Users send natural language messages in French; the bot parses them, executes structured actions, and responds conversationally.

Multi-user from day one. French-speaking UX. Spec written in English for development and LLM prompt engineering purposes.

### Deployment Strategy

**V1 — Validate the core loop.** Ship fast and test with one real lead (admin-phobic auto-entrepreneur who currently invoices manually or not at all). All features are unlocked — no premium/free distinction.

**V1 success criteria** (measured over 4+ weeks with the validation user):
- Creates **≥5 real invoices** (not test invoices)
- Returns **unprompted** (not just responding to reminders) at least once per week
- **Sends ≥3 invoices** to real clients via email through Mona
- **Marks ≥1 invoice as paid** — proves payment tracking adoption
- **Does not revert** to their previous invoicing method

**V2 — Expand and grow.** Once the core loop is validated, V2 focuses on acquiring new users, introducing the freemium paywall based on observed usage patterns, and adding features that drive engagement and retention at scale.

### Channel Strategy

**V1 launches on Telegram** — the Telegram Bot API is free, which minimizes financial risk during validation. Telegram is the **validation channel**, not the long-term target channel. The French market lives on WhatsApp; Telegram skews tech-savvy.

**WhatsApp migration trigger:** WhatsApp Business API (~€50-100/month) is activated once Mona generates enough revenue to cover the cost. This is a revenue milestone, not a user count milestone.

The messaging abstraction (see tech spec) ensures the core product is channel-agnostic from day one.

---

## 1. User Onboarding

### Progressive Flow

Onboarding is spread across the user's first interactions to minimize upfront friction. **Key principle: the user should reach the "aha moment" (seeing a PDF invoice) as fast as possible — administrative data is collected just-in-time, not upfront.**

| Step | Trigger | Data Collected | Blocking? |
|------|---------|---------------|-----------|
| 0 | User starts bot (`/start`) | `telegram_id` (auto) | No |
| 1 | Mona introduces herself and invites the user to create an invoice | Nothing — straight to value | No |
| 2 | User creates first invoice → Mona generates a **draft PDF** immediately (with "BROUILLON" watermark, no SIREN required) | Invoice data only | No — draft PDF is sent instantly |
| 3 | Mona asks for SIREN to **finalize** the invoice | `siren` → auto-fills `name`, `address`, `activity_type` via SIRENE API | Yes — required before invoice is finalized |
| 4 | User confirms auto-filled profile | User corrects any wrong fields or confirms | Yes — one message |
| 4b | Mona asks for default payment terms | `default_payment_delay_days` — default 30 | Yes — one message |
| 4c | Mona asks for IBAN (for payment info on invoices) | `iban` — stored encrypted | No — encouraged but optional |
| 5 | Mona collects email | `email` — for sending invoices and future account recovery | No |
| 6 | Mona sets up URSSAF reminders (collected later, in context) | `declaration_periodicity` | Yes — required before reminders activate |

At each step, Mona asks naturally — never feels like a form.

### SIREN Verification (SIRENE API)

SIREN is collected **after the aha moment** — the user first sees a draft PDF with a "BROUILLON" watermark. Then Mona asks for SIREN to finalize. On receiving the SIREN, Mona validates it against the **INSEE SIRENE API** (free, public) and auto-fills legal name, business address, and activity type (from NAF/APE code mapping).

**Two paths for SIREN collection:**

1. **Direct entry:** The user types their 9-digit SIREN number.
2. **Search by name:** If the user doesn't know their SIREN, Mona asks for their registration name and city, searches the SIRENE API, and proposes matches (max 5 results).

```
🤖 "Pour la finaliser, j'ai besoin de ton numéro SIREN — c'est quoi ?
    (Si tu l'as pas sous la main, dis-moi ton nom et ta ville, je cherche pour toi)"
👤 "je sais pas"
🤖 "Pas de souci — tu t'es inscrit sous quel nom ? Et dans quelle ville ?"
👤 "Sophie Martin, Paris"
🤖 "J'ai trouvé 2 résultats :
    1. Sophie Martin — 12 rue de la Paix, 75002 Paris — Conseil en gestion
    2. Sophie Martin — 8 avenue Montaigne, 75008 Paris — Formation
    C'est laquelle ?"
👤 "La première"
```

**Hint (shown alongside the SIREN request):** *"Tu le trouves aussi sur autoentrepreneur.urssaf.fr ou ton certificat INSEE."*

**Deep links:** Partners or accountants can share `t.me/monabot?start=siren_123456789` links that pre-fill the SIREN, skipping the collection step entirely.

### Onboarding Drop-Off Recovery

If the user creates a draft invoice but abandons onboarding at the SIREN step, Mona sends **two reminders**:

**Reminder 1 — after 24 hours:**
```
🤖 "Salut ! Tu as un brouillon de facture en attente — tu veux le finaliser ?
    Il me manque juste ton SIREN pour générer la facture définitive."
```

**Reminder 2 — after 72 hours** (proactively offers name+city search):
```
🤖 "Hey ! Ton brouillon est toujours en attente.
    Si tu connais pas ton SIREN, dis-moi juste ton nom et ta ville — je le retrouve pour toi ✓"
```

**Rules:**
- Two reminders max per drop-off — no nagging beyond that
- If the user responds to Reminder 1, Reminder 2 is skipped
- If the user doesn't respond to either, no further follow-up
- The drop-off step is tracked as a **funnel metric** (% who provide SIREN within 72h of first draft)

### Example Onboarding

```
🤖 "Salut ! Moi c'est Mona, je gère ta facturation en 2 secondes.
    Essaie : dis-moi « Facture 500€ pour Dupont, consulting »"
👤 "Facture 800€ pour Martin, coaching"
🤖 "Je crée cette facture ?
    → Client : Martin
    → Montant : 800,00€ HT
    → Prestation : Coaching
    → Date : 22/03/2026
    Confirme avec OK ou corrige-moi ✏️"
👤 "OK"
🤖 "Voilà un aperçu de ta facture ✓
    📄 PDF BROUILLON en pièce jointe.
    Pour la finaliser, j'ai besoin de ton numéro SIREN — c'est quoi ?
    (Tu le trouves sur autoentrepreneur.urssaf.fr ou ton certificat INSEE)"
👤 "123 456 789"
🤖 "J'ai trouvé ton entreprise — Sophie Martin, 12 rue de la Paix, 75002 Paris,
    activité libérale. On part là-dessus ?"
👤 "Oui"
🤖 "Top ✓ Par défaut je mets 30 jours pour le délai de paiement — ça te va
    ou tu préfères autre chose (15 jours, 45 jours, immédiat) ?"
👤 "30 jours c'est bien"
🤖 "Noté ✓ Encore un truc — ton IBAN, pour que tes clients sachent où payer ?
    (Tu le trouves dans ton appli bancaire)"
👤 "FR76 3000 1007 9412 3456 7890 185"
🤖 "Noté ✓ Facture F-2026-03-001 finalisée !
    📄 PDF définitif en pièce jointe (avec ton IBAN).
    Je l'envoie par mail à Martin ?"
```

If the user needs to correct something:
```
👤 "Non, c'est Sophie Dupont-Martin"
🤖 "Noté ✓ Sophie Dupont-Martin. Le reste est bon ?"
👤 "Oui"
```

Declaration periodicity is collected later, in context, when URSSAF reminders are set up.

---

## 2. Core Feature: Invoice Creation

### 2.1 Natural Language Parsing

The bot accepts free-form French messages and extracts structured invoice data.

**Supported input patterns:**
- `"Facture 800€ pour Dupont, coaching"`
- `"Facture 800 balles à Jean Dupont pour du coaching le 15 mars"`
- `"Fais une facture de 1200€ pour Martin, développement web, payé par virement"`
- `"Facture Dupont : 3 jours de dev à 500€ + formation 400€"` (multi-line)

All amounts are stored in **cents** internally. Currency is EUR only in V1.

**Non-EUR currency handling:** If the user mentions a non-EUR currency, Mona refuses clearly: *"Pour l'instant je ne gère que l'euro — tu veux que je fasse la facture en euros ?"*

**Currency display in chat vs. PDF:** In conversational messages, Mona uses simplified amounts — `"800€"` — without the "HT" suffix. For franchise en base users (the entire V1 target), HT and TTC are identical, so the distinction is confusing in chat. The full formal format (`800,00€ HT`, `TVA non applicable`) appears only on the invoice PDF, where it's legally required.

### 2.2 Multi-Line Invoices

Mona fully supports multi-line invoices:

```
👤 "Facture Dupont : 3 jours de dev à 500€ + formation 400€"
🤖 "Je crée cette facture ?
    → Client : Jean Dupont
    → 3x Développement — 500,00€ = 1 500,00€ HT
    → 1x Formation — 400,00€ HT
    → Total : 1 900,00€ HT
    Confirme avec OK ou corrige-moi ✏️"
```

Single-item invoices are a special case of multi-line (one line item).

### 2.3 Confirmation Flow

**Default behavior: always confirm before creating.**

```
👤 "Facture 800€ pour Dupont, coaching"
🤖 "Je crée cette facture ?
    → Client : Jean Dupont
    → 1x Coaching — 800,00€ HT
    → Total : 800,00€ HT
    → Date : 22/03/2026
    Confirme avec OK ou corrige-moi ✏️"
👤 "OK"
🤖 "Facture F-2026-03-012 créée ✓
    📄 PDF en pièce jointe.
    Je l'envoie par mail à Jean ?"
```

The user can configure confirmation off in their settings (per-user preference). When confirmation is off, Mona creates immediately and the user can correct after the fact.

### 2.4 Draft Mutability

**Drafts are freely editable and deletable at any time.** A `DRAFT` invoice has not been issued — it's a work in progress. The user can update any field or delete it entirely, and the invoice number is freed for reuse.

```
👤 "OK"
🤖 "Facture F-2026-03-012 créée ✓ 📄 PDF en pièce jointe."
👤 "Annule"
🤖 "Annulé ✓ La facture n'a pas été créée."
```

```
👤 "Change le montant à 900€"
🤖 "Mis à jour ✓ Facture F-2026-03-012 — 900€ HT, coaching, Dupont.
    📄 Nouveau PDF en pièce jointe."
```

**Cancellation (number preserved, status `CANCELLED`) only applies to `SENT` or `PAID` invoices** — these have been legally issued and cannot be modified or deleted. Corrections to sent/paid invoices go through the credit note flow (see section 2.9).

### 2.5 Duplicate Detection

When a user requests an invoice with the same client + same amount within the **last 48 hours** as an existing invoice, Mona warns before creating:

```
👤 "Facture 800€ pour Dupont, coaching"
🤖 "Tu m'as déjà demandé une facture de 800€ pour Dupont aujourd'hui
    (F-2026-03-012). C'est une deuxième facture ou un doublon ?"
👤 "Doublon"
🤖 "OK, je ne crée rien ✓"
```

### 2.6 Missing Information Handling

Core principle: **never block the flow**. Create with what you have, ask for the rest after.

**New client (no address on file):**
```
👤 "Facture 500€ pour un nouveau client, Pierre Moreau, développement"
🤖 "Facture F-2026-03-013 créée pour Pierre Moreau — 500€ HT ✓
    📄 PDF en pièce jointe.
    Au fait, j'ai pas l'adresse de Pierre — tu me la files pour que la facture soit complète ?"
👤 "12 rue de la Paix, 75002 Paris"
🤖 "Mis à jour ✓ J'ai regénéré le PDF avec l'adresse."
```

The invoice is usable immediately; the PDF is regenerated when missing info comes in.

**Any correction to a DRAFT invoice automatically regenerates and resends the PDF** — the user never needs to ask for it.

**Re-preview after edits:** When a user edits a draft and later asks to send it, Mona re-shows the PDF before sending so the user can verify the final version. If the invoice hasn't been modified since creation, Mona sends directly without re-previewing.

**Missing user address (first invoice ever):**
Mona asks for the user's address before generating the PDF, since it's legally mandatory on the invoice. This is the only hard block.

### 2.7 Invoice Numbering

Format: **`F-YYYY-MM-NNN`**

- `F` — fixed prefix
- `YYYY` — year
- `MM` — month
- `NNN` — sequential number within the month, zero-padded (starts at 001)

Examples: `F-2026-03-001`, `F-2026-04-012`

Numbering is **per-user** and **strictly sequential with no gaps** (French legal requirement). If an invoice is cancelled, it keeps its number and is marked as cancelled — the number is never reused or skipped.

### 2.8 PDF Generation

Text-based, professional, no logo in V1.

**Mandatory mentions on every invoice (French law):**
- Date of issue
- Invoice number (sequential, no gaps)
- Seller: full name, "EI" (Entreprise Individuelle), SIREN, address
- Buyer: name, address (if available), SIRET (if applicable)
- Line items table: description, quantity, unit price HT, line total HT
- Total HT and TTC
- `"TVA non applicable, article 293 B du CGI"` (for franchise en base de TVA)
- Payment terms and due date (based on user's `default_payment_delay_days` setting, default 30 days)
- Late payment penalties mention
- Payment method (if specified)
- Seller's IBAN (if provided) — displayed in a "Coordonnées bancaires" section

### 2.9 Credit Notes (Avoirs)

When an invoice has already been sent or paid, it cannot be modified (French legal requirement). To correct an error, Mona issues a **credit note** (avoir) that cancels the original invoice, then creates a new corrected invoice.

**Credit note numbering:** `A-YYYY-MM-NNN` — same sequential logic as invoices, separate sequence.

**UX principle: abstract the legal plumbing.** The user sees a "correction" — Mona generates the credit note and replacement invoice behind the scenes without exposing the avoir mechanism in conversation. The avoir details appear only in the PDF documents.

```
👤 "La facture de Dupont c'était 900€ pas 800€"
🤖 "La facture F-2026-03-008 est déjà envoyée — je corrige : 900€ au lieu de 800€.
    On y va ?"
👤 "Oui"
🤖 "Corrigé ✓ Nouvelle facture F-2026-03-014 — 900€ HT, coaching, Dupont.
    📄 Les documents en pièce jointe (facture corrigée + avoir d'annulation).
    Je les envoie à Dupont ?"
```

**If the invoice is still `DRAFT`**, Mona modifies it directly — no credit note needed.

**Credit note PDF** includes:
- Reference to the original invoice being cancelled
- All mandatory invoice mentions
- Negative amount matching the original invoice
- Reason for credit (optional, added if user provides one)

**The original invoice status changes to `CANCELLED`** when a credit note is issued against it.

---

## 3. Client Directory

### Auto-Population

Clients are automatically added to the directory when first mentioned in an invoice. Mona enriches the client record over time as more info becomes available.

**Client record:** name (required), email, address, company name, SIRET (all optional, collected progressively).

**V1 scope:** V1 supports **individual clients only** (no mandatory address requirement). B2B client support (with mandatory address for legal compliance) is a V2 addition — the data model already captures `address`, `company_name`, and `siret` fields to support this.

### Disambiguation

When a user mentions a client name that matches multiple records:

```
👤 "Facture 300€ pour Dupont, design"
🤖 "J'ai deux Dupont — Jean (coaching) ou Marie (design) ?"
👤 "Marie"
🤖 "Je crée la facture pour Marie Dupont — 300€ HT, design. OK ?"
```

Mona uses the client's name + last service description as disambiguation context.

### Fuzzy Matching

Mona handles common variations:
- "Dupont" matches "Jean Dupont"
- "jean dupont" matches "Jean Dupont"
- Minor typos are handled gracefully by the LLM layer

### Client List

```
👤 "Mes clients"
🤖 "Tu as 4 clients :
    → Jean Dupont — 3 factures (2 400€ total)
    → Marie Martin — 1 facture (500€)
    → Pierre Moreau — 2 factures (1 800€)
    → Sophie Bernard — 1 facture (300€)"
```

### Client Invoice History

```
👤 "Historique avec Dupont"
🤖 "Jean Dupont — 3 factures :
    → F-2026-01-003 — 800€ — coaching — PAID ✓
    → F-2026-02-007 — 800€ — coaching — PAID ✓
    → F-2026-03-012 — 800€ — coaching — SENT (échéance dans 5 jours)"
```

Full client directory management (merge duplicates, delete clients, bulk edit) is V2.

---

## 4. Email Sending

### Flow

After invoice creation, Mona offers to send it by email:

```
🤖 "Facture F-2026-03-012 créée ✓ 📄 PDF en pièce jointe.
    Je l'envoie par mail à Jean ?"
👤 "Oui, jean.dupont@gmail.com"
🤖 "Envoyé à jean.dupont@gmail.com ✓"
```

If the client's email is already on file:
```
🤖 "Je l'envoie à jean.dupont@gmail.com ?"
👤 "Oui"
🤖 "Envoyé ✓"
```

Email contains a short professional message in French + PDF attachment. Invoice status changes to `SENT` after successful delivery.

### Email Bounce Handling

When an email delivery fails, Mona notifies the user:

```
🤖 "L'email à jean.dupont@gmail.com a échoué — l'adresse est peut-être incorrecte.
    Tu veux me donner une autre adresse pour Jean ?"
👤 "j.dupont@gmail.com"
🤖 "Mis à jour ✓ Renvoyé à j.dupont@gmail.com ✓"
```

The invoice status remains `DRAFT` (not `SENT`) until email delivery is confirmed.

---

## 5. Payment Tracking

### Marking as Paid

```
👤 "Dupont m'a payé"
🤖 "La facture F-2026-03-008 de Dupont (800€) ? Par virement, chèque ou espèces ?"
👤 "Virement"
🤖 "Noté ✓ Facture F-2026-03-008 marquée comme payée par virement."
```

If multiple invoices exist for the same client:
```
🤖 "Dupont a 2 factures en cours :
    → F-2026-03-008 — 800€ (coaching)
    → F-2026-03-010 — 400€ (formation)
    C'est laquelle ?"
```

### Invoice Statuses

| Status | Description |
|--------|-------------|
| `DRAFT` | Created, not yet sent |
| `SENT` | Emailed to client |
| `PAID` | Payment received (with payment method + date recorded) |
| `OVERDUE` | Past due date, not paid |
| `CANCELLED` | Cancelled (number preserved, not reused) |

Status transitions:
- `DRAFT` → `SENT` (on email send)
- `DRAFT` → `PAID` (direct payment without email)
- `DRAFT` → *(deleted)* (user deletes — number is freed, no trace)
- `SENT` → `PAID` (payment received)
- `SENT` → `OVERDUE` (automatic, checked daily)
- `SENT` → `CANCELLED` (user cancels — number preserved)
- `OVERDUE` → `PAID` (late payment received)
- `OVERDUE` → `CANCELLED` (user cancels — number preserved)
- `PAID` → `CANCELLED` (via credit note only)

### Proactive Payment Check-Ins

**Always on.** The day after an invoice's due date (D+1), Mona proactively asks the user if they've been paid. When multiple invoices are due on the same day, Mona batches them into a single message.

**Single invoice:**
```
🤖 "La facture F-2026-03-008 de Dupont (800€) devait être payée hier — c'est fait ?"
👤 "Oui, par virement"
🤖 "Noté ✓ Facture marquée comme payée par virement."
```

**Multiple invoices (batched):**
```
🤖 "3 factures arrivaient à échéance hier :
    → Dupont — 800€ (F-2026-03-008)
    → Martin — 600€ (F-2026-03-010)
    → Bernard — 300€ (F-2026-03-012)
    Lesquels t'ont payé ?"
👤 "Dupont et Bernard, par virement"
🤖 "Noté ✓ Dupont et Bernard marquées comme payées par virement.
    Et Martin ? Tu veux que je le relance ?"
```

If not paid:
```
👤 "Non"
🤖 "OK, je les garde en attente. Tu veux que je relance quelqu'un ?"
```

**One check-in per invoice, no nagging.** If the user doesn't respond, the invoice transitions to `OVERDUE` after 3 days past due date.

### Proactive Overdue Notifications

When an invoice transitions to `OVERDUE`, Mona notifies the user once:

```
🤖 "La facture F-2026-03-008 de Dupont (800€) devait être payée il y a 3 jours.
    Tu veux que je le relance ?"
```

---

## 6. Revenue Tracking

### Instant Queries

```
👤 "J'en suis où ce mois ?"
🤖 "Ce mois : 3 200€ encaissés sur 4 factures.
    2 factures en attente (1 400€).
    Tu es à 68% de ton CA du mois dernier 👍"
```

```
👤 "Qui me doit du fric ?"
🤖 "2 factures impayées :
    → Dupont — 800€ — échue depuis 12 jours
    → Martin — 600€ — échéance dans 3 jours
    Tu veux que je prépare un mail de relance ?"
```

### Supported Queries

- Revenue this month / this quarter / this year
- Comparison to previous period
- Outstanding (unpaid) invoices
- List of paid invoices for a period

### Livre des Recettes (Revenue Ledger)

The data model captures all fields required for the legal ledger: date of payment, client name, invoice reference, amount, payment method. Data is stored from V1. **PDF export of the ledger is V2.** Basic CSV export is available in V1 (see section 8).

---

## 7. URSSAF Reminders

### Setup

Activated when the user provides their `declaration_periodicity` (collected during progressive onboarding). The `activity_type` is auto-filled from the SIRENE API during onboarding.

**Activity types** (stored for URSSAF declaration breakdowns):
- `BIC_VENTE` — Commercial activity, sales of goods
- `BIC_SERVICE` — Commercial activity, services
- `BNC` — Liberal profession

**Declaration deadlines:**
- Monthly: end of the following month
- Quarterly: Jan 31, Apr 30, Jul 31, Oct 31

### Reminder Schedule

**Two reminders per declaration period:**

1. **D-7** (7 days before deadline): planning reminder with pre-calculated amounts
2. **D-1** (day before deadline): last-call reminder

If the user acknowledges the D-7 reminder (replies OK, marks as done, or declares), the D-1 reminder is **skipped**.

### Reminder Content

Mona shows **revenue to declare only** — no contribution estimates. Contribution rates depend on too many edge cases (ACRE, mixed activities, CFP variations) to calculate reliably in V1.

**Calculation basis: encaissé (cash basis).** Auto-entrepreneurs declare on cash received, not invoiced. URSSAF amounts are computed from invoices with a `paid_date` falling within the declaration period, not from `issue_date`. This is a legal requirement — getting this wrong means a wrong tax declaration.

**For users with a single activity type:**
```
🤖 "Hey Sophie ! Ta déclaration URSSAF est dans 7 jours (31 mars).
    CA à déclarer ce trimestre : 4 800€
    Réponds OK et je te file le lien direct ✓"
👤 "OK"
🤖 "Voici le lien : https://autoentrepreneur.urssaf.fr
    Montant à saisir : 4 800€. Pense à télécharger le justificatif ✓"
```

**For users with mixed activities:**
```
🤖 "Hey Sophie ! Ta déclaration URSSAF est dans 7 jours (31 mars).
    CA à déclarer ce trimestre :
    → Prestations de services (BNC) : 3 200€
    → Vente de marchandises (BIC) : 1 600€
    Réponds OK et je te file le lien direct ✓"
```

### VAT Threshold Alerts

Mona alerts when cumulative annual revenue approaches franchise en base limits:

| Activity | Base Threshold | Increased Threshold |
|----------|---------------|-------------------|
| Services | 36 800€ | 39 100€ |
| Sales | 91 900€ | 101 000€ |

Alert triggered at **80%** and **95%** of the base threshold.

---

## 8. CSV Export

Users can export all their invoices as a CSV file sent directly in chat.

```
👤 "Exporte mes factures"
🤖 "Voici l'export de tes 23 factures ✓
    📎 mona-factures-2026-03-22.csv"
```

**CSV columns:** Invoice number, status, issue date, due date, paid date, client name, line items (description, quantity, unit price HT, line total HT), invoice total HT, total TTC, payment method.

No filtering in V1 — full export only. Filename format: `mona-factures-YYYY-MM-DD.csv`.

---

## 9. Conversation Design

### Context Management

Mona relies on **Claude + the last 2-3 conversation messages** for context resolution. No separate "last-action pointer" — the LLM resolves references like `"envoie-la à Jean"` or `"en fait c'est 900€"` directly from conversation history.

This avoids a second source of truth that can disagree with the conversation. When Claude's confidence in resolving a reference is low (e.g., ambiguous or too far from the original context), it asks for clarification instead of guessing.

The last 2-3 messages are persisted and survive server restarts.

### Structured Actions

The LLM parses messages into one of these action types:

| Action | Description |
|--------|-------------|
| `create_invoice` | Create a new invoice (with one or more line items) |
| `send_invoice` | Email an invoice to a client |
| `mark_paid` | Mark an invoice as paid |
| `update_draft` | Update any field on a draft invoice |
| `delete_draft` | Delete a draft invoice — number is freed |
| `cancel_invoice` | Cancel a sent/paid invoice (number preserved) |
| `correct_invoice` | Correct a sent/paid invoice — credit note + replacement |
| `get_revenue` | Query revenue for a period |
| `export_invoices` | Export all invoices as CSV |
| `get_unpaid` | List outstanding invoices |
| `update_client` | Add/update client information |
| `update_profile` | Update user profile information |
| `configure_setting` | Change user preferences |
| `list_clients` | List all clients with summary |
| `client_history` | Show invoice history for a specific client |
| `conversational` | Non-transactional message — greetings, thanks, small talk |
| `unknown` | Could not parse — ask user to rephrase |

### Conversational Messages

Mona handles non-transactional messages gracefully. Greetings, thanks, expressions of frustration or stress are acknowledged warmly and briefly.

```
👤 "Merci Mona !"
🤖 "De rien Sophie 😊"

👤 "Bonjour"
🤖 "Salut Sophie ! Qu'est-ce que je peux faire pour toi ?"

👤 "Je suis stressée par mes impôts"
🤖 "Courage ! Au moins côté facturation t'es carrée 💪
    Tu veux voir où t'en es ce trimestre ?"
```

Mona stays brief, redirects gently toward what she can help with, and never pretends to be a general-purpose assistant.

### Unparseable Messages

```
🤖 "Hmm, j'ai pas compris 😅 Tu peux reformuler ?
    Je sais gérer : factures, suivi de CA, relances, déclarations URSSAF."
```

No buttons, no menus — stays conversational.

### Persistent Menu

A **persistent keyboard/menu** is displayed at the bottom of the chat with common actions:

- **Nouvelle facture** — start invoice creation
- **Mes impayés** — list outstanding invoices
- **Mon CA** — revenue summary for the current month

The menu is a convenience shortcut — all actions remain accessible via natural language.

---

## 10. Error Handling (User-Facing)

When any external dependency fails, Mona:
1. **Acknowledges the failure** in plain French — no cryptic error messages
2. **Notifies the user** when the issue is resolved

| Situation | User sees |
|-----------|----------|
| LLM unavailable | *"Je suis momentanément indisponible — réessaie dans quelques minutes 🔄"* |
| SIRENE API down | *"Je n'arrive pas à vérifier ton SIREN — tu peux me donner ton nom, adresse et type d'activité ?"* (manual fallback) |
| Email delivery failed | *"L'envoi a échoué, je réessaie automatiquement."* |
| PDF generation failed | *"J'ai un souci pour générer le PDF — je réessaie."* |

No structured fallback (command syntax) in V1. The conversational-only UX is a deliberate product choice.

---

## 11. User Settings

Configurable per user via chat (e.g., `"Mona, désactive la confirmation"`):

| Setting | Default | Description |
|---------|---------|-------------|
| `confirm_before_create` | `true` | Ask for confirmation before creating invoices |
| `default_payment_delay_days` | `30` | Default payment terms on new invoices |

### Rate Limiting

**200 messages per user per day.** If exceeded, Mona responds: *"Tu as atteint la limite de messages pour aujourd'hui — on se retrouve demain !"* Counter resets at midnight UTC.

All features are unlocked in V1. Feature gating will be introduced in V2 based on observed usage patterns.

---

## 12. GDPR Compliance

### Privacy Policy

Published on a static page. Covers:
- What data is collected and why (invoicing, payment tracking, URSSAF reminders)
- Legal basis: contract performance (Art. 6(1)(b) GDPR)
- Data retention: invoices and financial data kept 10 years (French legal requirement), account data deleted on request
- Third-party processors: Anthropic (Claude API), Resend (email), hosting provider
- User rights: access, rectification, deletion, portability

### Right to Deletion

Users can request deletion via chat. Deleting an account:
- Permanently removes profile data, client records, and messaging link
- **Retains anonymized invoice records** for the 10-year legal retention period
- Unlinks the Telegram ID

### Data Export

Users can export all their data via chat:
- Full CSV of all invoices
- All invoice and credit note PDFs (sent as files in chat)
- Profile data as JSON

---

## 13. Out of Scope (V1)

V1 validates the core loop with one user. Everything below is deferred.

**V2 — Expand & grow (after V1 validation):**
- Web dashboard (user-facing read-only + admin panel)
- Platform account with email/password auth
- Voice message support (Whisper transcription)
- Automated payment follow-up / dunning (3-step escalation emails to clients)
- Monthly and weekly revenue digests
- Recurring invoice suggestions (pattern detection)
- Milestone celebrations (revenue milestones, invoice count)
- Inactivity nudges
- Freemium paywall (boundaries informed by V1 usage data)
- Referral system
- Quotes (devis) and devis→invoice conversion
- EPC QR code on invoice PDFs
- Self-adapting confirmation (suggest turning off after ~10 invoices)
- Batch invoicing
- Expense tracking
- Proactive financial insights
- Per-client payment terms override
- Partial payments
- Livre des recettes PDF export
- Invoice logo/customization
- Client directory full management (merge duplicates, delete, bulk edit)
- Recurring invoices (full infrastructure)
- WhatsApp channel (activated when revenue covers API cost)
- CSV export filtering (by date, client, status)
- Revenue goals and monthly targets

**Phase 3:**
- URSSAF API integration
- Bank sync
- Factur-X e-invoicing
- Full TVA management
- Multi-user teams

**Long-term:**
- Multi-currency invoicing
- Accountant sharing
- Photo/document input (business card → client info extraction)

**Not planned:**
- General AE admin Q&A (conversational)

---

## 14. Cost Projections

### Per-User Monthly Cost

| Item | Cost |
|------|------|
| Claude Sonnet (~50 messages) | ~€0.15 |
| Hosting (amortized) | ~€0.05–0.15 |
| Backup storage | ~€0.00 (free tier) |
| Email (~20 emails) | ~€0.05 |
| **Total** | **~€0.25–0.35** |

### Pricing Model

**V1: All features unlocked, no paywall.** V1 usage data informs V2 paywall boundaries.

**V2: Freemium.** Planned positioning:
- **Free tier:** Core invoicing, PDF generation, email sending, client directory, payment tracking, CSV export
- **Premium tier (~€7–15/month) — "Get paid faster":** Automated dunning, URSSAF reminders, weekly digest, EPC QR codes, quotes. Price exploration range informed by V1 learnings.

Rationale: free invoicing + CSV export keep the acquisition funnel open. Marginal cost per free user is ~€0.25/month — sustainable at scale. Premium is positioned around clear monetary value: users get paid faster.
