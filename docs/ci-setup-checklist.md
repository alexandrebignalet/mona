# CI/CD Setup Checklist

## GitHub Repository Secrets

Go to: **GitHub → Settings → Secrets and variables → Actions → New repository secret**

| Secret | Value | How to get it |
|--------|-------|---------------|
| `ANTHROPIC_API_KEY` | `sk-ant-...` | [Anthropic Console](https://console.anthropic.com) → API Keys |
| `FLY_API_TOKEN` | `fo1_...` | Run: `flyctl tokens create deploy -a mona` |

---

## Fly.io App Secrets (production env vars)

Run `flyctl secrets set` once per secret (or use the Fly.io dashboard):

```bash
flyctl secrets set \
  TELEGRAM_BOT_TOKEN="..." \
  ANTHROPIC_API_KEY="..." \
  IBAN_ENCRYPTION_KEY="..." \
  RESEND_API_KEY="..." \
  -a mona
```

| Secret | Description |
|--------|-------------|
| `TELEGRAM_BOT_TOKEN` | BotFather token |
| `ANTHROPIC_API_KEY` | Claude API key (also used at runtime) |
| `IBAN_ENCRYPTION_KEY` | AES-256-GCM key, base64-encoded (generate with `openssl rand -base64 32`) |
| `RESEND_API_KEY` | Resend dashboard → API Keys |

The non-secret env vars (`DATABASE_PATH`, etc.) are already in `fly.toml`.

---

## Fly.io Volume (one-time)

The persistent SQLite volume must exist before the first deploy:

```bash
flyctl volumes create mona_data --region cdg --size 1 -a mona
```

---

## Litestream Backup (S3-compatible)

Set these as Fly.io secrets if you want continuous SQLite backup:

```bash
flyctl secrets set \
  LITESTREAM_ACCESS_KEY_ID="..." \
  LITESTREAM_SECRET_ACCESS_KEY="..." \
  -a mona
```

Configure the bucket URL in `litestream.yml`.

---

## Branch Protection (recommended)

Go to: **GitHub → Settings → Branches → Add rule for `main`**

- [x] Require status checks to pass before merging
  - Required check: `ci` (the CI workflow job)
- [x] Require branches to be up to date before merging
- [ ] Do NOT require golden tests (they are manual)

---

## First Deploy

```bash
# Authenticate locally
flyctl auth login

# Create the app (first time only)
flyctl launch --no-deploy

# Set secrets (see above)

# Deploy
flyctl deploy
```

Subsequent deploys are done via the **Deploy** GitHub Actions workflow (manual trigger).
