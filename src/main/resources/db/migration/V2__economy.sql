ALTER TABLE realms
  ADD COLUMN balance_cents BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN upkeep_debt_cents BIGINT NOT NULL DEFAULT 0;

CREATE TABLE player_wallets (
  player_uuid   BINARY(16) NOT NULL PRIMARY KEY,
  balance_cents BIGINT NOT NULL DEFAULT 0,
  updated_at    DATETIME NOT NULL
);

CREATE TABLE transactions (
  id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  ts            DATETIME NOT NULL,
  category      ENUM('FAUCET','SINK','TRANSFER') NOT NULL,
  reason        VARCHAR(32) NOT NULL,
  source_type   ENUM('PLAYER','REALM','SERVER') NOT NULL,
  source_id     VARCHAR(36) NULL,
  target_type   ENUM('PLAYER','REALM','SERVER') NULL,
  target_id     VARCHAR(36) NULL,
  amount_cents  BIGINT NOT NULL,
  metadata      JSON NULL,
  INDEX idx_ts (ts),
  INDEX idx_reason (reason)
);
