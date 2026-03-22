# Facture Bot — Project Brief

## TL;DR

Build a **Telegram bot** that lets French auto-entrepreneurs (micro-entrepreneurs) manage invoices, quotes, and URSSAF tax declarations **entirely through chat messages**. The bot uses an LLM (Claude Sonnet) to parse natural language into structured actions, generates compliant PDF invoices, and proactively reminds users of URSSAF deadlines.

The project starts as a side project to solve a real pain point for a friend who recently became an auto-entrepreneur, has a growing client base, and suffers from "admin phobia."

---

## Market Context

### The Auto-Entrepreneur Landscape in France

- **3.2 million** auto-entrepreneurs registered in France (mid-2025), ~1.6M economically active
- **~717,000** new micro-enterprise registrations per year (64.5% of all new businesses)
- Average quarterly revenue per active AE: ~€5,072
- **50% of registered AEs don't even declare revenue** — admin friction is a massive problem
- 45% of new registrations are in consulting/digital services
- Target demographic skews young: 60% of under-30s are engaged in entrepreneurial activity

### Competitive Landscape

The "invoicing + URSSAF declaration" space for AEs is already well-served:

| Tool | Users | Price | Key Differentiator |
|------|-------|-------|--------------------|
| **Abby** | 100k+ | Free / €11-19/mo | All-in-one, best UX, URSSAF partner |
| **Freebe** | Established | €9.90/mo | Reference tool, clean interface |
| **Superindep** | Growing | Paid | Focused on declaration automation + anomaly detection |
| **Indy** | Large | €12/mo | Bank account included, broader scope |
| **Mon-AutoEntreprise.fr** | Established | ~€20/mo | Official URSSAF partner since 2019 |
| **Henrri** | Large | Free | Most complete free invoicing tool |

All competitors are **web/mobile dashboard-based**. None offer a conversational-first interface.

### Key Regulatory Timeline

- **September 2026**: All French businesses must be able to **receive** e-invoices (Factur-X format)
- **September 2027**: All businesses (including micro-enterprises) must **emit** e-invoices
- This creates a wave of forced adoption — good timing for new entrants

---

## Product Concept: "Conversational Admin Assistant"

### Core Insight

For someone with admin phobia, even opening a dashboard is friction. The bot meets them where they already are (Telegram/WhatsApp) and eliminates every unnecessary step.

### User Experience Flow

```
👤 "Facture 800€ pour Jean Dupont, coaching business, payé par virement"
🤖 "Invoice #2026-017 created for Jean Dupont — €800 excl. tax,
    business coaching. PDF attached.
    Want me to email it to Jean?"
👤 "Yes, jean.dupont@gmail.com"
🤖 "Sent ✓. Your cumulative revenue this month: €3,200.
    Your URSSAF declaration is due in 12 days,
    you'll need to declare ~€4,800 (estimate)."

--- on the 28th of the month ---
🤖 "Hey! Your URSSAF declaration is due in 3 days.
    Revenue to declare: €4,800 → estimated contributions: €1,056.
    All good? Reply OK and I'll give you the direct link to declare."
👤 "OK"
🤖 "Here's the link: https://autoentrepreneur.urssaf.fr/...
    Amount to enter: €4,800. Don't forget to download the receipt ✓"
```

### Why Conversational > Dashboard

- **Zero friction**: No app install, no login, no navigation
- **Already-open channel**: Everyone has Telegram/WhatsApp
- **Proactive**: Bot comes to the user (reminders, alerts) instead of waiting
- **Speed**: Create an invoice in 10 seconds via message vs 2-3 minutes in a form
- **Natural for "admin phobic" users**: Feels like texting an assistant, not doing admin

---

## Channel Choice: Telegram First

| Criteria | Telegram | WhatsApp |
|----------|----------|----------|
| API cost | Free | Paid (~€0.04-0.08/conversation via Meta) |
| Native bot support | First-class (BotFather) | Requires Business API + BSP |
| File sending | Native, simple | More constrained |
| Setup time | 5 minutes | Weeks (Meta business verification) |
| France adoption | ~30% | ~80%+ |
| Side project friendly | ✅ Perfect | ❌ Too much overhead initially |

**Decision**: Start with Telegram for the MVP. Migrate to WhatsApp (or support both) in V2 once product-market fit is validated.

---

## MVP Feature Set (V1 — Target: 2-3 weekends)

### Core: Invoicing via Chat

- Create invoices from natural language messages
- Generate **legally compliant** PDF invoices with all mandatory French AE mentions:
  - SIREN/SIRET number
  - AE's full name and address
  - Client name and address
  - Sequential invoice numbering (continuous, no gaps)
  - Date of issue and due date
  - Description of service
  - Amount (HT), VAT mention ("TVA non applicable, art. 293 B du CGI" for franchise en base)
  - Payment terms and method
- Create quotes (devis) from natural language
- Track quote status (sent / accepted / rejected)
- Send invoices/quotes by email to clients

### Revenue Tracking & URSSAF

- Automatic cumulative revenue tracking (monthly and quarterly)
- Pre-calculated URSSAF contribution estimates based on activity type:
  - BIC (commercial/artisan): 12.3% base rate
  - BNC (liberal profession): 21.1% or 23.1% depending on affiliation
  - + CFP (formation professionnelle) contribution
- Proactive reminders before URSSAF declaration deadlines:
  - Monthly: declare by end of following month
  - Quarterly: Jan 31, Apr 30, Jul 31, Oct 31
- VAT threshold alerts (approaching franchise en base limits):
  - Services: €36,800 (base) / €39,100 (increased)
  - Sales: €91,900 (base) / €101,000 (increased)
- Direct link to URSSAF declaration portal with pre-calculated amount

### Quality of Life

- Auto-maintained client directory (enriched from invoice data)
- "How much did I make this month?" → instant answer
- "Who hasn't paid me?" → list of outstanding invoices
- Mark invoices as paid via chat
- CSV/PDF export for accountant or tax purposes (livre des recettes)

### Explicitly Out of Scope for V1

- ❌ Direct URSSAF API integration (requires formal platform accreditation — see below)
- ❌ Bank account sync (Bridge/Powens costs + complexity)
- ❌ Factur-X / e-invoicing format (mandatory only from Sept 2026 for reception, 2027 for emission)
- ❌ Full TVA management (for AEs who've crossed the threshold)
- ❌ Multi-user / team features
- ❌ WhatsApp channel

---

## Technical Architecture

### System Overview

```
┌─────────────┐     ┌──────────────────────┐     ┌──────────────┐
│  Telegram    │────▶│  Backend             │────▶│  SQLite /    │
│  Bot API     │◀────│  (Node.js + TS)      │◀────│  Postgres    │
└─────────────┘     │                      │     └──────────────┘
                    │  ┌────────────────┐  │
                    │  │ Claude Sonnet  │  │  ← NLP: parse messages
                    │  │ API            │  │    into structured data
                    │  └────────────────┘  │
                    │                      │
                    │  ┌────────────────┐  │
                    │  │ PDF Generator  │  │  ← Compliant invoices
                    │  │ (Puppeteer or  │  │    from HTML templates
                    │  │  pdf-lib)      │  │
                    │  └────────────────┘  │
                    │                      │
                    │  ┌────────────────┐  │
                    │  │ Email Service  │  │  ← Send invoices to
                    │  │ (Resend / SES) │  │    clients
                    │  └────────────────┘  │
                    │                      │
                    │  ┌────────────────┐  │
                    │  │ Cron Scheduler │  │  ← URSSAF reminders,
                    │  │ (node-cron)    │  │    deadline alerts
                    │  └────────────────┘  │
                    └──────────────────────┘
```

### Recommended Stack

| Layer | Technology | Rationale |
|-------|-----------|-----------|
| Runtime | Node.js + TypeScript | Familiar stack (React Native background) |
| Bot Framework | `grammY` or `telegraf` | Well-maintained Telegram bot libraries for Node |
| LLM | Claude Sonnet API | Natural language → structured invoice data |
| PDF Generation | `puppeteer` (HTML→PDF) or `pdf-lib` | Puppeteer for pretty templates; pdf-lib for lightweight |
| Database | SQLite (V1) → PostgreSQL (V2) | Zero config to start, migrate when needed |
| Email | Resend | Simple API, free tier (100 emails/day) |
| Scheduling | `node-cron` | URSSAF deadline reminders |
| Hosting | Hetzner VPS (€5/mo) or Railway/Fly.io | Cheap, reliable |

### LLM Integration: Natural Language Parsing

The Claude Sonnet API parses free-form French messages into structured actions:

**Input:**
```
"Facture 800 balles à Dupont pour du coaching le 15 mars"
```

**Expected Output:**
```json
{
  "action": "create_invoice",
  "amount": 800,
  "client_name": "Dupont",
  "description": "Coaching",
  "date": "2026-03-15",
  "currency": "EUR"
}
```

**Key design principles for the LLM layer:**
- Always confirm before executing actions (especially financial ones)
- Handle ambiguity by asking clarifying questions ("One invoice for both, or two separate ones?")
- Support French slang and abbreviations ("800 balles", "facture", "devis")
- Maintain conversation context (follow-up corrections: "Actually make it 900€")
- Fallback to structured prompts if parsing confidence is low

### Data Model (Core Entities)

```
User
├── telegram_id (PK)
├── name
├── siren / siret
├── address
├── activity_type (BIC_VENTE | BIC_SERVICE | BNC)
├── declaration_periodicity (MONTHLY | QUARTERLY)
├── vat_status (FRANCHISE | ASSUJETTI)
└── created_at

Client
├── id (PK)
├── user_id (FK → User)
├── name
├── email
├── address
├── company_name (nullable)
└── siret (nullable)

Invoice
├── id (PK)
├── user_id (FK → User)
├── client_id (FK → Client)
├── invoice_number (sequential, e.g. "2026-001")
├── type (INVOICE | QUOTE)
├── status (DRAFT | SENT | PAID | OVERDUE | CANCELLED)
├── amount_ht (cents)
├── vat_rate (0 for franchise en base)
├── description
├── issue_date
├── due_date
├── paid_date (nullable)
├── pdf_path
└── created_at

Declaration
├── id (PK)
├── user_id (FK → User)
├── period_start
├── period_end
├── total_revenue (cents)
├── estimated_contributions (cents)
├── status (UPCOMING | REMINDED | DECLARED)
└── due_date
```

---

## URSSAF API — Future Integration (V2+)

### What the API Offers

The URSSAF provides a free REST API called **"API Tierce Déclaration Auto-Entrepreneur"** (aka "AE Connect") that enables:

1. **Account eligibility check**: Verify if an AE account is eligible + get declaration periodicity
2. **Mandate management**: Register/revoke third-party declaration mandates
3. **Estimation**: Calculate expected social contributions based on revenue
4. **Declaration**: Submit revenue declarations on behalf of the AE
5. **Payment**: Initiate SEPA direct debit for contribution payments
6. **SEPA mandate management**: List/add/revoke SEPA mandates

### Accreditation Requirements

To access the API, a platform must:
- Be a registered legal entity (SIRET)
- Be current on its own social contribution obligations
- Sign the **LCTI license** (Lutte Contre le Travail Illégal — anti-illegal-work commitment)
- Offer a digital service aimed at auto-entrepreneurs
- Apply via `portailapi.urssaf.fr` → sandbox access first, then production
- Display the mandatory **"AE Connect" logo** on the platform
- Allow users to revoke the mandate at any time

**Authentication**: OAuth2 Client Credentials flow.

### Why Not in V1

The accreditation process adds weeks of administrative overhead and requires a live product. The V1 strategy is to validate the conversational UX and invoice generation first, then pursue URSSAF API integration once there's traction. In V1, the bot provides pre-calculated amounts and direct links to the URSSAF portal — the user does the final click themselves.

---

## Compliance & Legal Considerations

### Invoice Requirements (French Law)

Every invoice must include:
- Date of issue
- Sequential number (unique, continuous series)
- Seller: name, SIREN, address, mention "EI" (Entreprise Individuelle)
- Buyer: name, address, SIRET if applicable
- Description of goods/services
- Quantity and unit price (HT)
- Total amount HT and TTC
- VAT mention: "TVA non applicable, article 293 B du CGI" (if franchise en base)
- Payment terms and conditions
- Late payment penalties mention

### Livre des Recettes (Revenue Ledger)

Auto-entrepreneurs must maintain a chronological record of all revenue with:
- Date of payment
- Client identity
- Invoice reference
- Amount
- Payment method

The bot should auto-generate this from invoice data.

### RGPD / Data Privacy

The bot handles sensitive data:
- Client names and emails
- Financial amounts
- Potentially NIR (social security numbers) in V2 with URSSAF integration
- IBAN for payment tracking

Requirements:
- Data encryption at rest and in transit
- Privacy policy accessible to users
- Data export capability (portability)
- Data deletion on request
- Minimal data collection principle

### E-Invoicing Reform (2026-2027)

- **September 2026**: All businesses must be able to receive electronic invoices
- **September 2027**: Micro-enterprises must be able to emit electronic invoices
- Format: **Factur-X** (hybrid PDF + XML) via certified platforms (PDP) or Chorus Pro
- This is a V3 concern but should inform architectural decisions (generate invoices in a format that can later be extended to Factur-X)

---

## Unit Economics (If Scaled)

### Cost per User (Estimated)

| Item | Monthly Cost |
|------|-------------|
| Claude Sonnet API (~50 messages/user/mo) | ~€0.15 |
| Hosting (amortized) | ~€0.10 |
| Email (Resend, ~20 emails/user/mo) | ~€0.05 |
| **Total** | **~€0.30/user/mo** |

### Potential Pricing

- **Free tier**: 5 invoices/month (enough to test)
- **Pro tier**: €7-9/month for unlimited invoices + URSSAF reminders + exports
- Healthy margin at scale (~95%+ gross margin)

### TAM Estimate

- 1.6M economically active AEs × ~50% who would pay for a tool = 800k addressable
- Even capturing 0.1% = 800 paying users = ~€6k/mo recurring
- Abby captured 100k users with €390k raised — shows the market responds to good products

---

## Development Roadmap

### Phase 1: MVP (2-3 weekends)
- [ ] Telegram bot setup (grammY/telegraf)
- [ ] User onboarding flow (collect SIREN, activity type, address)
- [ ] Claude Sonnet integration for message parsing
- [ ] Invoice PDF generation (compliant template)
- [ ] Email sending (Resend)
- [ ] SQLite database for users, clients, invoices
- [ ] Basic revenue tracking ("How much this month?")
- [ ] URSSAF deadline reminders (cron)

### Phase 2: Polish (month 2)
- [ ] Quote (devis) generation and tracking
- [ ] Client directory with auto-completion
- [ ] Outstanding invoice tracking ("Who hasn't paid?")
- [ ] CSV export (livre des recettes)
- [ ] VAT threshold alerts
- [ ] Invoice customization (logo, colors)

### Phase 3: Growth (month 3+)
- [ ] URSSAF API integration (accreditation process)
- [ ] WhatsApp channel support
- [ ] Bank sync (Bridge API) for automatic revenue detection
- [ ] Factur-X format support
- [ ] Landing page + onboarding for new users beyond the friend circle

---

## Key Risks

| Risk | Severity | Mitigation |
|------|----------|------------|
| LLM parsing errors on financial data | High | Always confirm before executing; show parsed data for validation |
| Invoice non-compliance | High | Thoroughly research and template all mandatory mentions; get accountant review |
| RGPD violation | Medium | Encrypt data, minimal collection, privacy policy from day 1 |
| User doesn't adopt (still prefers manual) | Medium | Onboard friend first, iterate on friction points |
| WhatsApp needed for real traction | Medium | Build abstraction layer from the start to support multiple channels |
| URSSAF API accreditation blocked/slow | Low (V2) | V1 works without it; manual declaration with pre-calculated amounts |

---

## Open Questions for Implementation

1. **Invoice numbering**: Should it be `YYYY-NNN` (e.g., `2026-001`) or fully sequential? French law requires continuous numbering with no gaps.
2. **Multi-activity support**: Some AEs have mixed BIC+BNC activities with different contribution rates. Support in V1 or defer?
3. **Language**: Bot should speak French to users. LLM system prompt and all UX copy in French.
4. **PDF template design**: Minimalist and professional. Should we include the AE's logo? (Requires image upload flow in Telegram.)
5. **Conversation state management**: How to handle multi-turn invoice creation if the user provides partial info? (e.g., amount but no client name)
6. **Testing strategy**: Unit tests for contribution calculations, integration tests for the full message→invoice flow.

---

## References

- URSSAF API Portal: `portailapi.urssaf.fr`
- API Documentation: `data.gouv.fr/dataservices/api-tierce-declaration-auto-entrepreneur`
- AE Declaration Portal: `autoentrepreneur.urssaf.fr`
- Contact for API accreditation: `contact.tiercedeclaration@urssaf.fr`
- Telegram Bot API: `core.telegram.org/bots/api`
- grammY framework: `grammy.dev`
- Resend email API: `resend.com`
