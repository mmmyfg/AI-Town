-- Apply to existing databases before enabling SCENE_ROUNDS > 1.
USE ai_town_db;
ALTER TABLE conversation_messages
  ADD COLUMN turn_no INT NOT NULL DEFAULT 1,
  DROP INDEX uk_task_role_npc,
  ADD UNIQUE KEY uk_task_role_npc_turn (task_id, role, npc_id, turn_no);
