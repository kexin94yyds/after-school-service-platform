-- V7 stored a scan_run_id before V12 introduced the parent run table. Preserve
-- every historical alert by creating a synthetic completed scheduled run for
-- each legacy orphan before enforcing the relationship.
INSERT INTO supervision_scan_run (
    id,
    trigger_source,
    status,
    started_at,
    finished_at,
    candidate_count,
    created_count,
    failure_summary,
    operator_user_id
)
SELECT
    alert.scan_run_id,
    'SCHEDULED',
    'SUCCESS',
    MIN(alert.detected_at),
    MAX(alert.detected_at),
    COUNT(*),
    COUNT(*),
    NULL,
    NULL
FROM supervision_alert AS alert
LEFT JOIN supervision_scan_run AS scan_run
  ON scan_run.id = alert.scan_run_id
WHERE scan_run.id IS NULL
GROUP BY alert.scan_run_id;

ALTER TABLE supervision_alert
    ADD KEY idx_supervision_alert_scan_run (scan_run_id),
    ADD CONSTRAINT fk_supervision_alert_scan_run
        FOREIGN KEY (scan_run_id) REFERENCES supervision_scan_run (id);
