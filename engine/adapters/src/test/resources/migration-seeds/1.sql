-- 版 1 のスキーマに入れる行。その版のテーブルをすべて埋める。作成した後は変更しない
INSERT INTO utterance (started_at_ms, ended_at_ms, body, confidence, created_at_ms)
VALUES (1000, 2000, '信頼度のある発話', 0.75, 2500);

INSERT INTO utterance (started_at_ms, ended_at_ms, body, confidence, created_at_ms)
VALUES (3000, 4000, '信頼度のない発話', NULL, 4500);

INSERT INTO capture_gap (started_at_ms, ended_at_ms, reason)
VALUES (5000, 6000, 'processing_backlog');
