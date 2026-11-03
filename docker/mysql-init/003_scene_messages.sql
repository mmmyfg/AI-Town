-- Apply to existing databases before enabling /api/chat/scene.
-- The original unique key only allowed one assistant message per task.
USE ai_town_db;
ALTER TABLE conversation_messages
  DROP INDEX uk_task_role,
  ADD UNIQUE KEY uk_task_role_npc (task_id, role, npc_id);
-- Rollback after removing scene data:
-- ALTER TABLE conversation_messages DROP INDEX uk_task_role_npc,
--   ADD UNIQUE KEY uk_task_role (task_id, role);
