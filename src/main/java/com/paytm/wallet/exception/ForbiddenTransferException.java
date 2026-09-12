package com.paytm.wallet.exception;

/** Caller's bearer token does not own the source wallet. Maps to 403. */
public class ForbiddenTransferException extends RuntimeException {
    public ForbiddenTransferException(String message) { super(message); }
}
