package com.paytm.wallet.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class DomainMetrics {

    private final Counter walletsCreated;
    private final Counter walletsGetOrCreateExisting;
    private final Counter transfersCompleted;
    private final Counter transfersDeclinedInsufficientFunds;
    private final Counter idempotentReplays;
    private final Counter idempotencyConflicts;

    public DomainMetrics(MeterRegistry registry) {
        this.walletsCreated = Counter.builder("wallets_created_total")
                .description("Wallets actually created (excludes get-or-create hits on an existing wallet)")
                .register(registry);
        this.walletsGetOrCreateExisting = Counter.builder("wallets_get_or_create_existing_total")
                .description("POST /wallets calls that returned an already-existing wallet")
                .register(registry);
        this.transfersCompleted = Counter.builder("transfers_completed_total")
                .description("Transfers that completed (debit + credit applied)")
                .register(registry);
        this.transfersDeclinedInsufficientFunds = Counter.builder("transfers_declined_total")
                .tag("reason", "insufficient_funds")
                .description("Transfers declined for insufficient funds")
                .register(registry);
        this.idempotentReplays = Counter.builder("idempotent_replays_total")
                .description("Transfer requests served from an existing idempotency key")
                .register(registry);
        this.idempotencyConflicts = Counter.builder("idempotency_conflicts_total")
                .description("Same idempotency key reused with a different request body (409s)")
                .register(registry);
    }

    public void walletCreated() { walletsCreated.increment(); }
    public void walletExisting() { walletsGetOrCreateExisting.increment(); }
    public void transferCompleted() { transfersCompleted.increment(); }
    public void transferDeclinedInsufficientFunds() { transfersDeclinedInsufficientFunds.increment(); }
    public void idempotentReplay() { idempotentReplays.increment(); }
    public void idempotencyConflict() { idempotencyConflicts.increment(); }
}
