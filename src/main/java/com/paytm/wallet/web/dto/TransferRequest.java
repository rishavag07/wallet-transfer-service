package com.paytm.wallet.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TransferRequest(
        @NotNull Long from,
        @NotNull Long to,
        @NotNull @Positive Long amountPaise,
        @NotBlank String idempotencyKey
) {
}
