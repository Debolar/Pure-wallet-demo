package dev.purse;

import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wallet API. The owner proves who they are with the header  X-Wallet-Token: <token>
 * (given once when the wallet is created).
 */
@RestController
@RequestMapping("/api/wallets")
public class WalletController {

    private final WalletService wallets;

    public WalletController(WalletService wallets) {
        this.wallets = wallets;
    }

    @PostMapping
    public ResponseEntity<Dtos.NewWallet> create(@RequestBody Dtos.CreateWalletRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(wallets.createWallet(body.name(), body.email()));
    }

    @GetMapping("/{id}")
    public Dtos.Wallet get(@PathVariable String id,
                           @RequestHeader(value = "X-Wallet-Token", required = false) String token) {
        return wallets.getWallet(normalise(id), token);
    }

    @GetMapping("/{id}/transactions")
    public Dtos.Statement statement(@PathVariable String id,
                                    @RequestHeader(value = "X-Wallet-Token", required = false) String token,
                                    @RequestParam(defaultValue = "20") int limit) {
        return wallets.statement(normalise(id), token, limit);
    }

    @PostMapping("/{id}/deposits")
    public ResponseEntity<Dtos.Transaction> deposit(
            @PathVariable String id,
            @RequestHeader(value = "X-Wallet-Token", required = false) String token,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Dtos.DepositRequest body) {
        String walletId = normalise(id);
        return respond(walletId, idempotencyKey,
                () -> wallets.deposit(walletId, token, body.amount(), idempotencyKey));
    }

    @PostMapping("/{id}/transfers")
    public ResponseEntity<Dtos.Transaction> transfer(
            @PathVariable String id,
            @RequestHeader(value = "X-Wallet-Token", required = false) String token,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Dtos.TransferRequest body) {
        String walletId = normalise(id);
        return respond(walletId, idempotencyKey,
                () -> wallets.transfer(walletId, token, body.toWallet(), body.amount(), body.note(), idempotencyKey));
    }

    /**
     * 201 Created for a new transaction; 200 OK plus "Idempotent-Replay: true" for a repeat.
     * If two identical requests arrive at the same instant, the database's UNIQUE rule lets only
     * one through; the other gets DuplicateKeyException and we return the winner's result.
     */
    private ResponseEntity<Dtos.Transaction> respond(String walletId, String idempotencyKey,
                                                     Supplier<WalletService.Result> action) {
        WalletService.Result result;
        try {
            result = action.get();
        } catch (DuplicateKeyException e) {
            result = wallets.replayAfterRace(walletId, idempotencyKey);
            if (result == null) {
                throw e;
            }
        }
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replay", "true").body(result.transaction());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.transaction());
    }

    private static String normalise(String id) {
        return id == null ? "" : id.trim().toUpperCase();
    }
}
