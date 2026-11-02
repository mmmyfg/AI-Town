USE ai_town_db;

CREATE TABLE IF NOT EXISTS conversations (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  conversation_id VARCHAR(64) NOT NULL,
  player_id VARCHAR(64) NOT NULL,
  npc_id VARCHAR(64) NOT NULL,
  last_sequence BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_conversation_id (conversation_id),
  UNIQUE KEY uk_player_npc (player_id, npc_id),
  KEY idx_conversation_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS conversation_messages (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  conversation_id VARCHAR(64) NOT NULL,
  task_id VARCHAR(64) NOT NULL,
  player_id VARCHAR(64) NOT NULL,
  npc_id VARCHAR(64) NOT NULL,
  sequence_no BIGINT NOT NULL,
  role VARCHAR(16) NOT NULL,
  content TEXT NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_task_role (task_id, role),
  UNIQUE KEY uk_conversation_sequence (conversation_id, sequence_no),
  KEY idx_message_session_time (player_id, npc_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS player_npc_relations (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  player_id VARCHAR(64) NOT NULL,
  npc_id VARCHAR(64) NOT NULL,
  affinity INT NOT NULL DEFAULT 50,
  mood VARCHAR(32) NOT NULL DEFAULT 'CALM',
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_player_npc_relation (player_id, npc_id),
  KEY idx_relation_npc (npc_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS chat_tasks (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id VARCHAR(64) NOT NULL,
  conversation_id VARCHAR(64) NOT NULL,
  player_id VARCHAR(64) NOT NULL,
  npc_id VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  request_payload JSON NOT NULL,
  reply TEXT NULL,
  affinity_change INT NOT NULL DEFAULT 0,
  current_affinity INT NULL,
  mood VARCHAR(32) NOT NULL DEFAULT 'CALM',
  retry_count INT NOT NULL DEFAULT 0,
  last_error VARCHAR(1000) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  started_at TIMESTAMP(3) NULL,
  completed_at TIMESTAMP(3) NULL,
  UNIQUE KEY uk_chat_task_id (task_id),
  KEY idx_task_recovery (status, updated_at),
  KEY idx_task_session (player_id, npc_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS task_outbox (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id VARCHAR(64) NOT NULL,
  task_id VARCHAR(64) NOT NULL,
  exchange_name VARCHAR(128) NOT NULL,
  routing_key VARCHAR(128) NOT NULL,
  payload JSON NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  retry_count INT NOT NULL DEFAULT 0,
  last_error VARCHAR(1000) NULL,
  next_retry_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  published_at TIMESTAMP(3) NULL,
  UNIQUE KEY uk_outbox_event_id (event_id),
  KEY idx_outbox_publish (status, next_retry_at),
  KEY idx_outbox_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
