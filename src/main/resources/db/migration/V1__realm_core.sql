CREATE TABLE realms (
  id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  name          VARCHAR(32)  NOT NULL UNIQUE,
  display_name  VARCHAR(48)  NOT NULL,
  leader_uuid   BINARY(16)   NOT NULL,
  level         INT UNSIGNED NOT NULL DEFAULT 1,
  created_at    DATETIME     NOT NULL,
  disbanded_at  DATETIME     NULL,
  INDEX idx_leader (leader_uuid)
);

CREATE TABLE realm_ranks (
  id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  realm_id      BIGINT UNSIGNED NOT NULL,
  name          VARCHAR(24)  NOT NULL,
  priority      INT          NOT NULL,
  permissions   BIGINT UNSIGNED NOT NULL,
  is_default    BOOLEAN      NOT NULL DEFAULT FALSE,
  UNIQUE KEY uq_realm_rank_name (realm_id, name),
  FOREIGN KEY (realm_id) REFERENCES realms(id) ON DELETE CASCADE
);

CREATE TABLE realm_members (
  realm_id      BIGINT UNSIGNED NOT NULL,
  player_uuid   BINARY(16)   NOT NULL,
  rank_id       BIGINT UNSIGNED NOT NULL,
  joined_at     DATETIME     NOT NULL,
  PRIMARY KEY (realm_id, player_uuid),
  UNIQUE KEY uq_player_one_realm (player_uuid),
  FOREIGN KEY (realm_id) REFERENCES realms(id) ON DELETE CASCADE,
  FOREIGN KEY (rank_id) REFERENCES realm_ranks(id)
);
