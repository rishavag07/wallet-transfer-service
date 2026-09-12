Wallet & P2P Transfer Service (Spring Boot)

A wallet service with P2P transfers, built to stay correct under concurrency: race-free get-or-create, idempotent exactly-once transfers, conservation + no-overdraft under contention.

Live URL: https://wallet-transfer-service-20h3.onrender.com Burst script: scripts/burst.sh — passes all checks locally and against the live URL.

Quick start
bash
docker compose up --build
./scripts/burst.sh http://localhost:3000
API
POST /wallets — get-or-create wallet (bearer token = user id).
GET /wallets/{id} — balance.
POST /transfers — {"from": 1, "to": 2, "amountPaise": 500, "idempotencyKey": "..."}.
GET /transfers/{id} — status.
POST /wallets/{id}/seed — test-only utility to fund a wallet, not part of the graded API.

/health and /metrics are Spring Actuator endpoints, remapped to those plain paths.

Data model
wallets(id, user_id UNIQUE, balance_paise BIGINT CHECK >= 0)
transfers(id, idempotency_key UNIQUE, from_wallet FK, to_wallet FK, amount_paise BIGINT CHECK > 0, status, CHECK from_wallet <> to_wallet)

Money is integer paise throughout, never floats.

Mechanism + what was rejected
Get-or-create: UNIQUE on user_id + INSERT ... ON CONFLICT DO NOTHING. No check-then-insert gap.
Idempotent transfer: UNIQUE on idempotency_key, claimed via INSERT ... ON CONFLICT DO NOTHING in the same transaction as the debit/credit — so a duplicate request either loses the race and replays the committed result, or gets 409 if the body differs.
No-overdraft: atomic conditional debit (UPDATE ... WHERE balance_paise >= amount, checking rowcount) instead of read-modify-write.
Two-wallet locking: SELECT ... FOR UPDATE in ascending wallet-ID order, as two separate statements — not one ORDER BY ... FOR UPDATE, which does not actually guarantee lock order in Postgres (locks are acquired before the sort step runs). SERIALIZABLE isolation was rejected as heavier than needed.

Two deadlock bugs found by load-testing, both fixed:

ORDER BY ... FOR UPDATE doesn't lock in sorted order — fixed with two sequential FOR UPDATE statements in explicit ascending-ID order.
Even after that, the transfers table's foreign keys took an implicit lock on the wallet rows during the idempotency-key insert, in unsorted column order — causing a lock-upgrade deadlock. Fixed by acquiring the sorted wallet locks before the idempotency insert, so the FK check always finds a lock this same transaction already holds.

Both proven deadlock-free with 100+100 concurrent A↔B transfers, repeated, locally and on the live deployment.

Idempotency placement

In transfers.idempotency_key, same transaction as the ledger movement — not checked separately beforehand.

Consistency vs. availability

Chose consistency: transfers wait on row locks under contention rather than risk a lost update. Cost is bounded throughput on hot wallet pairs under heavy load — accepted trade-off for a money workload.

AI directed vs. decided

The requirement to load-test every concurrency claim against real Postgres instead of reasoning about it on paper was my call. An AI tool wrote the implementation and found both deadlock bugs by running concurrent load, not by inspection — I've since reviewed the reasoning for both and can explain them.

Free-tier cost note

₹0 — Render free web service + free managed Postgres, no card required.