package dev.purse;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * All the money rules live here.
 *
 * The three rules that make a wallet safe:
 *  1. Atomic: a transfer's debit, credit and records happen in ONE database transaction.
 *     If anything fails, all of it is rolled back. Money is never half-moved.
 *  2. Locked: both wallets are locked (SELECT ... FOR UPDATE) in a fixed order before the
 *     balance check, so two transfers at the same moment can't both spend the same naira,
 *     even with many copies of the app running. The fixed order prevents deadlocks.
 *  3. Idempotent: a client can send an Idempotency-Key. If the same request arrives twice
 *     (a retry after a timeout, a double click), it is applied once and the first result is returned.
 */
@Service
public class WalletService {

    public static final long MAX_AMOUNT = 100_000_000L; // 1,000,000 naira, in kobo
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9_-]{8,100}$");

    private final JdbcTemplate jdbc;
    private final MeterRegistry metrics;

    public WalletService(JdbcTemplate jdbc, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    /** The result of a money movement, and whether it was a repeat of an earlier request. */
    public record Result(Dtos.Transaction transaction, boolean replayed) {
    }

    // ------------------------------------------------------------------ wallets

    public Dtos.NewWallet createWallet(String name, String email) {
        String cleanName = name == null ? "" : name.trim();
        String cleanEmail = email == null ? "" : email.trim().toLowerCase();
        if (cleanName.isEmpty() || cleanName.length() > 100) {
            throw ApiException.badRequest("Name must be 1 to 100 characters");
        }
        if (cleanEmail.length() > 200 || !EMAIL.matcher(cleanEmail).matches()) {
            throw ApiException.badRequest("Please enter a valid email address");
        }
        String id = Tokens.code("W", 9);
        String token = Tokens.secret();
        jdbc.update("INSERT INTO wallets (id, owner_name, email, token_hash) VALUES (?, ?, ?, ?)",
                id, cleanName, cleanEmail, Tokens.hash(token));
        metrics.counter("purse.wallets.opened").increment();
        return new Dtos.NewWallet(id, cleanName, cleanEmail, 0, token);
    }

    public Dtos.Wallet getWallet(String walletId, String token) {
        authenticate(walletId, token);
        return loadWallet(walletId);
    }

    public Dtos.Statement statement(String walletId, String token, int limit) {
        authenticate(walletId, token);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<Dtos.StatementLine> lines = jdbc.query("""
                SELECT l.reference, t.kind, t.from_wallet, t.to_wallet, l.amount,
                       l.balance_after, t.note, l.created_at
                FROM ledger_entries l
                JOIN transactions t ON t.reference = l.reference
                WHERE l.wallet_id = ?
                ORDER BY l.id DESC
                LIMIT ?""",
                (rs, i) -> {
                    long amount = rs.getLong("amount");
                    String counterparty = amount < 0 ? rs.getString("to_wallet") : rs.getString("from_wallet");
                    return new Dtos.StatementLine(
                            rs.getString("reference"),
                            rs.getString("kind"),
                            counterparty == null ? "Deposit" : counterparty,
                            amount,
                            rs.getLong("balance_after"),
                            rs.getString("note"),
                            timestamp(rs));
                },
                walletId, safeLimit);
        return new Dtos.Statement(loadWallet(walletId), lines);
    }

    // ------------------------------------------------------------------ money movements

    /** Demo top-up. In a real system this money would come from a bank or card payment. */
    @Transactional
    public Result deposit(String walletId, String token, long amount, String idempotencyKey) {
        authenticate(walletId, token);
        checkAmount(amount);
        String key = scopedKey(walletId, idempotencyKey);

        Result previous = findPrevious(key, "DEPOSIT", null, walletId, amount);
        if (previous != null) {
            return previous;
        }

        long balance = lockBalance(walletId);
        long newBalance = balance + amount;
        String reference = Tokens.code("TX", 10);

        insertTransaction(reference, "DEPOSIT", null, walletId, amount, "Demo top-up", key);
        jdbc.update("UPDATE wallets SET balance = ? WHERE id = ?", newBalance, walletId);
        insertLedger(reference, walletId, amount, newBalance);

        metrics.counter("purse.deposits", "outcome", "success").increment();
        return new Result(loadTransaction(reference), false);
    }

    @Transactional
    public Result transfer(String fromWallet, String token, String toWallet, long amount, String note,
                           String idempotencyKey) {
        authenticate(fromWallet, token);
        try {
            checkAmount(amount);
        } catch (ApiException e) {
            throw rejected("invalid", e);
        }
        String cleanNote = note == null ? null : note.trim();
        if (cleanNote != null && cleanNote.length() > 140) {
            throw rejected("invalid", ApiException.badRequest("Note must be 140 characters or fewer"));
        }
        if (toWallet == null || toWallet.isBlank()) {
            throw rejected("invalid", ApiException.badRequest("Recipient wallet ID is required"));
        }
        String recipient = toWallet.trim().toUpperCase();
        if (recipient.equals(fromWallet)) {
            throw rejected("invalid", ApiException.badRequest("You can't send money to your own wallet"));
        }
        String key = scopedKey(fromWallet, idempotencyKey);

        Result previous = findPrevious(key, "TRANSFER", fromWallet, recipient, amount);
        if (previous != null) {
            metrics.counter("purse.transfers", "outcome", "replayed").increment();
            return previous;
        }

        // Rule 2: lock both wallets, always in the same (alphabetical) order, to avoid deadlocks.
        boolean senderFirst = fromWallet.compareTo(recipient) < 0;
        long first = lockBalanceOr404(senderFirst ? fromWallet : recipient, !senderFirst);
        long second = lockBalanceOr404(senderFirst ? recipient : fromWallet, senderFirst);
        long senderBalance = senderFirst ? first : second;
        long recipientBalance = senderFirst ? second : first;

        if (senderBalance < amount) {
            throw rejected("insufficient_funds", ApiException.conflict("Insufficient funds"));
        }

        long senderAfter = senderBalance - amount;
        long recipientAfter = recipientBalance + amount;
        String reference = Tokens.code("TX", 10);

        // Rule 1: everything below commits together or not at all.
        insertTransaction(reference, "TRANSFER", fromWallet, recipient, amount, cleanNote, key);
        jdbc.update("UPDATE wallets SET balance = ? WHERE id = ?", senderAfter, fromWallet);
        jdbc.update("UPDATE wallets SET balance = ? WHERE id = ?", recipientAfter, recipient);
        insertLedger(reference, fromWallet, -amount, senderAfter);
        insertLedger(reference, recipient, amount, recipientAfter);

        metrics.counter("purse.transfers", "outcome", "success").increment();
        return new Result(loadTransaction(reference), false);
    }

    /**
     * Used when two identical requests raced and the database's UNIQUE rule stopped the second one.
     * Returns the transaction the first one created.
     */
    public Result replayAfterRace(String walletId, String idempotencyKey) {
        String key = scopedKey(walletId, idempotencyKey);
        if (key == null) {
            return null;
        }
        List<String> refs = jdbc.queryForList(
                "SELECT reference FROM transactions WHERE idempotency_key = ?", String.class, key);
        return refs.isEmpty() ? null : new Result(loadTransaction(refs.get(0)), true);
    }

    // ------------------------------------------------------------------ admin

    public Dtos.Summary summary() {
        long wallets = count("SELECT COUNT(*) FROM wallets");
        long transactions = count("SELECT COUNT(*) FROM transactions");
        long totalBalance = count("SELECT COALESCE(SUM(balance), 0) FROM wallets");
        long totalDeposits = count("SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE kind = 'DEPOSIT'");
        long ledgerTotal = count("SELECT COALESCE(SUM(amount), 0) FROM ledger_entries");
        // Transfers only move money between wallets, so all balances must add up to all deposits,
        // and the ledger must agree. If not, money was created or lost: a serious bug.
        boolean booksBalance = totalBalance == totalDeposits && ledgerTotal == totalBalance;
        return new Dtos.Summary(wallets, transactions, totalBalance, totalDeposits, booksBalance);
    }

    public List<Dtos.AdminWallet> adminWallets(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return jdbc.query("""
                SELECT id, owner_name, email, balance, created_at
                FROM wallets ORDER BY balance DESC, id LIMIT ?""",
                (rs, i) -> new Dtos.AdminWallet(
                        rs.getString("id"),
                        rs.getString("owner_name"),
                        rs.getString("email"),
                        rs.getLong("balance"),
                        timestamp(rs)),
                safeLimit);
    }

    public List<Dtos.Transaction> recentTransactions(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return jdbc.query("""
                SELECT reference, kind, from_wallet, to_wallet, amount, note, created_at
                FROM transactions ORDER BY created_at DESC, reference LIMIT ?""",
                (rs, i) -> new Dtos.Transaction(
                        rs.getString("reference"),
                        rs.getString("kind"),
                        rs.getString("from_wallet"),
                        rs.getString("to_wallet"),
                        rs.getLong("amount"),
                        rs.getString("note"),
                        timestamp(rs)),
                safeLimit);
    }

    // ------------------------------------------------------------------ helpers

    private void authenticate(String walletId, String token) {
        List<String> hashes = jdbc.queryForList(
                "SELECT token_hash FROM wallets WHERE id = ?", String.class, walletId);
        // Same message whether the wallet doesn't exist or the token is wrong,
        // so nobody can use this to discover which wallet IDs exist.
        if (hashes.isEmpty() || token == null || !Tokens.matches(Tokens.hash(token), hashes.get(0))) {
            metrics.counter("purse.auth.failures").increment();
            throw ApiException.unauthorized("Wallet ID or token is wrong");
        }
    }

    private void checkAmount(long amount) {
        if (amount <= 0 || amount > MAX_AMOUNT) {
            throw ApiException.badRequest(
                    "Amount must be between 1 kobo and 1,000,000 naira (100000000 kobo)");
        }
    }

    private String scopedKey(String walletId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        if (!IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw ApiException.badRequest("Idempotency-Key must be 8 to 100 letters, digits, - or _");
        }
        return walletId + ":" + idempotencyKey;
    }

    /** If this idempotency key was used before, return that result (or refuse if the request differs). */
    private Result findPrevious(String key, String kind, String from, String to, long amount) {
        if (key == null) {
            return null;
        }
        List<String> refs = jdbc.queryForList(
                "SELECT reference FROM transactions WHERE idempotency_key = ?", String.class, key);
        if (refs.isEmpty()) {
            return null;
        }
        Dtos.Transaction earlier = loadTransaction(refs.get(0));
        boolean same = earlier.kind().equals(kind)
                && Objects.equals(earlier.fromWallet(), from)
                && earlier.toWallet().equals(to)
                && earlier.amount() == amount;
        if (!same) {
            throw ApiException.conflict("This Idempotency-Key was already used for a different request");
        }
        return new Result(earlier, true);
    }

    private long lockBalance(String walletId) {
        Long balance = jdbc.queryForObject(
                "SELECT balance FROM wallets WHERE id = ? FOR UPDATE", Long.class, walletId);
        return balance == null ? 0 : balance;
    }

    private long lockBalanceOr404(String walletId, boolean isRecipient) {
        try {
            return lockBalance(walletId);
        } catch (EmptyResultDataAccessException e) {
            throw rejected("invalid", ApiException.notFound(
                    isRecipient ? "Recipient wallet not found" : "Wallet not found"));
        }
    }

    private void insertTransaction(String reference, String kind, String from, String to, long amount,
                                   String note, String key) {
        jdbc.update("""
                INSERT INTO transactions (reference, kind, from_wallet, to_wallet, amount, note, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
                reference, kind, from, to, amount, note, key);
    }

    private void insertLedger(String reference, String walletId, long amount, long balanceAfter) {
        jdbc.update("INSERT INTO ledger_entries (reference, wallet_id, amount, balance_after) VALUES (?, ?, ?, ?)",
                reference, walletId, amount, balanceAfter);
    }

    private Dtos.Wallet loadWallet(String walletId) {
        return jdbc.queryForObject("SELECT id, owner_name, balance FROM wallets WHERE id = ?",
                (rs, i) -> new Dtos.Wallet(rs.getString("id"), rs.getString("owner_name"), rs.getLong("balance")),
                walletId);
    }

    private Dtos.Transaction loadTransaction(String reference) {
        return jdbc.queryForObject("""
                SELECT reference, kind, from_wallet, to_wallet, amount, note, created_at
                FROM transactions WHERE reference = ?""",
                (rs, i) -> new Dtos.Transaction(
                        rs.getString("reference"),
                        rs.getString("kind"),
                        rs.getString("from_wallet"),
                        rs.getString("to_wallet"),
                        rs.getLong("amount"),
                        rs.getString("note"),
                        timestamp(rs)),
                reference);
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private static String timestamp(ResultSet rs) throws SQLException {
        java.sql.Timestamp ts = rs.getTimestamp("created_at");
        return ts == null ? null : ts.toInstant().toString();
    }

    private ApiException rejected(String outcome, ApiException e) {
        metrics.counter("purse.transfers", "outcome", outcome).increment();
        return e;
    }
}
