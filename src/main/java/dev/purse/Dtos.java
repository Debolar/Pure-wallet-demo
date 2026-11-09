package dev.purse;

import java.util.List;

/** The shapes of JSON going in and out of the API. Amounts are always in kobo (whole numbers). */
public final class Dtos {

    private Dtos() {
    }

    // ----- requests -----
    public record CreateWalletRequest(String name, String email) {
    }

    public record DepositRequest(long amount) {
    }

    public record TransferRequest(String toWallet, long amount, String note) {
    }

    // ----- responses -----
    /** Returned once, when a wallet is created. The token is never shown again. */
    public record NewWallet(String id, String name, String email, long balance, String token) {
    }

    public record Wallet(String id, String name, long balance) {
    }

    public record Transaction(String reference, String kind, String fromWallet, String toWallet,
                              long amount, String note, String createdAt) {
    }

    /** One line of a wallet's statement: positive = money in, negative = money out. */
    public record StatementLine(String reference, String kind, String counterparty, long amount,
                                long balanceAfter, String note, String createdAt) {
    }

    public record Statement(Wallet wallet, List<StatementLine> lines) {
    }

    /** Admin view. booksBalance is true when total balances equal total deposits. */
    public record Summary(long wallets, long transactions, long totalBalance, long totalDeposits,
                          boolean booksBalance) {
    }

    /** A wallet as staff see it. The token hash is never included. */
    public record AdminWallet(String id, String name, String email, long balance, String createdAt) {
    }

    public record Info(String app, String version, String environment, String database,
                       boolean adminEnabled, String currency) {
    }
}
