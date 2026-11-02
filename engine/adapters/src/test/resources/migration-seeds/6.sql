-- 版 6 のスキーマに入れる行。その版のテーブルをすべて埋める。作成した後は変更しない
INSERT INTO speaker (id, display_name, name_source, is_self, created_at_ms)
VALUES (1, '自分', 'self', 1, 500);

INSERT INTO speaker (id, display_name, name_source, is_self, created_at_ms)
VALUES (2, '話者1', 'provisional', 0, 600);

INSERT INTO speaker_embedding (speaker_id, model_version, vector, sample_count, updated_at_ms, voiced_ms)
VALUES (1, 'fake-1', X'0000803F00000000', 3, 500, 0);

INSERT INTO speaker_embedding (speaker_id, model_version, vector, sample_count, updated_at_ms, voiced_ms)
VALUES (2, 'fake-1', X'000000000000803F', 1, 600, 4000);

INSERT INTO self_voice_adaptation (model_version, vector, voiced_ms, updated_at_ms)
VALUES ('fake-1', X'0000803F00000000', 3000, 700);

INSERT INTO speaker_decision_stat (day_ms, decision, bucket, runs, voiced_ms)
VALUES (0, 'self', 12, 2, 5000);

INSERT INTO utterance (id, started_at_ms, ended_at_ms, body, confidence, created_at_ms, organized_at_ms, speaker_id, speaker_confidence)
VALUES (1, 1000, 2000, '処理済みの発話', 0.75, 2500, 9000, 1, 0.8);

INSERT INTO utterance (id, started_at_ms, ended_at_ms, body, confidence, created_at_ms, organized_at_ms, speaker_id, speaker_confidence)
VALUES (2, 3000, 4000, '未処理の発話', NULL, 4500, NULL, 2, NULL);

INSERT INTO utterance (id, started_at_ms, ended_at_ms, body, confidence, created_at_ms, organized_at_ms, speaker_id, speaker_confidence)
VALUES (3, 5000, 6000, '話者が不明の発話', NULL, 6500, NULL, NULL, NULL);

INSERT INTO capture_gap (started_at_ms, ended_at_ms, reason)
VALUES (5000, 6000, 'processing_backlog');

INSERT INTO memo (id, started_at_ms, ended_at_ms, title, summary, todo_extraction, model_version, created_at_ms)
VALUES (1, 1000, 2000, '題名', '- 要約', 'complete', 'fake-1', 9000);

INSERT INTO memo_utterance (memo_id, utterance_id)
VALUES (1, 1);

INSERT INTO todo (memo_id, task, title, kind, due_date, status, created_at_ms, completed_at_ms, completed_from_status)
VALUES (1, '期限のある作業', '期限の作業', 'reminder', '2026-11-05', 'open', 9000, NULL, NULL);

INSERT INTO todo (memo_id, task, title, kind, due_date, status, created_at_ms, completed_at_ms, completed_from_status)
VALUES (1, '完了した作業', '完了の作業', 'todo', NULL, 'done', 9000, 9500, 'candidate');
