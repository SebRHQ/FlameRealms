ALTER TABLE realms
  ADD COLUMN friendly_fire_enabled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE realm_claims (
  id                BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  realm_id          BIGINT UNSIGNED NOT NULL,
  world             VARCHAR(64)  NOT NULL,
  chunk_x           INT          NOT NULL,
  chunk_z           INT          NOT NULL,
  claimed_at        DATETIME     NOT NULL,
  price_paid_cents  BIGINT       NOT NULL,
  UNIQUE KEY uq_chunk (world, chunk_x, chunk_z),
  INDEX idx_realm (realm_id),
  FOREIGN KEY (realm_id) REFERENCES realms(id) ON DELETE CASCADE
);

-- contributed is unused/always-false as of this milestone: the "presence
-- UNION contribution" active-population signal (see pricing.yml's
-- upkeep.active-population block) is meant to combine online-minutes
-- presence with economic contribution, but contribution_events (M5) does
-- not exist yet. Until then this column is written FALSE and the signal
-- degrades to presence-only.
CREATE TABLE realm_member_activity (
  realm_id          BIGINT UNSIGNED NOT NULL,
  player_uuid       BINARY(16)   NOT NULL,
  activity_date     DATE         NOT NULL,
  online_minutes    INT UNSIGNED NOT NULL DEFAULT 0,
  contributed       BOOLEAN      NOT NULL DEFAULT FALSE,
  PRIMARY KEY (realm_id, player_uuid, activity_date)
);
