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

[[ "${target_database}" =~ ^[A-Za-z0-9_]{1,64}$ ]] \
  || fail "new database name must contain only letters, digits and underscore"
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

if command -v sha256sum >/dev/null 2>&1; then
  (cd "${backup_dir}" && sha256sum --check --status "$(basename -- "${checksum_file}")") \
    || fail "backup checksum verification failed"
elif command -v shasum >/dev/null 2>&1; then
  (cd "${backup_dir}" && shasum -a 256 --check --status \
    "$(basename -- "${checksum_file}")") \
    || fail "backup checksum verification failed"
else
  fail "sha256sum or shasum is required"
fi

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
flyway_history_count="$("${mysql_bin}" --defaults-extra-file="${credentials_file}" \
  --batch --skip-column-names \
  --execute="SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '${target_database}' AND table_name = 'flyway_schema_history'")"
[[ "${table_count}" =~ ^[0-9]+$ && "${table_count}" -gt 0 ]] \
  || fail "restored database contains no tables"
[[ "${flyway_history_count}" == "1" ]] \
  || fail "restored database is missing Flyway history"

completed=true
printf 'Restored %s into new database %s with %s tables.\n' \
  "${backup_file}" "${target_database}" "${table_count}"
