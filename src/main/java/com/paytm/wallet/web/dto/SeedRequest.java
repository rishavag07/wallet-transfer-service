package com.paytm.wallet.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record SeedRequest(@NotNull @Positive Long amountPaise) {
}
