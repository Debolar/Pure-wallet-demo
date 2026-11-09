package dev.purse;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin (back-office) API. Every request needs the header  X-Admin-Key: <key>  matching ADMIN_API_KEY.
 *
 * It is READ-ONLY on purpose. In a real money system nobody edits a balance directly:
 * every change must be a recorded transaction, so the books always explain themselves.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final WalletService wallets;
    private final String adminKey;

    public AdminController(WalletService wallets, @Value("${app.admin-api-key:}") String adminKey) {
        this.wallets = wallets;
        this.adminKey = adminKey;
    }

    /** Lets the admin page check a key before showing anything. */
    @GetMapping("/check")
    public Dtos.Summary check(@RequestHeader(value = "X-Admin-Key", required = false) String key) {
        requireAdmin(key);
        return wallets.summary();
    }

    /** Totals, and whether the books balance (no money created or lost). */
    @GetMapping("/summary")
    public Dtos.Summary summary(@RequestHeader(value = "X-Admin-Key", required = false) String key) {
        requireAdmin(key);
        return wallets.summary();
    }

    /** All wallets, highest balance first. */
    @GetMapping("/wallets")
    public List<Dtos.AdminWallet> listWallets(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                              @RequestParam(defaultValue = "50") int limit) {
        requireAdmin(key);
        return wallets.adminWallets(limit);
    }

    /** Latest transactions across every wallet. */
    @GetMapping("/transactions")
    public List<Dtos.Transaction> recentTransactions(
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @RequestParam(defaultValue = "50") int limit) {
        requireAdmin(key);
        return wallets.recentTransactions(limit);
    }

    private void requireAdmin(String key) {
        if (adminKey == null || adminKey.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Admin is switched off (ADMIN_API_KEY not set)");
        }
        if (!Tokens.matches(key, adminKey)) {
            throw ApiException.unauthorized("Missing or wrong admin key");
        }
    }
}
