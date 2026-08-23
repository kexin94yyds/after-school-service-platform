ALTER TABLE academic_term
    ADD COLUMN school_id BIGINT UNSIGNED NULL AFTER id,
    DROP INDEX uk_academic_term_code;

CREATE TABLE migration_term_school_pair (
    old_term_id BIGINT UNSIGNED NOT NULL,
    school_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (old_term_id, school_id)
) ENGINE=InnoDB;

INSERT IGNORE INTO migration_term_school_pair (old_term_id, school_id)
SELECT term_id, school_id FROM school_service_plan
UNION
SELECT term_id, school_id FROM course_offering WHERE term_id IS NOT NULL
UNION
SELECT term_id, school_id FROM school_calendar_event;

INSERT IGNORE INTO migration_term_school_pair (old_term_id, school_id)
SELECT term.id, COALESCE(creator.school_id, fallback_school.id)
FROM academic_term term
JOIN sys_user creator ON creator.id = term.created_by
LEFT JOIN school fallback_school
  ON fallback_school.id = (SELECT MIN(id) FROM school)
WHERE NOT EXISTS (
    SELECT 1
    FROM migration_term_school_pair pair
    WHERE pair.old_term_id = term.id
)
  AND COALESCE(creator.school_id, fallback_school.id) IS NOT NULL;

UPDATE academic_term term
JOIN (
    SELECT old_term_id, MIN(school_id) AS school_id
    FROM migration_term_school_pair
    GROUP BY old_term_id
) primary_pair ON primary_pair.old_term_id = term.id
SET term.school_id = primary_pair.school_id;

INSERT INTO academic_term (
    school_id,
    term_code,
    term_name,
    start_date,
    end_date,
    status,
    created_by,
    created_at,
    updated_at
)
SELECT
    pair.school_id,
    original.term_code,
    original.term_name,
    original.start_date,
    original.end_date,
    original.status,
    original.created_by,
    original.created_at,
    original.updated_at
FROM migration_term_school_pair pair
JOIN academic_term original ON original.id = pair.old_term_id
WHERE pair.school_id <> original.school_id;

CREATE TABLE migration_term_school_map (
    old_term_id BIGINT UNSIGNED NOT NULL,
    school_id BIGINT UNSIGNED NOT NULL,
    new_term_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (old_term_id, school_id),
    UNIQUE KEY uk_migration_term_new (new_term_id)
) ENGINE=InnoDB;

INSERT INTO migration_term_school_map (old_term_id, school_id, new_term_id)
SELECT pair.old_term_id, pair.school_id, scoped.id
FROM migration_term_school_pair pair
JOIN academic_term original ON original.id = pair.old_term_id
JOIN academic_term scoped
  ON scoped.school_id = pair.school_id
 AND scoped.term_code = original.term_code;

ALTER TABLE course_offering
    DROP FOREIGN KEY fk_offering_plan_school_term,
    DROP FOREIGN KEY fk_offering_term;

ALTER TABLE school_service_plan
    DROP FOREIGN KEY fk_service_plan_term;

ALTER TABLE school_calendar_event
    DROP FOREIGN KEY fk_calendar_term;

UPDATE school_service_plan plan
JOIN migration_term_school_map mapping
  ON mapping.old_term_id = plan.term_id
 AND mapping.school_id = plan.school_id
SET plan.term_id = mapping.new_term_id;

UPDATE course_offering offering
JOIN migration_term_school_map mapping
  ON mapping.old_term_id = offering.term_id
 AND mapping.school_id = offering.school_id
SET offering.term_id = mapping.new_term_id;

UPDATE school_calendar_event event
JOIN migration_term_school_map mapping
  ON mapping.old_term_id = event.term_id
 AND mapping.school_id = event.school_id
SET event.term_id = mapping.new_term_id;

ALTER TABLE academic_term
    MODIFY COLUMN school_id BIGINT UNSIGNED NOT NULL,
    ADD UNIQUE KEY uk_academic_term_school_code (school_id, term_code),
    ADD UNIQUE KEY uk_academic_term_school_id (school_id, id),
    DROP INDEX idx_academic_term_status_date,
    ADD KEY idx_academic_term_status_date (
        school_id, status, start_date, end_date
    ),
    ADD CONSTRAINT fk_academic_term_school
        FOREIGN KEY (school_id) REFERENCES school (id);

ALTER TABLE school_service_plan
    ADD CONSTRAINT fk_service_plan_term_school
        FOREIGN KEY (school_id, term_id)
        REFERENCES academic_term (school_id, id);

ALTER TABLE school_calendar_event
    ADD CONSTRAINT fk_calendar_term_school
        FOREIGN KEY (school_id, term_id)
        REFERENCES academic_term (school_id, id);

ALTER TABLE course_offering
    ADD CONSTRAINT fk_offering_term_school
        FOREIGN KEY (school_id, term_id)
        REFERENCES academic_term (school_id, id),
    ADD CONSTRAINT fk_offering_plan_school_term
        FOREIGN KEY (school_id, term_id, plan_id)
        REFERENCES school_service_plan (school_id, term_id, id);

UPDATE sys_role
SET enabled = FALSE
WHERE role_code = 'REGULATOR';

DROP TABLE migration_term_school_map;
DROP TABLE migration_term_school_pair;
