-- The historical V8 demo migration inserts this fixed alert before V12 creates
-- supervision_scan_run. When a database has already reached V13 and demo is
-- enabled afterwards, create the parent first so V8 remains compatible with
-- the new foreign key. On a fresh schema the table does not yet exist, so this
-- migration intentionally becomes a no-op and V13 backfills it later.
SET @demo_scan_run_parent_sql = (
    SELECT IF(
        EXISTS (
            SELECT 1
            FROM information_schema.tables
            WHERE table_schema = DATABASE()
              AND table_name = 'supervision_scan_run'
        ),
        'INSERT INTO supervision_scan_run (id, trigger_source, status, started_at, finished_at, candidate_count, created_count, failure_summary, operator_user_id) VALUES (''11111111-1111-4111-8111-111111111111'', ''SCHEDULED'', ''SUCCESS'', ''2026-07-22 08:00:00.000'', ''2026-07-22 08:00:00.000'', 1, 1, NULL, NULL) ON DUPLICATE KEY UPDATE id = VALUES(id)',
        'SELECT 1'
    )
);
PREPARE demo_scan_run_parent_statement FROM @demo_scan_run_parent_sql;
EXECUTE demo_scan_run_parent_statement;
DEALLOCATE PREPARE demo_scan_run_parent_statement;
