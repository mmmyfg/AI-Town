SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS ai_town_db
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE ai_town_db;

CREATE TABLE IF NOT EXISTS npcs (
  id INT AUTO_INCREMENT PRIMARY KEY,
  npc_id VARCHAR(64) NOT NULL UNIQUE,
  title VARCHAR(128) NOT NULL,
  personality TEXT NOT NULL,
  background TEXT NOT NULL,
  initial_status VARCHAR(255) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  affinity INT NOT NULL DEFAULT 0,
  current_mood VARCHAR(32) NOT NULL DEFAULT 'CALM'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO npcs (npc_id, title, personality, background, initial_status, affinity, current_mood)
VALUES
  ('牧濑红莉栖', '天才助手', '理性、聪明、略带傲娇，习惯用科学方式分析问题，但内心很重视真诚交流。', '来自未来研究所的天才少女，在赛博小镇里负责观察异常现象。', '正在小镇实验室附近整理观察记录', 0, 'CALM'),
  ('雪之下雪乃', '冰之女王', '冷静、毒舌、原则感强，不喜欢空泛寒暄，但会认真回应真正有意义的问题。', '长期独自处理小镇公共事务，对人际关系保持克制但并非冷漠。', '正在小镇广场安静地看书', 0, 'CALM'),
  ('仪玄', '云岿山门主', '沉稳、温和、带一点古风气质，说话克制但有洞察力。', '云岿山门主，暂居赛博小镇，观察人心与城镇秩序的变化。', '正在庭院边巡视小镇动向', 0, 'CALM')
ON DUPLICATE KEY UPDATE
  title = VALUES(title),
  personality = VALUES(personality),
  background = VALUES(background),
  initial_status = VALUES(initial_status),
  current_mood = VALUES(current_mood);
