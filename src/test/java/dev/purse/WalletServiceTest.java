package dev.purse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

/** Tests of the money rules, run against the in-memory H2 database. */
@SpringBootTest(properties = "app.admin-api-key=test-admin-key")
class WalletServiceTest {

    @Autowired
    WalletService wallets;

    private Dtos.NewWallet fundedWallet(long kobo) {
        Dtos.NewWallet w = wallets.createWallet("Ada Test", "ada@example.com");
        if (kobo > 0) {
            wallets.deposit(w.id(), w.token(), kobo, null);
        }
        return w;
    }

    private long balance(Dtos.NewWallet w) {
        return wallets.getWallet(w.id(), w.token()).balance();
    }

    private static void assertStatus(Runnable action, HttpStatus status) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(status);
    }

    @Test
    void newWalletStartsEmptyAndGetsAToken() {
        Dtos.NewWallet w = wallets.createWallet("  Chidi  ", "Chidi@Example.com");
        assertThat(w.id()).startsWith("W").hasSize(10);
        assertThat(w.token()).hasSizeGreaterThan(30);
        assertThat(w.name()).isEqualTo("Chidi");
        assertThat(w.email()).isEqualTo("chidi@example.com");
        assertThat(balance(w)).isZero();
    }

    @Test
    void rejectsBadNameOrEmail() {
        assertStatus(() -> wallets.createWallet("", "a@b.co"), HttpStatus.UNPROCESSABLE_ENTITY);
        assertStatus(() -> wallets.createWallet("Ada", "not-an-email"), HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void depositIncreasesBalanceAndWritesStatement() {
        Dtos.NewWallet w = fundedWallet(500_000);
        assertThat(balance(w)).isEqualTo(500_000);
        Dtos.Statement s = wallets.statement(w.id(), w.token(), 10);
        assertThat(s.lines()).hasSize(1);
        assertThat(s.lines().get(0).amount()).isEqualTo(500_000);
        assertThat(s.lines().get(0).counterparty()).isEqualTo("Deposit");
    }

    @Test
    void transferMovesMoneyAndWritesBothLedgerLines() {
        Dtos.NewWallet a = fundedWallet(10_000);
        Dtos.NewWallet b = fundedWallet(0);
        WalletService.Result r = wallets.transfer(a.id(), a.token(), b.id(), 2_500, "Lunch", null);

        assertThat(r.replayed()).isFalse();
        assertThat(r.transaction().reference()).startsWith("TX");
        assertThat(balance(a)).isEqualTo(7_500);
        assertThat(balance(b)).isEqualTo(2_500);
        assertThat(wallets.statement(a.id(), a.token(), 10).lines().get(0).amount()).isEqualTo(-2_500);
        assertThat(wallets.statement(b.id(), b.token(), 10).lines().get(0).counterparty()).isEqualTo(a.id());
    }

    @Test
    void insufficientFundsChangesNothing() {
        Dtos.NewWallet a = fundedWallet(1_000);
        Dtos.NewWallet b = fundedWallet(0);
        assertStatus(() -> wallets.transfer(a.id(), a.token(), b.id(), 1_001, null, null), HttpStatus.CONFLICT);
        assertThat(balance(a)).isEqualTo(1_000);
        assertThat(balance(b)).isZero();
    }

    @Test
    void wrongTokenIsRejected() {
        Dtos.NewWallet a = fundedWallet(1_000);
        Dtos.NewWallet b = fundedWallet(0);
        assertStatus(() -> wallets.transfer(a.id(), "wrong-token", b.id(), 100, null, null), HttpStatus.UNAUTHORIZED);
        assertStatus(() -> wallets.getWallet(a.id(), null), HttpStatus.UNAUTHORIZED);
        assertStatus(() -> wallets.getWallet("WDOESNOTEX", "x"), HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unknownRecipientAndSelfTransferAreRejected() {
        Dtos.NewWallet a = fundedWallet(1_000);
        assertStatus(() -> wallets.transfer(a.id(), a.token(), "WNOSUCHWAL", 100, null, null), HttpStatus.NOT_FOUND);
        assertStatus(() -> wallets.transfer(a.id(), a.token(), a.id(), 100, null, null),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(balance(a)).isEqualTo(1_000);
    }

    @Test
    void amountMustBePositiveAndWithinLimit() {
        Dtos.NewWallet a = fundedWallet(1_000);
        Dtos.NewWallet b = fundedWallet(0);
        assertStatus(() -> wallets.transfer(a.id(), a.token(), b.id(), 0, null, null), HttpStatus.UNPROCESSABLE_ENTITY);
        assertStatus(() -> wallets.transfer(a.id(), a.token(), b.id(), -50, null, null), HttpStatus.UNPROCESSABLE_ENTITY);
        assertStatus(() -> wallets.deposit(a.id(), a.token(), WalletService.MAX_AMOUNT + 1, null),
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void sameIdempotencyKeyIsAppliedOnlyOnce() {
        Dtos.NewWallet a = fundedWallet(10_000);
        Dtos.NewWallet b = fundedWallet(0);
        WalletService.Result first = wallets.transfer(a.id(), a.token(), b.id(), 3_000, null, "retry-test-001");
        WalletService.Result second = wallets.transfer(a.id(), a.token(), b.id(), 3_000, null, "retry-test-001");

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.transaction().reference()).isEqualTo(first.transaction().reference());
        assertThat(balance(a)).isEqualTo(7_000);
        assertThat(balance(b)).isEqualTo(3_000);
    }

    @Test
    void reusingAKeyForADifferentRequestIsRefused() {
        Dtos.NewWallet a = fundedWallet(10_000);
        Dtos.NewWallet b = fundedWallet(0);
        wallets.transfer(a.id(), a.token(), b.id(), 3_000, null, "retry-test-002");
        assertStatus(() -> wallets.transfer(a.id(), a.token(), b.id(), 4_000, null, "retry-test-002"),
                HttpStatus.CONFLICT);
        assertThat(balance(a)).isEqualTo(7_000);
    }

    /** 20 transfers of 1,000 at the same moment from a wallet holding 10,000: exactly 10 may succeed. */
    @Test
    void concurrentTransfersNeverOverspend() throws Exception {
        Dtos.NewWallet a = fundedWallet(10_000);
        Dtos.NewWallet b = fundedWallet(0);
        List<Callable<Boolean>> jobs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            jobs.add(() -> {
                try {
                    wallets.transfer(a.id(), a.token(), b.id(), 1_000, null, null);
                    return true;
                } catch (ApiException e) {
                    return false;
                }
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(20);
        int ok = 0;
        for (Future<Boolean> f : pool.invokeAll(jobs)) {
            if (f.get()) {
                ok++;
            }
        }
        pool.shutdown();

        assertThat(ok).isEqualTo(10);
        assertThat(balance(a)).isZero();
        assertThat(balance(b)).isEqualTo(10_000);
    }

    /** Transfers in both directions at once must not deadlock, and no money may appear or vanish. */
    @Test
    void transfersBothWaysDoNotDeadlockOrLoseMoney() throws Exception {
        Dtos.NewWallet a = fundedWallet(50_000);
        Dtos.NewWallet b = fundedWallet(50_000);
        List<Callable<Boolean>> jobs = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            boolean aToB = i % 2 == 0;
            jobs.add(() -> {
                Dtos.NewWallet from = aToB ? a : b;
                Dtos.NewWallet to = aToB ? b : a;
                wallets.transfer(from.id(), from.token(), to.id(), 700, null, null);
                return true;
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(10);
        for (Future<Boolean> f : pool.invokeAll(jobs)) {
            assertThat(f.get()).isTrue();
        }
        pool.shutdown();

        assertThat(balance(a) + balance(b)).isEqualTo(100_000);
        assertThat(wallets.summary().booksBalance()).isTrue();
    }
}
