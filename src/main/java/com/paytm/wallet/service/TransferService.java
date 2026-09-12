package com.paytm.wallet.service;

import com.paytm.wallet.exception.ForbiddenTransferException;
import com.paytm.wallet.exception.IdempotencyConflictException;
import com.paytm.wallet.exception.WalletNotFoundException;
import com.paytm.wallet.metrics.DomainMetrics;
import com.paytm.wallet.web.dto.TransferResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final JdbcTemplate jdbc;
    private final DomainMetrics metrics;

    public TransferService(JdbcTemplate jdbc, DomainMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    /**
     * Executes (or replays) a transfer. This is the one method that has to get
     * every concurrency invariant right, so the ordering of operations below is
     * deliberate and load-tested (see scripts/burst.sh, and the standalone JDBC
     * harness used during development) -- do not reorder without re-running the
     * A<->B contention burst.
     *
     * Ordering, and why:
     *
     *   1. Lock both wallets, in ascending-id order, as two SEQUENTIAL
     *      single-row `SELECT ... FOR UPDATE` statements -- BEFORE touching the
     *      transfers table at all.
     *
     *      This must happen first, before the idempotency-key insert (step 2),
     *      because that insert's foreign key constraints (from_wallet/to_wallet
     *      -> wallets.id) take their own implicit row lock during the INSERT,
     *      in from/to column order -- NOT sorted order. If wallets were locked
     *      after the insert, an A->B transfer and a concurrent B->A transfer
     *      could each hold a shared FK lock on their own "from" row and then
     *      both block trying to upgrade to an exclusive lock on the other's
     *      row: a lock-upgrade deadlock on a single row, happening before our
     *      own sorted locking ever gets a chance to matter. This was a real bug
     *      caught in local load testing, not a theoretical concern.
     *
     *      Also note: a single `SELECT ... WHERE id = ANY(?) ORDER BY id FOR
     *      UPDATE` does NOT give a sorted lock order either -- Postgres's
     *      LockRows plan node locks rows in scan order, which happens BEFORE
     *      the Sort node runs. Two SEQUENTIAL single-row statements, issued in
     *      explicit ascending-id order, is what actually enforces the order.
     *      That was the first deadlock bug caught here.
     *
     *   2. Claim the idempotency key: INSERT ... ON CONFLICT (idempotency_key)
     *      DO NOTHING, in the SAME transaction as the ledger movement. Whoever
     *      wins this INSERT owns running the transfer; everyone else --
     *      including a concurrent retry of the exact same request -- falls
     *      through to the "existing" branch and replays the committed result.
     *      This is what makes idempotency race-free instead of TOCTOU: there
     *      is no separate "check if it exists" query before the write. Because
     *      step 1 already holds an exclusive lock on both wallet rows, this
     *      INSERT's own FK-check lock is trivially satisfied (same transaction
     *      already holds a stronger lock), so it never conflicts with another
     *      transaction's FK check.
     *
     *   3. Only for the winner of step 2: ownership check, then the atomic
     *      conditional debit (`balance_paise >= amount` in the WHERE clause,
     *      as defense-in-depth on top of the row lock) and credit, then mark
     *      the transfer completed.
     */
    @Transactional
    public Outcome execute(long fromId, long toId, long amountPaise, String idempotencyKey, String callerUserId) {
        if (fromId == toId) {
            throw new IllegalArgumentException("from and to must differ");
        }

        long lowerId = Math.min(fromId, toId);
        long higherId = Math.max(fromId, toId);

        WalletLock lowerLock = lockWallet(lowerId);
        WalletLock higherLock = lockWallet(higherId);
        WalletLock fromLock = (fromId == lowerId) ? lowerLock : higherLock;

        List<Map<String, Object>> inserted = jdbc.queryForList(
                "INSERT INTO transfers (idempotency_key, from_wallet, to_wallet, amount_paise, status) " +
                        "VALUES (?, ?, ?, ?, 'pending') " +
                        "ON CONFLICT (idempotency_key) DO NOTHING " +
                        "RETURNING id",
                idempotencyKey, fromId, toId, amountPaise
        );

        if (inserted.isEmpty()) {
            return handleExistingIdempotencyKey(idempotencyKey, fromId, toId, amountPaise);
        }

        long transferId = ((Number) inserted.get(0).get("id")).longValue();

        if (!fromLock.userId().equals(callerUserId)) {
            throw new ForbiddenTransferException("you do not own the source wallet");
        }

        if (fromLock.balancePaise() < amountPaise) {
            jdbc.update(
                    "UPDATE transfers SET status = 'declined_insufficient_funds', completed_at = now() WHERE id = ?",
                    transferId
            );
            metrics.transferDeclinedInsufficientFunds();
            log.info("transfer declined (insufficient funds): transfer_id={} from={} to={} amount_paise={}",
                    transferId, fromId, toId, amountPaise);
            TransferResponse body = new TransferResponse(
                    String.valueOf(transferId), String.valueOf(fromId), String.valueOf(toId),
                    amountPaise, "declined_insufficient_funds"
            );
            return new Outcome(body, HttpStatus.UNPROCESSABLE_ENTITY);
        }

        int debited = jdbc.update(
                "UPDATE wallets SET balance_paise = balance_paise - ? WHERE id = ? AND balance_paise >= ?",
                amountPaise, fromId, amountPaise
        );
        int credited = jdbc.update(
                "UPDATE wallets SET balance_paise = balance_paise + ? WHERE id = ?",
                amountPaise, toId
        );
        if (debited != 1 || credited != 1) {
            // Should be unreachable given the row lock + balance check above.
            // Guards against ever committing a half-applied transfer.
            throw new IllegalStateException("transfer application affected unexpected row count");
        }

        jdbc.update("UPDATE transfers SET status = 'completed', completed_at = now() WHERE id = ?", transferId);
        metrics.transferCompleted();
        log.info("transfer completed: transfer_id={} from={} to={} amount_paise={}",
                transferId, fromId, toId, amountPaise);

        TransferResponse body = new TransferResponse(
                String.valueOf(transferId), String.valueOf(fromId), String.valueOf(toId),
                amountPaise, "completed"
        );
        return new Outcome(body, HttpStatus.CREATED);
    }

    public TransferResponse getById(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, from_wallet, to_wallet, amount_paise, status FROM transfers WHERE id = ?", id
        );
        if (rows.isEmpty()) {
            throw new WalletNotFoundException("transfer not found");
        }
        return toResponse(rows.get(0));
    }

    private Outcome handleExistingIdempotencyKey(String idempotencyKey, long fromId, long toId, long amountPaise) {
        Map<String, Object> existing = jdbc.queryForMap(
                "SELECT id, from_wallet, to_wallet, amount_paise, status FROM transfers WHERE idempotency_key = ?",
                idempotencyKey
        );

        boolean sameBody = ((Number) existing.get("from_wallet")).longValue() == fromId
                && ((Number) existing.get("to_wallet")).longValue() == toId
                && ((Number) existing.get("amount_paise")).longValue() == amountPaise;

        if (!sameBody) {
            metrics.idempotencyConflict();
            log.warn("idempotency conflict: same key reused with a different body, key={}", idempotencyKey);
            throw new IdempotencyConflictException("idempotency_key already used with a different request");
        }

        metrics.idempotentReplay();
        long transferId = ((Number) existing.get("id")).longValue();
        log.info("idempotent replay served: key={} transfer_id={}", idempotencyKey, transferId);

        String status = (String) existing.get("status");
        HttpStatus httpStatus = "declined_insufficient_funds".equals(status)
                ? HttpStatus.UNPROCESSABLE_ENTITY
                : HttpStatus.OK;
        return new Outcome(toResponse(existing), httpStatus);
    }

    private WalletLock lockWallet(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT balance_paise, user_id FROM wallets WHERE id = ? FOR UPDATE", id
        );
        if (rows.isEmpty()) {
            throw new WalletNotFoundException("from or to wallet does not exist");
        }
        Map<String, Object> row = rows.get(0);
        return new WalletLock(((Number) row.get("balance_paise")).longValue(), (String) row.get("user_id"));
    }

    private static TransferResponse toResponse(Map<String, Object> row) {
        return new TransferResponse(
                String.valueOf(row.get("id")),
                String.valueOf(row.get("from_wallet")),
                String.valueOf(row.get("to_wallet")),
                ((Number) row.get("amount_paise")).longValue(),
                (String) row.get("status")
        );
    }

    private record WalletLock(long balancePaise, String userId) {
    }

    public record Outcome(TransferResponse body, HttpStatus status) {
    }
}
