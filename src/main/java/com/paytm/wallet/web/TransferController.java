package com.paytm.wallet.web;

import com.paytm.wallet.service.TransferService;
import com.paytm.wallet.web.dto.TransferRequest;
import com.paytm.wallet.web.dto.TransferResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping("/transfers")
    public ResponseEntity<TransferResponse> transfer(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody TransferRequest request) {
        String userId = BearerAuth.extractUserId(authorization);
        TransferService.Outcome outcome = transferService.execute(
                request.from(), request.to(), request.amountPaise(), request.idempotencyKey(), userId
        );
        return ResponseEntity.status(outcome.status()).body(outcome.body());
    }

    @GetMapping("/transfers/{id}")
    public TransferResponse getById(@PathVariable long id) {
        return transferService.getById(id);
    }
}
