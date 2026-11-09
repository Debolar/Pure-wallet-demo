package dev.purse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Creates the tables on startup if they don't exist.
 * The same SQL works on PostgreSQL and on the in-memory H2 database.
 *
 * Money is stored as whole kobo in BIGINT columns (100 kobo = 1 naira).
 * Never use floating-point numbers for money: 0.1 + 0.2 is not 0.3 in floating point.
 */
@Component
public class Schema implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Schema.class);
    private final JdbcTemplate jdbc;

    public Schema(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS wallets (
                    id          VARCHAR(16)  PRIMARY KEY,
                    owner_name  VARCHAR(100) NOT NULL,
                    email       VARCHAR(200) NOT NULL,
                    token_hash  VARCHAR(64)  NOT NULL,
                    balance     BIGINT       NOT NULL DEFAULT 0 CHECK (balance >= 0),
                    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""");
        // One row per money movement. idempotency_key stops the same request being applied twice.
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS transactions (
                    reference        VARCHAR(16)  PRIMARY KEY,
                    kind             VARCHAR(10)  NOT NULL,
                    from_wallet      VARCHAR(16)  REFERENCES wallets(id),
                    to_wallet        VARCHAR(16)  NOT NULL REFERENCES wallets(id),
                    amount           BIGINT       NOT NULL CHECK (amount > 0),
                    note             VARCHAR(140),
                    idempotency_key  VARCHAR(140) UNIQUE,
                    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""");
        // Double-entry ledger: every transfer writes a minus line and a plus line that add up to zero.
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ledger_entries (
                    id             BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    reference      VARCHAR(16)  NOT NULL REFERENCES transactions(reference),
                    wallet_id      VARCHAR(16)  NOT NULL REFERENCES wallets(id),
                    amount         BIGINT       NOT NULL,
                    balance_after  BIGINT       NOT NULL,
                    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_ledger_wallet ON ledger_entries (wallet_id, id)");
        log.info("Database tables ready");
    }
}
