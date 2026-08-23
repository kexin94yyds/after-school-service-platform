#!/usr/bin/env bash

set -Eeuo pipefail

usage() {
  cat >&2 <<'EOF'
Usage: restore-mysql-backup.sh <backup.sql.gz.age> <new-database> <mysql-defaults-file> <age-identity-file>

The target database must not already exist. On an import or validation failure,
the newly created partial target database is removed.
EOF
  exit 2
}

fail() {
  printf 'restore-mysql-backup: %s\n' "$1" >&2
  exit 1
}

[[ "$#" == 4 ]] || usage
[[ -f "$1" && ! -L "$1" ]] || fail "backup must be a regular non-symlink file"
backup_dir="$(cd -- "$(dirname -- "$1")" 2>/dev/null && pwd -P)" \
  || fail "backup parent directory does not exist"
backup_file="${backup_dir}/$(basename -- "$1")"
target_database="$2"
credentials_file="$3"
identity_file="$4"
checksum_file="${backup_file}.sha256"
mysql_bin="${AFTER_SCHOOL_MYSQL_BIN:-mysql}"
age_bin="${AFTER_SCHOOL_AGE_BIN:-age}"
gzip_bin="${AFTER_SCHOOL_GZIP_BIN:-gzip}"
required_flyway_version="${RESTORE_REQUIRED_FLYWAY_VERSION:-18}"

[[ "${target_database}" =~ ^[A-Za-z0-9_]{1,64}$ ]] \
  || fail "new database name must contain only letters, digits and underscore"
[[ "${required_flyway_version}" =~ ^[0-9]+(\.[0-9]+)*$ ]] \
  || fail "RESTORE_REQUIRED_FLYWAY_VERSION must be a numeric Flyway version"
[[ -f "${checksum_file}" && ! -L "${checksum_file}" ]] \
  || fail "adjacent SHA-256 checksum file is required"
[[ -f "${credentials_file}" && ! -L "${credentials_file}" ]] \
  || fail "MySQL defaults file must be a regular non-symlink file"
[[ -f "${identity_file}" && ! -L "${identity_file}" ]] \
  || fail "age identity must be a regular non-symlink file"
for private_file in "${credentials_file}" "${identity_file}"; do
  private_mode="$(stat -c '%a' "${private_file}" 2>/dev/null \
    || stat -f '%Lp' "${private_file}" 2>/dev/null \
    || true)"
  private_mode="${private_mode: -3}"
  [[ "${private_mode}" =~ ^[0-7]{3}$ ]] \
    || fail "could not determine credential file permissions"
  (( (8#${private_mode} & 077) == 0 )) \
    || fail "credential and identity files must not be readable by group or other users"
done
grep -Eiq '^[[:space:]]*password[[:space:]]*=[[:space:]]*[^[:space:]]+' \
  "${credentials_file}" \
  || fail "MySQL defaults file must contain a non-empty password"
grep -Eiq '^[[:space:]]*ssl-mode[[:space:]]*=[[:space:]]*VERIFY_IDENTITY[[:space:]]*$' \
  "${credentials_file}" \
  || fail "MySQL defaults file must enforce ssl-mode=VERIFY_IDENTITY"
grep -Eiq '^[[:space:]]*ssl-ca[[:space:]]*=[[:space:]]*[^[:space:]]+' \
  "${credentials_file}" \
  || fail "MySQL defaults file must name a CA certificate"
for required_bin in "${mysql_bin}" "${age_bin}" "${gzip_bin}"; do
  command -v "${required_bin}" >/dev/null 2>&1 \
    || fail "required command not found: ${required_bin}"
done
"${mysql_bin}" --version | grep -Eq 'Ver[[:space:]]+8\.4\.' \
  || fail "mysql client 8.4 is required"
"${age_bin}" --version >/dev/null 2>&1 || fail "age command is unavailable"

verify_backup_checksum() {
  local backup_name="$(basename -- "${backup_file}")"
  local checksum_name="$(basename -- "${checksum_file}")"
  local checksum_record=""
  local checksum_line_count=""
  local expected_length=0
  local checksum_digest=""
  local checksum_target=""

  # A checksum sidecar that validates a different, intact backup is not an
  # acceptable integrity proof for the requested restore input. Require the
  # standard single sha256sum record and bind its filename to backup_file
  # before delegating the digest calculation to the platform tool.
  checksum_line_count="$(awk 'END { print NR + 0 }' "${checksum_file}")"
  [[ "${checksum_line_count}" == "1" ]] || return 1
  checksum_record="$(<"${checksum_file}")"
  expected_length=$((66 + ${#backup_name}))
  [[ "${#checksum_record}" -eq "${expected_length}" ]] || return 1
  checksum_digest="${checksum_record:0:64}"
  checksum_target="${checksum_record:66}"
  [[ "${checksum_digest}" =~ ^[[:xdigit:]]{64}$ \
      && "${checksum_record:64:2}" == "  " \
      && "${checksum_target}" == "${backup_name}" ]] || return 1

  if command -v sha256sum >/dev/null 2>&1; then
    (cd "${backup_dir}" && sha256sum --check --status "${checksum_name}")
  elif command -v shasum >/dev/null 2>&1; then
    (cd "${backup_dir}" && shasum -a 256 --check --status "${checksum_name}")
  else
    return 1
  fi
}

verify_backup_checksum || fail "backup checksum is missing, malformed, points to another file or fails verification"

schema_exists="$("${mysql_bin}" --defaults-extra-file="${credentials_file}" \
  --batch --skip-column-names \
  --execute="SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = '${target_database}'")"
[[ "${schema_exists}" == "0" ]] \
  || fail "target database already exists; restore requires a new database"

created=false
completed=false
cleanup() {
  local exit_status=$?
  trap - EXIT
  if [[ "${created}" == "true" && "${completed}" != "true" ]]; then
    "${mysql_bin}" --defaults-extra-file="${credentials_file}" \
      --execute="DROP DATABASE IF EXISTS \`${target_database}\`" \
      >/dev/null 2>&1 \
      || printf 'restore-mysql-backup: warning: failed to remove partial database %s\n' \
        "${target_database}" >&2
  fi
  exit "${exit_status}"
}
trap cleanup EXIT

"${mysql_bin}" --defaults-extra-file="${credentials_file}" \
  --execute="CREATE DATABASE \`${target_database}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
created=true

"${age_bin}" --decrypt --identity "${identity_file}" "${backup_file}" \
  | "${gzip_bin}" --decompress --stdout \
  | "${mysql_bin}" --defaults-extra-file="${credentials_file}" \
      --binary-mode --database="${target_database}"

table_count="$("${mysql_bin}" --defaults-extra-file="${credentials_file}" \
  --batch --skip-column-names \
  --execute="SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '${target_database}'")"
flyway_summary="$("${mysql_bin}" --defaults-extra-file="${credentials_file}" \
  --batch --skip-column-names \
  --execute="SELECT CONCAT(
      COUNT(*), ':',
      COALESCE(SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END), 0), ':',
      COALESCE(SUM(CASE WHEN success = 1 AND version = '${required_flyway_version}' THEN 1 ELSE 0 END), 0)
    )
    FROM \`${target_database}\`.\`flyway_schema_history\`")"
core_table_count="$("${mysql_bin}" --defaults-extra-file="${credentials_file}" \
  --batch --skip-column-names \
  --execute="SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = '${target_database}'
      AND table_type = 'BASE TABLE'
      AND table_name IN (
        'school', 'sys_user', 'course', 'course_offering', 'student',
        'enrollment', 'lesson_session', 'attendance', 'supervision_alert',
        'supervision_scan_run', 'operation_audit', 'course_evaluation',
        'service_plan_item', 'enrollment_action', 'regulator_school_scope',
        'regulator_notification', 'rectification_notice',
        'rectification_material', 'student_grade', 'student_grade_revision'
      )")"
required_fk_count="$("${mysql_bin}" --defaults-extra-file="${credentials_file}" \
  --batch --skip-column-names \
  --execute="SELECT COUNT(*)
    FROM information_schema.referential_constraints
    WHERE constraint_schema = '${target_database}'
      AND table_name IN (
        'enrollment', 'supervision_alert', 'service_plan_item',
        'enrollment_action', 'regulator_notification',
        'rectification_notice', 'rectification_material',
        'student_grade', 'student_grade_revision'
      )
      AND constraint_name IN (
        'fk_enrollment_offering_school',
        'fk_enrollment_student_school',
        'fk_supervision_alert_scan_run',
        'fk_plan_item_plan_school',
        'fk_enrollment_action_identity',
        'fk_regulator_notification_alert_school',
        'fk_rectification_notice_alert_school',
        'fk_rectification_material_notice_school',
        'fk_student_grade_enrollment',
        'fk_grade_revision_grade'
      )")"
scan_run_orphan_count="$("${mysql_bin}" --defaults-extra-file="${credentials_file}" \
  --batch --skip-column-names \
  --execute="SELECT COUNT(*)
    FROM \`${target_database}\`.\`supervision_alert\` AS alert
    LEFT JOIN \`${target_database}\`.\`supervision_scan_run\` AS scan_run
      ON scan_run.id = alert.scan_run_id
    WHERE scan_run.id IS NULL")"
[[ "${table_count}" =~ ^[0-9]+$ && "${table_count}" -gt 0 ]] \
  || fail "restored database contains no tables"
IFS=':' read -r flyway_history_count failed_migration_count required_version_count \
  <<<"${flyway_summary}"
[[ "${flyway_history_count:-}" =~ ^[0-9]+$ \
    && "${failed_migration_count:-}" =~ ^[0-9]+$ \
    && "${required_version_count:-}" =~ ^[0-9]+$ \
    && "${flyway_history_count}" -gt 0 \
    && "${failed_migration_count}" == "0" \
    && "${required_version_count}" -ge 1 ]] \
  || fail "restored database Flyway history is incomplete, failed or predates required version ${required_flyway_version}"
[[ "${core_table_count}" == "19" ]] \
  || fail "restored database is missing one or more required core tables"
[[ "${required_fk_count}" == "10" ]] \
  || fail "restored database is missing required tenant or supervision foreign keys"
[[ "${scan_run_orphan_count}" == "0" ]] \
  || fail "restored database contains supervision alerts without a scan run"

completed=true
printf 'Restored %s into new database %s with %s tables and Flyway version %s.\n' \
  "${backup_file}" "${target_database}" "${table_count}" "${required_flyway_version}"
