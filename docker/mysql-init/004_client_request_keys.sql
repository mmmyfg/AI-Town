USE ai_town_db;

-- Durable request idempotency. NULL/empty client IDs continue through the legacy path.
CREATE TABLE IF NOT EXISTS client_request_keys (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  player_id VARCHAR(64) NOT NULL,
  client_request_id VARCHAR(128) NOT NULL,
  task_id VARCHAR(64) NOT NULL,
  request_fingerprint CHAR(64) NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_player_request (player_id, client_request_id),
  KEY idx_client_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Rollback requires dropping this table, which also removes replay mappings.
