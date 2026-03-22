# Seven Wonders Web Game

## Tech Stack (Authoritative)

| Layer | Technology | Notes |
|-------|-----------|-------|
| Framework | Remix (React, SSR) | Loaders/actions for HTTP, client React for game view |
| Runtime | Node.js >= 20 | Node 18 lacks `crypto.hash` |
| Database | SQLite via better-sqlite3 | Lobby/room data only; game state in-memory |
| Real-time | Socket.IO | All in-game communication |
| Styling | Tailwind CSS | Antiquity-inspired palette |
| Language | TypeScript (strict) | No `any`, no `as` casts unless justified |
| Hosting | Fly.io (free tier, single instance) | SQLite on Fly volume |

**Do not introduce unapproved libraries.** No ORMs, Redux/Zustand, CSS-in-JS, or additional frameworks.

## Build & Validate

```bash
npm run dev          # Dev server (tsx server.ts, port 3000)
npm run build        # Production build
npm run typecheck    # tsc --noEmit
npm run lint         # eslint
npm run test         # vitest
npm run typecheck && npm run lint && npm run test  # Full validation
```

## DDD Structure

```
src/
├── domain/          # Pure logic — ZERO imports from remix, socket.io, better-sqlite3, node:*
│   ├── models/      # Entities & value objects
│   ├── services/    # GameEngine, ScoringEngine, TradeCalculator, BotEngine
│   ├── rules/       # Pure functions (affordability, chaining, military, deck building)
│   └── types.ts     # All domain types, enums, interfaces
├── infrastructure/  # Framework & IO adapters
│   ├── db/          # SQLite schema, repositories
│   ├── ws/          # Socket.IO handlers (validate → call use case → emit)
│   └── session/     # Cookie/session management
├── application/     # Thin orchestration (~30 lines max per use case)
│   ├── lobby/       # Room CRUD
│   └── game/        # Start, submit, resolve, disconnect
├── routes/          # Remix routes (call application use cases, no game logic)
├── components/      # Shared React UI components
├── data/            # Static JSON (cards.json, wonders.json)
└── lib/             # Shared utilities
```

### DDD Rules

1. **Domain is pure.** `src/domain/` has zero framework imports.
2. **Domain owns invariants.** Validation lives in domain, not handlers.
3. **Infrastructure adapts.** Translates external systems ↔ domain types. No game logic.
4. **Application orchestrates.** Thin wiring of domain + infrastructure.
5. **Static data feeds domain.** Access via domain service, not direct JSON import in routes.
6. **Test domain in isolation.** No server/DB/socket needed.

### Backpressure Checklist (answer before writing code)

1. Right layer? 2. Approved tech only? 3. Domain stays pure? 4. Not duplicating? 5. Testable in isolation? 6. Matches spec?

## Specs

Spec index with summaries and topic lists: **`specs/README.md`**. Read it to decide which spec files are relevant to your task — then read only those.

**Rule:** When adding a new spec file to `specs/`, you MUST add a corresponding entry to `specs/README.md` following the existing format.

## Auto Memory

**Disabled for this project.** Do not read from or write to the auto memory system (`~/.claude/projects/*/memory/`). All persistent guidance lives in `CLAUDE.md`, `PROMPT_build.md`, and `IMPLEMENTATION_PLAN.md`.

## Updating This File

Update `CLAUDE.md` only when the session reveals a new operational fact not already captured here (e.g. a newly approved library, a confirmed architectural constraint, a permanent workflow rule). Do not update it for task-specific decisions or transient state.

## Operational Notes

- Game state is **in-memory only** — no SQLite for active games.
- SQLite stores lobby/room metadata and sessions only.
- Single Fly.io instance — no horizontal scaling.
- Bots act instantly server-side.
