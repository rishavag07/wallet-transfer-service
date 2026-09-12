# Wallet & P2P Transfer Service (Spring Boot)

A small wallet service with peer-to-peer transfers, built to stay correct under
concurrency: race-free get-or-create, idempotent exactly-once transfers, and
conservation + no-overdraft under contention.

**⚠️ Before anything else: run `mvn clean package` locally.** This project was
written in a sandboxed environment whose network could not reach Maven
Central, so I was never able to run a full Spring Boot compile/boot cycle
against it. What I *did* verify, against a real local Postgres instance:

- The exact transaction/locking sequence used in `TransferService.execute()`
  (lock wallets sorted → claim idempotency key → apply movement), proven with
  a standalone plain-JDBC harness — 200 concurrent A↔B transfers, 0 deadlocks,
  0 conservation errors, across multiple repeated runs.
- Everything else (the actual Spring/JDBC wiring, controllers, DTOs, Maven
  config) is hand-written and manually proofread, but **not** compiler- or
  runtime-verified. Please run `mvn clean package` first and fix any typos
  that turn up before you rely on this for your submission — Spring annotation
  processing sometimes surfaces things a plain read-through won't catch.

## Quick start

```bash
docker compose up --build
# API on http://localhost:3000, Postgres on 5432
./scripts/burst.sh http://localhost:3000
```

Or locally without Docker (needs a local Postgres):

```bash
mvn spring-boot:run
```

## API

- `POST /wallets` — get-or-create a wallet for the bearer-token user. `201` if created, `200` if it already existed.
- `GET /wallets/{id}` — current balance.
- `POST /transfers` — body: `{"from": 1, "to": 2, "amountPaise": 500, "idempotencyKey": "..."}`.
- `GET /transfers/{id}` — transfer status.
- `POST /wallets/{id}/seed` — **test utility only**, not part of the graded API. Funds a wallet with `{"amountPaise": 100000}` so the burst script has something to move. A real deployment would never expose this unauthenticated.

Auth: `Authorization: Bearer <user_id>` — deliberately simple per the spec (auth sophistication isn't graded); the token *is* treated as the user id.

`/health` and `/metrics` are Spring Actuator's health and Prometheus endpoints, remapped to those plain paths (see `application.properties`) rather than the `/actuator/*` defaults.

## Data model

- `wallets(id, user_id UNIQUE, balance_paise BIGINT CHECK >= 0)`
- `transfers(id, idempotency_key UNIQUE, from_wallet FK, to_wallet FK, amount_paise BIGINT CHECK > 0, status, CHECK from_wallet <> to_wallet)`

Money is integer paise throughout — no floats, no decimal, enforced by the `BIGINT` column type and by parsing amounts as `long` end to end.

## The concurrency mechanism (and what I rejected)

**Race-free get-or-create.** A `UNIQUE` constraint on `wallets.user_id` plus
`INSERT ... ON CONFLICT (user_id) DO NOTHING`. Whoever's insert wins owns the
row; everyone else's insert is a no-op and they fall through to a plain
`SELECT`. No check-then-insert gap.

**Idempotent exactly-once transfer.** `idempotency_key` is `UNIQUE`, and the
claiming `INSERT ... ON CONFLICT DO NOTHING` runs in the *same* `@Transactional`
method — same transaction — as the ledger movement. A concurrent retry with
the same key either loses the insert race and replays the (by-then committed,
or soon-to-be-committed and blocking-until-committed) result, or — if the body
differs — gets a `409`.

**Conservation + no-overdraft under contention.** An atomic conditional debit
(`UPDATE wallets SET balance_paise = balance_paise - ? WHERE id = ? AND
balance_paise >= ?`, checking `rowCount`) rather than read-modify-write in
application code. For the two-wallet lock itself I used `SELECT ... FOR
UPDATE` in a **deterministic ascending-id order**, issued as two **sequential
single-row statements** rather than one `SELECT ... WHERE id = ANY(...) ORDER
BY id FOR UPDATE` — the latter does not actually guarantee lock-acquisition
order in Postgres (see below). I rejected `SERIALIZABLE` isolation with
retry-on-conflict as heavier than necessary here: the lock discipline is
cheap, well-understood, and doesn't require the client to implement retry
logic for serialization failures.

**Two real deadlock bugs, both caught by load-testing rather than by
reasoning about it in the abstract:**

1. `SELECT ... WHERE id = ANY(?) ORDER BY id FOR UPDATE` looks sorted, but
   isn't at the *locking* level: Postgres's `LockRows` plan node acquires
   locks in scan order, which runs **before** the `Sort` node. The `ORDER BY`
   only affects the order rows are *returned*, not the order locks are
   *acquired*. Fix: two separate `SELECT ... WHERE id = ? FOR UPDATE`
   statements, issued explicitly in ascending-id order.

2. Even with that fixed, deadlocks continued under an A↔B contention burst.
   Cause: the `transfers.from_wallet`/`to_wallet` foreign keys make Postgres
   take an implicit `FOR KEY SHARE` lock on the referenced wallet rows during
   the idempotency-claiming `INSERT` — in `(from, to)` column order, **not**
   sorted order. An A→B transfer and a concurrent B→A transfer could each
   hold a shared FK lock on their own "from" row, then both block trying to
   upgrade to exclusive on the *other's* row — a lock-upgrade deadlock on a
   single row. Fix: acquire the sorted wallet locks **before** the
   idempotency-key insert, so by the time the FK check runs, this transaction
   already holds a stronger lock on both rows and the check is a same-owner
   no-op.

Both fixes are load-tested (100 A→B + 100 B→A concurrent transfers, repeated):
zero deadlocks, conservation held exactly, across multiple runs.

## Where idempotency lives

In `transfers.idempotency_key` (`UNIQUE`), claimed via `INSERT ... ON CONFLICT
DO NOTHING` inside the same `@Transactional` boundary as the wallet locks and
the debit/credit. Same key + same body → replay of the committed result (200,
or 422 if the original was declined). Same key + different body → `409`.

## Consistency vs. availability

Chose strict consistency (linearizable-enough via row locks in a single
Postgres instance) over availability: a transfer under contention will wait
on a lock rather than risk a lost update or a double-apply. For a money
workload this is the right trade — a slightly slower transfer beats an
incorrect one. The corresponding cost: under very high contention on the same
two wallets, throughput is bounded by serialized lock acquisition on those
rows, not by the app tier. That's an accepted, deliberate limit for a
single-Postgres design at this scale.

## AI directed vs. decided

Directed: the choice of atomic-conditional-update over read-modify-write, the
sorted-lock-order approach over `SERIALIZABLE`, the idempotency-key-in-the-
same-transaction requirement, and — critically — the decision to actually
load-test the locking logic against real Postgres rather than trust it by
inspection. Both deadlock bugs above were found this way, not predicted in
advance.

AI-decided (and should be reviewed carefully since this sandbox couldn't
compile it): the specific Spring wiring — `@RestControllerAdvice` exception
mapping, DTO shapes, Actuator path remapping, Dockerfile/compose details,
Logback JSON config.

## Free-tier cost note

₹0: Postgres and the app both run on free tiers of Render/Railway/Fly.io/Koyeb
(pick one with a free managed Postgres instance), no card required for the
tiers used here.
