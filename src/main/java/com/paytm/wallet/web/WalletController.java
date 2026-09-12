package com.paytm.wallet.web;

import com.paytm.wallet.service.WalletService;
import com.paytm.wallet.web.dto.SeedRequest;
import com.paytm.wallet.web.dto.WalletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping("/wallets")
    public ResponseEntity<WalletResponse> getOrCreate(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String userId = BearerAuth.extractUserId(authorization);
        WalletService.WalletResult result = walletService.getOrCreate(userId);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.wallet());
    }

    @GetMapping("/wallets/{id}")
    public WalletResponse getById(@PathVariable long id) {
        return walletService.getById(id);
    }

    // Testing/demo utility ONLY -- see WalletService.seed() javadoc.
    @PostMapping("/wallets/{id}/seed")
    public WalletResponse seed(@PathVariable long id, @Valid @RequestBody SeedRequest request) {
        return walletService.seed(id, request.amountPaise());
    }
}
