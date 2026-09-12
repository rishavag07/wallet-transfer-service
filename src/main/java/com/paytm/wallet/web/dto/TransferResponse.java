package com.paytm.wallet.web.dto;

public record TransferResponse(
        String id,
        String fromWallet,
        String toWallet,
        long amountPaise,
        String status
) {
}
