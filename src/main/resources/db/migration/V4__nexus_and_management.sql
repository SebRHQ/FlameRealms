ALTER TABLE realms
  ADD COLUMN nexus_world VARCHAR(64) NULL,
  ADD COLUMN nexus_x INT NULL,
  ADD COLUMN nexus_y INT NULL,
  ADD COLUMN nexus_z INT NULL,
  ADD COLUMN upkeep_unpaid_cycles INT UNSIGNED NOT NULL DEFAULT 0;

CREATE TABLE realm_invites (
  realm_id    BIGINT UNSIGNED NOT NULL,
  player_uuid BINARY(16)   NOT NULL,
  invited_at  DATETIME     NOT NULL,
  PRIMARY KEY (realm_id, player_uuid),
  FOREIGN KEY (realm_id) REFERENCES realms(id) ON DELETE CASCADE
);
