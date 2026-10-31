-- 版 2 のスキーマに入れる行。その版のテーブルをすべて埋める。作成した後は変更しない
INSERT INTO utterance (id, started_at_ms, ended_at_ms, body, confidence, created_at_ms, organized_at_ms)
VALUES (1, 1000, 2000, '処理済みの発話', 0.75, 2500, 9000);

INSERT INTO utterance (id, started_at_ms, ended_at_ms, body, confidence, created_at_ms, organized_at_ms)
VALUES (2, 3000, 4000, '未処理の発話', NULL, 4500, NULL);

INSERT INTO capture_gap (started_at_ms, ended_at_ms, reason)
VALUES (5000, 6000, 'processing_backlog');

INSERT INTO memo (id, started_at_ms, ended_at_ms, title, summary, todo_extraction, model_version, created_at_ms)
VALUES (1, 1000, 2000, '題名', '- 要約', 'complete', 'fake-1', 9000);

INSERT INTO memo (id, started_at_ms, ended_at_ms, title, summary, todo_extraction, model_version, created_at_ms)
VALUES (2, 3000, 4000, NULL, NULL, 'incomplete', 'fake-1', 9000);

INSERT INTO memo_utterance (memo_id, utterance_id)
VALUES (1, 1);

INSERT INTO todo (memo_id, task, kind, due_date, status, created_at_ms)
VALUES (1, '期限のある作業', 'reminder', '2026-11-05', 'open', 9000);

INSERT INTO todo (memo_id, task, kind, due_date, status, created_at_ms)
VALUES (1, '期限のない作業', 'todo', NULL, 'candidate', 9000);
