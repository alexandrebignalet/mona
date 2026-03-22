# Mona MVP — Product Specification

## Overview

Mona is a conversational assistant that lets French auto-entrepreneurs manage invoices and URSSAF declarations entirely through chat. Users send natural language messages (text or voice) in French; the bot parses them via Claude Sonnet, executes structured actions, and responds conversationally.

Multi-user from day one. French-speaking UX. Spec written in English for development and LLM prompt engineering purposes.

### Channel Strategy

**V1 launches on Telegram** — the Telegram Bot API is free, which minimizes financial risk during validation. Telegram is the **validation channel**, not the long-term target channel. The French market lives on WhatsApp; Telegram skews tech-savvy and is not representative of the target audience.

**WhatsApp migration trigger:** WhatsApp Business API (~€50-100/month) is activated once Mona generates enough revenue to cover the cost. This is a revenue milestone, not a user count milestone.

The `MessagingPort` abstraction (see section 12) ensures the core product is channel-agnostic from day one.

---

## 1. User Onboarding

### Progressive Flow

Onboarding is spread across the user's first interactions to minimize upfront friction. The goal: first invoice created within 2 minutes of starting the bot. **Key principle: the user should reach the "aha moment" (seeing a PDF invoice) as fast as possible — administrative data is collected just-in-time, not upfront.**

| Step | Trigger | Data Collected | Blocking? |
|------|---------|---------------|-----------|
| 0 | User starts bot (`/start`) | `telegram_id` (auto) | No |
| 1 | Mona introduces herself and invites the user to create an invoice | Nothing — straight to value | No |
| 2 | User creates first invoice → Mona generates a **draft PDF** immediately (with "BROUILLON" watermark, no SIREN required) | Invoice data only | No — draft PDF is sent instantly |
| 3 | Mona asks for SIREN to **finalize** the invoice | `siren` → auto-fills `name`, `address`, `activity_type` via SIRENE API | Yes — required before invoice is finalized |
| 4 | User confirms auto-filled profile | User corrects any wrong fields or confirms | Yes — one message |
| 5 | Mona collects email for platform account | `email` — grabbed from Telegram profile if available, otherwise asked in chat | Yes — required for account creation |
| 6 | Mona sets up URSSAF reminders (collected later, in context) | `declaration_periodicity` (only field not in SIRENE) | Yes — required before reminders activate |

At each step, Mona asks naturally — never feels like a form.

### Platform Account Creation

Every user gets a **platform account** (independent of Telegram) during onboarding. This provides resilience if Telegram is unavailable and access to the web dashboard.

**Flow:**
1. After the first invoice is created and the profile is confirmed, Mona checks the user's Telegram profile for an email address
2. If an email is available, Mona uses it. If not, Mona asks: *"Pour sécuriser ton compte, j'ai besoin de ton email — c'est quoi ?"*
3. A **verification email** is sent with a link to set a password
4. The user can ignore the email until they need dashboard access — zero friction
5. The platform account is created immediately (email as identifier), password is set when the user clicks the verification link

**If the user never sets a password**, they can still use Mona normally via Telegram. The account exists and data is safe — they just can't access the web dashboard until they verify.

**Admin support:** The admin dashboard includes a "resend verification email" action for support cases.

### SIREN Verification (SIRENE API)

SIREN is collected **after the aha moment** — the user first sees a draft PDF with a "BROUILLON" watermark, proving the NL→invoice magic works. Then Mona asks for SIREN to finalize the invoice. On receiving the SIREN, Mona validates it against the **INSEE SIRENE API** (free, public) and auto-fills:
- Legal name, business address, activity type (from NAF/APE code mapping)

The draft PDF is legally valid as a draft — SIREN is only required on the finalized, issued invoice. This ensures the user experiences the core value before any administrative friction. If the API is unavailable, Mona falls back to manual collection.

**Hint for users who don't know their SIREN:** *"Tu le trouves sur autoentrepreneur.urssaf.fr ou ton certificat INSEE."*

**Deep links:** Partners or accountants can share `t.me/monabot?start=siren_123456789` links that pre-fill the SIREN, skipping the collection step entirely.

**Referral links:** Each user gets a unique referral link: `t.me/monabot?start=ref_USERID`. Referrals are tracked for analytics (referrer, referee, timestamp, activation status). The referral link is accessible via chat: *"Mona, mon lien de parrainage"*. Referral rewards are Phase 2 — V1 tracks data only.

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
🤖 "Facture F-2026-03-001 finalisée ✓
    📄 PDF définitif en pièce jointe.
    Je l'envoie par mail à Martin ?
    Au fait, ton email c'est sophie.martin@gmail.com ? Je t'envoie un lien
    pour sécuriser ton compte Mona."
👤 "Oui"
🤖 "Parfait ✓ Check tes mails pour activer ton espace Mona."
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

The bot accepts free-form French messages and extracts structured invoice data via Claude Sonnet.

**Supported input patterns:**
- `"Facture 800€ pour Dupont, coaching"`
- `"Facture 800 balles à Jean Dupont pour du coaching le 15 mars"`
- `"Fais une facture de 1200€ pour Martin, développement web, payé par virement"`
- `"Facture Dupont : 3 jours de dev à 500€ + formation 400€"` (multi-line)

**Parsed output (internal):**
```json
{
  "action": "create_invoice",
  "client_name": "Dupont",
  "line_items": [
    {
      "description": "Développement",
      "quantity": 3,
      "unit_price_ht": 50000
    },
    {
      "description": "Formation",
      "quantity": 1,
      "unit_price_ht": 40000
    }
  ],
  "date": "2026-03-15",
  "payment_method": null
}
```

All amounts are stored in **cents** internally. Currency is stored per invoice (default EUR, multi-currency feature in V2).

### 2.2 Multi-Line Invoices

Mona fully supports multi-line invoices. The LLM extracts multiple line items from natural language:

```
👤 "Facture Dupont : 3 jours de dev à 500€ + formation 400€"
🤖 "Je crée cette facture ?
    → Client : Jean Dupont
    → 3x Développement — 500,00€ = 1 500,00€ HT
    → 1x Formation — 400,00€ HT
    → Total : 1 900,00€ HT
    Confirme avec OK ou corrige-moi ✏️"
```

Single-item invoices are a special case of multi-line (one line item). The data model, PDF generation, and confirmation flow all handle N line items natively.

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

### 2.4 Duplicate Detection

When a user requests an invoice with the same client + same amount on the same day as an existing invoice, Mona warns before creating:

```
👤 "Facture 800€ pour Dupont, coaching"
🤖 "Tu m'as déjà demandé une facture de 800€ pour Dupont aujourd'hui
    (F-2026-03-012). C'est une deuxième facture ou un doublon ?"
👤 "Doublon"
🤖 "OK, je ne crée rien ✓"
```

### 2.5 Missing Information Handling

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

**Any correction to a DRAFT invoice automatically regenerates and resends the PDF** — the user never needs to ask for it. This keeps the latest PDF always in sync with the invoice data.

**Missing user address (first invoice ever):**
Mona asks for the user's address before generating the PDF, since it's legally mandatory on the invoice. This is the only hard block.

### 2.6 Invoice Numbering

Format: **`F-YYYY-MM-NNN`**

- `F` — fixed prefix
- `YYYY` — year
- `MM` — month
- `NNN` — sequential number within the month, zero-padded (starts at 001)

Examples: `F-2026-03-001`, `F-2026-04-012`

Numbering is **per-user** and **strictly sequential with no gaps** (French legal requirement). If an invoice is cancelled, it keeps its number and is marked as cancelled — the number is never reused or skipped.

### 2.7 PDF Generation

Text-based, professional, no logo in V1.

**Mandatory mentions on every invoice (French law):**
- Date of issue
- Invoice number (sequential, no gaps)
- Seller: full name, "EI" (Entreprise Individuelle), SIREN, address
- Buyer: name, address (if available), SIRET (if applicable)
- Line items table: description, quantity, unit price HT, line total HT
- Total HT and TTC
- `"TVA non applicable, article 293 B du CGI"` (for franchise en base de TVA)
- Payment terms and due date (30 days from issue date — see section 14 for V2 customization)
- Late payment penalties mention
- Payment method (if specified)

PDF is generated programmatically using **pdf-lib** (pure JavaScript, no native dependencies). The invoice layout includes a line items table, which pdf-lib handles well. This avoids the Chromium/Puppeteer dependency, resulting in smaller Docker images, faster cold starts, and reliable generation on constrained hosting (Fly.io). Puppeteer may be introduced in Phase 2 if logo/custom template support requires HTML→PDF rendering.

### 2.8 Credit Notes (Avoirs)

When an invoice has already been sent or paid, it cannot be modified (French legal requirement). To correct an error, Mona issues a **credit note** (avoir) that cancels the original invoice, then creates a new corrected invoice.

**Credit note numbering:** `A-YYYY-MM-NNN` — same sequential logic as invoices, separate sequence.

**Trigger:** User asks to correct a sent/paid invoice.

```
👤 "La facture de Dupont c'était 900€ pas 800€"
🤖 "La facture F-2026-03-008 est déjà envoyée — je dois faire un avoir pour l'annuler
    puis créer une nouvelle facture de 900€. On y va ?"
👤 "Oui"
🤖 "Avoir A-2026-03-001 créé ✓ (annule F-2026-03-008)
    Nouvelle facture F-2026-03-014 — 900€ HT, coaching, Dupont ✓
    📄 Les deux PDF en pièce jointe.
    Je les envoie à Dupont ?"
```

**If the invoice is still `DRAFT`**, Mona modifies it directly — no credit note needed.

**Credit note PDF** includes:
- Reference to the original invoice being cancelled
- All mandatory invoice mentions
- Negative amount matching the original invoice
- Reason for credit (optional, added if user provides one)

**The original invoice status changes to `CANCELLED`** when a credit note is issued against it.

### 2.9 Recurring Invoice Suggestions

Mona detects monthly billing patterns and proactively suggests repeating invoices. This saves freelancers who bill the same clients monthly from manually dictating the same invoice every time.

**Trigger:** When a user has invoiced the same client for the same amount for **2 consecutive months**, Mona proactively sends a suggestion on the approximate date the next invoice would be expected (based on the previous issue dates).

```
🤖 "Salut Sophie ! Les 2 derniers mois tu as facturé 800€ à Dupont pour du coaching.
    Je refais la même pour ce mois ?"
👤 "Oui"
🤖 "Facture F-2026-03-015 créée ✓
    📄 PDF en pièce jointe."
```

If the user declines or doesn't respond, Mona does not suggest again until the next month. The user can also adjust: *"Oui mais 900€ cette fois"*.

**Detection logic:**
- Same client + same total amount (±5% tolerance) + 2+ consecutive months
- Suggestion sent on the same day-of-month as the previous invoice (or the 1st of the month if dates vary)
- One suggestion per client per month — no nagging

---

## 3. Client Directory

### Auto-Population

Clients are automatically added to the directory when first mentioned in an invoice. Mona enriches the client record over time as more info becomes available.

**Client record:**
- `name` (required — extracted from invoice request)
- `email` (collected when user wants to send invoice by email)
- `address` (collected when provided or asked for)
- `company_name` (optional)
- `siret` (optional)

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

Mona should handle common variations:
- "Dupont" matches "Jean Dupont"
- "jean dupont" matches "Jean Dupont"
- Minor typos should be handled gracefully by the LLM layer

### Client List

Users can list all their clients:

```
👤 "Mes clients"
🤖 "Tu as 4 clients :
    → Jean Dupont — 3 factures (2 400€ total)
    → Marie Martin — 1 facture (500€)
    → Pierre Moreau — 2 factures (1 800€)
    → Sophie Bernard — 1 facture (300€)"
```

### Client Invoice History

Users can query a specific client's history:

```
👤 "Historique avec Dupont"
🤖 "Jean Dupont — 3 factures :
    → F-2026-01-003 — 800€ — coaching — PAID ✓
    → F-2026-02-007 — 800€ — coaching — PAID ✓
    → F-2026-03-012 — 800€ — coaching — SENT (échéance dans 5 jours)"
```

Full client directory management (merge duplicates, delete clients, bulk edit) is Phase 2.

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

### Implementation

- Email service: **Resend** (free tier: 100 emails/day)
- Email contains: short professional message in French + PDF attachment
- Sender: configurable "from" address (e.g., `factures@mona-app.fr`)
- Invoice status changes to `SENT` after successful email delivery

---

## 5. Revenue Tracking

### Instant Queries

Users can ask about their revenue at any time:

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

### Monthly Revenue Digest (Free)

**Free feature. Opt-in.** Mona sends a monthly summary (1st of the month) with:
- Revenue collected last month
- Number of invoices sent / paid
- Outstanding invoices and total pending amount

```
🤖 "Récap de mars 📊
    → 3 200€ encaissés (4 factures)
    → 2 factures en attente (800€)
    Bon mois Sophie !"
```

The monthly digest doubles as a **retention lever** — it brings inactive users back into the conversation. Activated via chat: `"Mona, envoie-moi un récap chaque mois"`.

### Weekly Revenue Digest (Premium)

**Premium feature. Opt-in.** Mona sends a weekly summary (Monday morning) with:
- Revenue collected this week
- Number of invoices sent / paid
- Outstanding invoices and total pending amount
- Comparison to the same week last month

```
🤖 "Récap de ta semaine 📊
    → 1 200€ encaissés (2 factures)
    → 2 factures en attente (800€)
    → +15% vs la même semaine le mois dernier
    Bonne semaine Sophie !"
```

The user activates via chat: `"Mona, envoie-moi un récap chaque semaine"` and deactivates the same way. If the user is on the free tier, Mona explains this is a premium feature.

### Livre des Recettes (Revenue Ledger)

The data model captures all fields required for the legal ledger:
- Date of payment
- Client name
- Invoice reference
- Amount
- Payment method

Data is stored from V1. **PDF export of the ledger is Phase 2.** Basic CSV export is available in V1 (see section 9).

---

## 6. Payment Tracking

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
- `SENT` → `PAID` (payment received)
- `SENT` → `OVERDUE` (automatic, checked daily by cron job)
- `OVERDUE` → `PAID` (late payment received)
- Any → `CANCELLED` (user cancels)

### Proactive Payment Check-Ins

**Always on, not configurable.** The day after an invoice's due date (D+1), Mona proactively asks the user if they've been paid. **When multiple invoices are due on the same day, Mona batches them into a single message** to avoid spamming the user.

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

Mona parses natural language responses to batch check-ins — partial answers like *"Dupont oui, pas les autres"* are handled gracefully.

If not paid:
```
👤 "Non"
🤖 "OK, je les garde en attente. Tu veux que je relance quelqu'un ?"
```

This reduces the friction of manual payment tracking — instead of the user remembering to tell Mona, Mona asks. **One check-in per invoice, no nagging.** If the user doesn't respond, the invoice transitions to `OVERDUE` after 3 days past due date (checked daily by cron).

### Proactive Overdue Notifications

When an invoice transitions to `OVERDUE`, Mona notifies the user:

```
🤖 "La facture F-2026-03-008 de Dupont (800€) devait être payée il y a 3 jours.
    Tu veux que je le relance ?"
```

The overdue check runs daily via cron. Mona notifies once per invoice when it first becomes overdue — no repeated notifications for the same invoice.

### Payment Follow-Up / Dunning (Premium)

**Premium feature.** Automated payment reminders sent directly to clients. **Global opt-in setting** — disabled by default, the user must explicitly activate it.

**Default schedule (3-step escalation):**

| Step | Timing | Tone |
|------|--------|------|
| Relance 1 | D+7 after due date | Gentle — friendly reminder |
| Relance 2 | D+15 after due date | Firmer — formal reminder |
| Relance 3 | D+30 after due date | Formal — last notice before further action |

**Transparency principle:** Mona notifies the user before each send:

```
🤖 "Je relance Dupont demain pour la facture de 800€ (échue depuis 7 jours) — OK ?"
👤 "OK"
🤖 "Relance envoyée à jean.dupont@gmail.com ✓"
```

The user can stop the sequence at any time for any invoice:
```
👤 "Arrête les relances pour Dupont"
🤖 "Relances stoppées pour la facture F-2026-03-008 ✓"
```

If the user doesn't respond to the notification, the reminder is **not sent** — Mona never emails a client without the user's knowledge.

When an invoice is marked as paid, the dunning sequence stops automatically.

---

## 7. URSSAF Reminders (Premium)

**Premium feature.**

### Setup

Activated when the user provides their `declaration_periodicity` (collected during progressive onboarding, Step 5). The `activity_type` is auto-filled from the SIRENE API during onboarding.

**Activity types** (stored for future contribution estimate features, not used for calculations in V1):
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

Mona shows **revenue to declare only** — no contribution estimates. Contribution rates depend on too many edge cases (ACRE, mixed activities, CFP variations) to calculate reliably in V1. The user verifies and declares on the URSSAF website.

**For users with a single activity type:**
```
🤖 "Hey Sophie ! Ta déclaration URSSAF est dans 7 jours (31 mars).
    CA à déclarer ce trimestre : 4 800€
    Réponds OK et je te file le lien direct ✓"
👤 "OK"
🤖 "Voici le lien : https://autoentrepreneur.urssaf.fr
    Montant à saisir : 4 800€. Pense à télécharger le justificatif ✓"
```

**For users with mixed activities** (activity type stored per invoice, see data model):
```
🤖 "Hey Sophie ! Ta déclaration URSSAF est dans 7 jours (31 mars).
    CA à déclarer ce trimestre :
    → Prestations de services (BNC) : 3 200€
    → Vente de marchandises (BIC) : 1 600€
    Réponds OK et je te file le lien direct ✓"
```

Mixed-activity breakdowns are derived from the `activity_type` field on each invoice. URSSAF requires separate declaration per activity category.

### VAT Threshold Alerts

Mona alerts when cumulative annual revenue approaches franchise en base limits:

| Activity | Base Threshold | Increased Threshold |
|----------|---------------|-------------------|
| Services | 36 800€ | 39 100€ |
| Sales | 91 900€ | 101 000€ |

Alert triggered at **80%** and **95%** of the base threshold.

---

## 8. Conversation & LLM Design

### Context Management

Mona uses a **persistent last-action pointer** stored in SQLite for each user. No session timeout — the pointer persists indefinitely:
- Last created/modified invoice
- Last mentioned client
- Last action type

This allows references like `"envoie-la à Jean"` or `"en fait c'est 900€"` to work even hours after the invoice was created, as long as there's no ambiguity. Cost: ~20 extra tokens per request (compact JSON in the user context payload).

If the reference is ambiguous (e.g., multiple invoices created since), Mona asks for clarification instead of guessing.

**Best-effort conversational context:** The last 2-3 messages are kept in an in-memory map for richer conversational flow within a short window. If the server restarts, this context is lost — the last-action pointer in SQLite ensures core references still work.

### Token Optimization

To keep API costs low (~€0.15/user/month at 50 messages):

- **Do not send full conversation history** to Claude
- Send to Claude on each request:
  - System prompt (static, ~300 tokens)
  - Compact user context: name, SIREN, recent invoice (structured JSON, ~100 tokens)
  - Last 2-3 messages only (~200 tokens)
- Conversational context lives in the **application layer** (in-memory map), not in the LLM context
- Target per-request: ~500-800 tokens in, ~200 tokens out

### Structured Actions

The LLM parses messages into one of these action types:

| Action | Description |
|--------|-------------|
| `create_invoice` | Create a new invoice (with one or more line items) |
| `send_invoice` | Email an invoice to a client |
| `mark_paid` | Mark an invoice as paid |
| `cancel_invoice` | Cancel an invoice |
| `correct_invoice` | Issue credit note + new corrected invoice (for sent/paid invoices) |
| `get_revenue` | Query revenue for a period |
| `export_invoices` | Export all invoices as CSV |
| `get_unpaid` | List outstanding invoices |
| `update_client` | Add/update client information |
| `update_profile` | Update user profile information |
| `configure_setting` | Change user preferences (e.g., confirmation on/off) |
| `list_clients` | List all clients with summary |
| `client_history` | Show invoice history for a specific client |
| `conversational` | Non-transactional message — greetings, thanks, small talk, emotional expression |
| `unknown` | Could not parse — ask user to rephrase |

### Conversational Messages

Mona handles non-transactional messages gracefully instead of forcing every input into a business action. Greetings, thanks, expressions of frustration or stress are acknowledged warmly and briefly, staying in character.

```
👤 "Merci Mona !"
🤖 "De rien Sophie 😊"

👤 "Bonjour"
🤖 "Salut Sophie ! Qu'est-ce que je peux faire pour toi ?"

👤 "Je suis stressée par mes impôts"
🤖 "Courage ! Au moins côté facturation t'es carrée 💪
    Tu veux voir où t'en es ce trimestre ?"
```

Mona stays brief, redirects gently toward what she can help with, and never pretends to be a general-purpose assistant. If a message is truly unparseable (not conversational, not a business action), Mona falls back to the unknown handler.

### Unparseable Messages

When Mona can't understand a business-intent message:

```
🤖 "Hmm, j'ai pas compris 😅 Tu peux reformuler ?
    Je sais gérer : factures, suivi de CA, relances, déclarations URSSAF."
```

No buttons, no menus — stays conversational.

### Voice Message Support

Mona accepts **voice messages** as input — a key differentiator enabling hands-free invoice creation for freelancers on the go. The audio is transcribed then parsed by Claude like any text message.

**Voice is a free feature** — available to all users regardless of tier.

```
👤 🎤 "Facture cinq cents euros pour Martin, développement web"
🤖 "Je crée cette facture ?
    → Client : Martin
    → 1x Développement web — 500,00€ HT
    → Date : 22/03/2026
    Confirme avec OK ou corrige-moi ✏️"
```

**Implementation:** Telegram provides voice messages as `.ogg` files. The bot downloads the file and sends it to the **OpenAI Whisper API** for transcription, then feeds the transcript into the standard NL parsing pipeline. The transcription step is transparent to the user — Mona responds as if the user had typed the message.

**Cost optimization:** Whisper API costs ~€0.006/minute, which is negligible at early volumes. If transcription costs become significant at scale, migrate to self-hosted `whisper.cpp` (runs the small/medium model on CPU with solid French quality for short audio).

---

## 9. CSV Export

**Free feature.** Users can export all their invoices as a CSV file sent directly in the Telegram chat.

```
👤 "Exporte mes factures"
🤖 "Voici l'export de tes 23 factures ✓
    📎 mona-factures-2026-03-22.csv"
```

**CSV columns:**
- Invoice number, status, issue date, due date, paid date
- Client name
- Line items (description, quantity, unit price HT, line total HT)
- Invoice total HT, total TTC
- Payment method

No filtering in V1 — full export only. Filename format: `mona-factures-YYYY-MM-DD.csv`.

---

## 10. API Resilience

The Claude Sonnet API is the sole NL parsing layer. If it's unavailable, Mona cannot process messages.

**Strategy: queue + notify.**

When the Claude API returns an error or times out:
1. Mona queues the user's message in the application layer
2. Responds immediately: *"Je suis momentanément indisponible — je te réponds dès que possible 🔄"*
3. Retries queued messages with exponential backoff (max 5 minutes between retries)
4. Once the API is back, processes the queue in order and responds normally

**No structured fallback in V1.** No regex parsing, no command syntax — consistency of the conversational UX is more important than degraded partial functionality. This is a deliberate tradeoff: at early volumes, an outage is unlikely to affect many users simultaneously. **Revisit when user base grows** — a structured command fallback (e.g., `/facture 800 Dupont coaching`) may be warranted at scale.

**Queue limits:** max 10 messages per user, oldest dropped if exceeded. Queue TTL: 1 hour.

---

## 11. Data Model

### User

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `id` | uuid (PK) | Yes | Platform account ID |
| `telegram_id` | bigint (unique) | Yes | From Telegram API |
| `email` | string (unique) | No | Collected after first invoice, used for platform login |
| `password_hash` | string | No | Set when user clicks verification link |
| `email_verified` | boolean | Yes | Default `false` |
| `name` | string | No | Auto-filled from SIRENE, confirmed at onboarding. Required before PDF generation |
| `siren` | string(9) | No | Collected just-in-time before first PDF generation |
| `siret` | string(14) | No | Auto-filled from SIRENE if available |
| `address` | text | No | Auto-filled from SIRENE, confirmed at onboarding |
| `activity_type` | enum | No | `BIC_VENTE`, `BIC_SERVICE`, `BNC` — auto-filled from SIRENE (NAF/APE code) |
| `declaration_periodicity` | enum | No | `MONTHLY`, `QUARTERLY` — collected at Step 5 |
| `vat_status` | enum | Yes | Default `FRANCHISE` |
| `confirm_before_create` | boolean | Yes | Default `true` |
| `monthly_digest` | boolean | Yes | Default `false` |
| `auto_dunning` | boolean | Yes | Default `false` |
| `weekly_digest` | boolean | Yes | Default `false` |
| `is_premium` | boolean | Yes | Default `false`, toggled via admin dashboard |
| `premium_trial_used` | boolean | Yes | Default `false` — tracks whether the 7-day free trial has been consumed |
| `premium_trial_end` | timestamp | No | End date of active trial, if any |
| `created_at` | timestamp | Yes | Auto |

### Client

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `id` | uuid (PK) | Yes | Auto |
| `user_id` | FK → User | Yes | |
| `name` | string | Yes | Extracted from invoice request |
| `email` | string | No | Collected when sending email |
| `address` | text | No | Collected when provided |
| `company_name` | string | No | |
| `siret` | string(14) | No | |
| `created_at` | timestamp | Yes | Auto |

### Invoice

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `id` | uuid (PK) | Yes | Auto |
| `user_id` | FK → User | Yes | |
| `client_id` | FK → Client | Yes | |
| `invoice_number` | string | Yes | Format: `F-YYYY-MM-NNN`, per-user sequential |
| `status` | enum | Yes | `DRAFT`, `SENT`, `PAID`, `OVERDUE`, `CANCELLED` |
| `currency` | string(3) | Yes | Default `EUR`. Multi-currency feature in V2 |
| `vat_rate` | decimal | Yes | `0` for franchise en base |
| `issue_date` | date | Yes | |
| `due_date` | date | Yes | Default: issue_date + 30 days |
| `paid_date` | date | No | Set when marked as paid |
| `payment_method` | enum | No | `VIREMENT`, `CHEQUE`, `ESPECES`, `CARTE`, `AUTRE` |
| `activity_type` | enum | Yes | `BIC_VENTE`, `BIC_SERVICE`, `BNC` — defaults to user's activity type, overridable per invoice. Required for mixed-activity URSSAF declarations |
| `pdf_path` | string | No | Path to generated PDF |
| `created_at` | timestamp | Yes | Auto |

### InvoiceLineItem

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `id` | uuid (PK) | Yes | Auto |
| `invoice_id` | FK → Invoice | Yes | |
| `description` | text | Yes | |
| `quantity` | decimal | Yes | Default `1` |
| `unit_price_ht` | integer | Yes | In cents |
| `created_at` | timestamp | Yes | Auto |

**Computed fields** (not stored, derived at read time):
- `line_total_ht` = `quantity` × `unit_price_ht`
- Invoice `amount_ht` = sum of all line item `line_total_ht`
- Invoice `amount_ttc` = `amount_ht` × (1 + `vat_rate`)

### CreditNote

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `id` | uuid (PK) | Yes | Auto |
| `user_id` | FK → User | Yes | |
| `original_invoice_id` | FK → Invoice | Yes | The invoice being cancelled |
| `replacement_invoice_id` | FK → Invoice | No | The corrected invoice (if any) |
| `credit_note_number` | string | Yes | Format: `A-YYYY-MM-NNN`, per-user sequential |
| `amount_ht` | integer | Yes | In cents (negative, matching original) |
| `reason` | text | No | Optional reason for credit |
| `issue_date` | date | Yes | |
| `pdf_path` | string | No | Path to generated PDF |
| `created_at` | timestamp | Yes | Auto |

### Declaration

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `id` | uuid (PK) | Yes | Auto |
| `user_id` | FK → User | Yes | |
| `period_start` | date | Yes | |
| `period_end` | date | Yes | |
| `total_revenue` | integer | Yes | In cents, calculated from paid invoices |
| `status` | enum | Yes | `UPCOMING`, `REMINDED_D7`, `REMINDED_D1`, `DECLARED` |
| `due_date` | date | Yes | |
| `created_at` | timestamp | Yes | Auto |

### LastAction

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `user_id` | FK → User (PK) | Yes | One per user |
| `last_invoice_id` | FK → Invoice | No | Last created/modified invoice |
| `last_client_id` | FK → Client | No | Last mentioned client |
| `last_action_type` | string | No | Action type enum value |
| `updated_at` | timestamp | Yes | Auto |

---

## 12. Technical Stack

| Layer | Technology | Rationale |
|-------|-----------|-----------|
| Runtime | Node.js + TypeScript | Type safety, async-native |
| Bot Framework | grammY (behind MessagingPort) | Well-maintained, good TypeScript support |
| LLM | Claude Sonnet API | NL parsing → structured actions |
| Speech-to-Text | OpenAI Whisper API | Voice message transcription (migrate to self-hosted whisper.cpp at scale) |
| PDF Generation | pdf-lib | Pure JS, no native deps, fast on constrained hosting |
| Database | SQLite (via better-sqlite3) | Zero config, single-file, sufficient for V1 |
| Database Replication | Litestream → S3-compatible storage | Continuous backup to prevent data loss |
| Email | Resend | Simple API, free tier (100 emails/day) |
| Scheduling | node-cron | URSSAF deadline reminders + payment check-ins |
| Hosting | Fly.io | Simple deployment, persistent volumes for SQLite |
| Web Dashboard | Minimal web app (same Node.js server) | Read-only user access + admin panel |

### Data Safety & Backups

SQLite requires special care on cloud hosting:

- **Persistent volumes:** SQLite runs on a Fly.io persistent volume, not ephemeral storage. Data survives deploys and restarts.
- **Continuous replication:** **Litestream** streams WAL changes to S3-compatible storage (Tigris on Fly.io or Cloudflare R2 — both have free tiers). This provides point-in-time recovery.
- **Recovery:** If the volume is lost, the database is restored from the Litestream replica. Target RPO: < 1 minute.

Auto-entrepreneurs are legally required to keep invoices for **10 years**. Data integrity is non-negotiable.

### Encryption at Rest

The SQLite database contains sensitive personal and financial data (SIREN, email, addresses, invoice amounts). **The database file must be encrypted at rest** using SQLite encryption (e.g., SQLCipher or similar) or filesystem-level encryption on the Fly.io volume. This is a GDPR requirement ("appropriate technical measures" — Art. 32).

### Rate Limiting

Per-user message rate limits prevent abuse and cost overruns on the Claude API:

| Tier | Limit | Behavior when exceeded |
|------|-------|----------------------|
| Free | 100 messages/day | Mona responds: *"Tu as atteint la limite du jour — on se retrouve demain !"* |
| Premium | 200 messages/day | Same behavior |

Limits reset at midnight UTC. Rate limiting is enforced at the application layer before any API call.

### Availability & Recovery

Single Fly.io machine means single point of failure. Mitigation strategy:

- **Health check endpoint:** `/health` returns 200 when the bot, database, and Litestream are operational. Fly.io restarts the machine automatically on health check failure.
- **Recovery procedure:** If the machine is lost, Fly.io provisions a new one. Litestream restores the database from the latest replica (RPO < 1 minute). Documented runbook for manual recovery.
- **Target uptime:** 99.5% (allows ~3.6 hours/month downtime). Acceptable for V1 validation phase.
- **Monitoring:** Basic alerting (e.g., Fly.io built-in metrics or UptimeRobot) on bot availability, with notifications to the admin.

### LLM Parsing Quality

A **golden test suite** of French invoice requests ensures parsing quality does not regress across prompt iterations:

- Minimum 50 test cases covering: simple invoices, multi-line, voice transcription artifacts, slang ("800 balles"), ambiguous clients, corrections, edge cases
- Expected structured output for each test case
- Run as part of CI — parsing accuracy must stay above **95%** on the test suite before any prompt change is deployed
- Test suite grows over time as new failure modes are discovered in production

### Messaging Abstraction (MessagingPort)

To avoid Telegram lock-in and enable future channels (WhatsApp, etc.), all messaging goes through a **MessagingPort interface**. Telegram (via grammY) is the first adapter.

```typescript
interface MessagingPort {
  sendMessage(userId: string, text: string): Promise<void>;
  sendDocument(userId: string, file: Buffer, filename: string): Promise<void>;
  sendButtons(userId: string, text: string, buttons: Button[]): Promise<void>;
  onMessage(handler: (msg: IncomingMessage) => void): void;
  onVoice(handler: (voice: IncomingVoice) => void): void;
}

interface IncomingMessage {
  userId: string;
  text: string;
  timestamp: number;
}

interface IncomingVoice {
  userId: string;
  audioBuffer: Buffer;
  duration: number;
  timestamp: number;
}

interface Button {
  label: string;
  callbackData: string;
}
```

**Inline buttons** (e.g., Oui/Non for payment check-ins) are sent via `sendButtons` when the channel supports them. The bot **always accepts text and voice replies** as equivalent — buttons are a UX enhancement, never a requirement. If a channel adapter doesn't support buttons, it falls back to a text prompt.

Conversation state is stored independent of any channel's chat IDs — the `userId` is mapped per-adapter.

---

## 13. Web Dashboard

### User Dashboard (Read-Only)

A minimal web interface for data access and account management. **No invoice creation or actions** — those stay in the bot.

**Login:** Email + password (set via verification link during onboarding).

**Features:**
- View all invoices with status
- Download individual invoice PDFs
- Download full CSV export
- View and edit profile information (name, address, SIREN)
- Account settings (change password, change email)

**Not in V1:** Invoice creation, client management, payment actions — the bot is the primary interface.

### Admin Dashboard

Internal dashboard for operations and support.

**User Management:**
- User list with status (free / premium / inactive)
- View user details (profile, invoice count, last active)
- Toggle premium features per user (for beta testing, partnerships, support)
- Resend verification email
- Manual password reset

**Metrics Dashboard:**

| Metric | Definition | V1 Target |
|--------|-----------|-----------|
| Activation rate | % of signups who create first invoice in first session | > 60% |
| Week-1 retention | % of activated users who return in week 2 | > 40% |
| Month-1 retention | % of activated users active at day 30 | > 25% |
| Free → premium conversion | % of free users who upgrade (once premium is live) | > 5% |
| Core loop frequency | Invoices created per active user per month | > 3 |

Targets are initial benchmarks based on B2B SaaS and micro-entrepreneur tool norms. Adjust after first 3 months of real data.

**Revenue & MRR Tracking:**
- Total premium users
- MRR (monthly recurring revenue)
- MRR growth rate
- Churn rate (premium users who cancel)

**System Health:**
- Claude API uptime and response times
- Whisper API uptime and response times
- Queue depth and error rates
- Database size and replication status
- Failed email delivery rate

---

## 14. User Settings

Configurable per user via chat (e.g., `"Mona, désactive la confirmation"`):

| Setting | Default | Tier | Description |
|---------|---------|------|-------------|
| `confirm_before_create` | `true` | Free | Ask for confirmation before creating invoices |
| `monthly_digest` | `false` | Free | Receive a monthly revenue summary on the 1st of each month |
| `auto_dunning` | `false` | Premium | Enable automated payment follow-up emails to clients |
| `weekly_digest` | `false` | Premium | Receive a weekly revenue summary every Monday morning |

When a free user tries to activate a premium setting, Mona explains: *"C'est une fonctionnalité premium — tu veux en savoir plus ?"*

---

## 15. Re-engagement & Retention

### Milestone Celebrations

Mona celebrates revenue milestones to build emotional connection and reinforce the user's progress. These are **always on**, not configurable.

**Milestones:**
- First invoice created
- 1 000€, 5 000€, 10 000€, 25 000€, 50 000€ cumulative revenue
- 10th, 50th, 100th invoice created

```
🤖 "Tu viens de dépasser les 10 000€ de CA cette année — bravo Sophie ! 🎉"
```

Milestones are checked on each payment recording. Each milestone triggers once per user, never repeated.

### Inactivity Nudges

When a user has been inactive for **3+ weeks** and has **pending actions** (unpaid invoices, approaching URSSAF deadline, overdue invoices), Mona sends a single gentle nudge:

```
🤖 "Salut Sophie ! Au fait, la facture de Dupont (800€) est toujours en attente.
    Tu veux que je le relance ?"
```

**Rules:**
- Only triggered when there are actionable pending items — Mona never nudges users with nothing pending
- Maximum one nudge per inactivity period — no nagging
- If the user doesn't respond, no follow-up nudge is sent
- The user can opt out: *"Mona, arrête les rappels d'inactivité"*

---

## 16. GDPR Compliance

Mandatory for processing French users' personal and financial data.

### Privacy Policy

Published on the web dashboard. Covers:
- What data is collected and why (invoicing, payment tracking, URSSAF reminders)
- Legal basis: contract performance (Art. 6(1)(b) GDPR) for core features, legitimate interest for analytics
- Data retention: invoices and financial data kept 10 years (French legal requirement), account data deleted on request
- Third-party processors: Anthropic (Claude API), OpenAI (Whisper), Resend (email), Fly.io (hosting)
- User rights: access, rectification, deletion, portability

### Right to Deletion

Available in the **web dashboard only** (prevents accidental deletion via chat). Deleting an account:
- Permanently removes profile data, client records, and platform account
- **Retains anonymized invoice records** for the 10-year legal retention period (invoice data is stripped of personal identifiers but preserved for accounting compliance)
- Unlinks the Telegram ID

### Data Export

Users can export all their data from the web dashboard:
- Full CSV of all invoices
- All invoice and credit note PDFs as a ZIP archive
- Profile data as JSON

### Cookie Consent

The web dashboard uses a minimal cookie consent banner (session cookies for auth — no tracking cookies in V1).

---

## 17. Out of Scope (V1)

**Phase 2:**
- Configurable payment terms (per-user default + per-client override)
- Multi-currency invoicing (data model ready — `currency` field exists)
- Quotes (devis)
- Livre des recettes PDF export
- Invoice logo/customization
- Client directory full management (merge duplicates, delete, bulk edit)
- Recurring invoices (full infrastructure — V1 has smart suggestions only)
- WhatsApp channel (activated when revenue covers API cost)
- CSV export filtering (by date, client, status)
- URSSAF contribution estimates (ACRE, mixed activities)
- Revenue goals and monthly targets
- Referral rewards (tracking is V1, rewards are V2)
- Accountant sharing (read-only dashboard link or periodic email to accountant)
- Premium upgrade prompt at trial end + Stripe self-serve payment
- Annual pricing option

**Phase 3:**
- URSSAF API integration
- Bank sync
- Factur-X e-invoicing
- Full TVA management
- Multi-user teams
- Claude API structured fallback (command syntax for outages)

**Not planned:**
- General AE admin Q&A (conversational)

---

## 18. Cost Projections

### Per-User Monthly Cost

| Item | Cost |
|------|------|
| Claude Sonnet (~50 messages, ~800 tokens avg) | ~€0.15 |
| Whisper API (~10 voice messages, ~10 sec avg) | ~€0.01 |
| Hosting (Fly.io, amortized) | ~€0.05–0.15 |
| Litestream + S3 backup | ~€0.00 (free tier) |
| Email (Resend, ~20 emails) | ~€0.05 |
| **Total** | **~€0.25–0.35** |

### Pricing Model

**Freemium.**

**Free tier (forever):**
- Invoice creation (multi-line, voice, text)
- PDF generation and email sending
- Client directory
- Payment tracking (manual mark-as-paid)
- Proactive payment check-ins (D+1)
- Voice messages
- CSV export
- Monthly revenue digest

**Premium tier — "Get paid faster" (€7–9/month):**
- Automated dunning (3-step payment follow-up)
- URSSAF reminders with pre-calculated amounts
- Weekly revenue digest (with period-over-period comparison)

Premium is positioned around a clear monetary value proposition: **users get paid faster**. Dunning automation is the hero feature — it directly impacts cash flow.

**Free trial:** A 7-day premium trial is automatically triggered when a user's first invoice becomes overdue. This surfaces the premium value at the exact moment the user feels the pain. At the end of the trial, premium features deactivate silently — Mona mentions it once. In V1, premium is toggled manually via the admin dashboard (no self-serve payment flow). Stripe integration and upgrade prompts are Phase 2.

Rationale: free invoicing + CSV export + monthly digest keep the acquisition funnel open, build word-of-mouth, and drive retention. Marginal cost per free user is ~€0.25/month — sustainable at scale. Gating data export (CSV) was considered but rejected — it feels punitive and doesn't drive conversion. Premium features target users who get enough value from Mona to justify the cost.
