#!/usr/bin/env bash

set -Eeuo pipefail

fail() {
  printf 'backup-mysql: %s\n' "$1" >&2
  exit 1
}

database="${MYSQL_DATABASE:-after_school_service}"
backup_dir="${BACKUP_DIR:-/var/backups/after-school-service}"
retention_days="${BACKUP_RETENTION_DAYS:-30}"
credentials_file="${MYSQL_DEFAULTS_FILE:-${CREDENTIALS_DIRECTORY:-}/mysql-client.cnf}"
recipients_file="${AGE_RECIPIENTS_FILE:-${CREDENTIALS_DIRECTORY:-}/age-recipients}"
upload_hook="${BACKUP_UPLOAD_HOOK:-}"
lock_file="${BACKUP_LOCK_FILE:-${RUNTIME_DIRECTORY:-${backup_dir}}/backup.lock}"
mysqldump_bin="${AFTER_SCHOOL_MYSQLDUMP_BIN:-mysqldump}"
age_bin="${AFTER_SCHOOL_AGE_BIN:-age}"
gzip_bin="${AFTER_SCHOOL_GZIP_BIN:-gzip}"

[[ "${database}" =~ ^[A-Za-z0-9_]{1,64}$ ]] \
  || fail "MYSQL_DATABASE must contain only letters, digits and underscore"
[[ "${backup_dir}" == /* && "${backup_dir}" != "/" ]] \
  || fail "BACKUP_DIR must be a specific absolute directory"
[[ "${retention_days}" =~ ^[0-9]+$ && "${retention_days}" -le 3650 ]] \
  || fail "BACKUP_RETENTION_DAYS must be between 0 and 3650"
[[ "${lock_file}" == /* ]] || fail "backup lock file must be absolute"
[[ -f "${credentials_file}" && ! -L "${credentials_file}" ]] \
  || fail "MySQL defaults file must be a regular non-symlink file"
[[ -f "${recipients_file}" && ! -L "${recipients_file}" ]] \
  || fail "age recipients file must be a regular non-symlink file"
private_mode="$(stat -c '%a' "${credentials_file}" 2>/dev/null \
  || stat -f '%Lp' "${credentials_file}" 2>/dev/null \
  || true)"
private_mode="${private_mode: -3}"
[[ "${private_mode}" =~ ^[0-7]{3}$ ]] \
  || fail "could not determine MySQL defaults file permissions"
(( (8#${private_mode} & 077) == 0 )) \
  || fail "MySQL defaults file must not be readable by group or other users"
grep -Eiq '^[[:space:]]*password[[:space:]]*=[[:space:]]*[^[:space:]]+' \
  "${credentials_file}" \
  || fail "MySQL defaults file must contain a non-empty password"
grep -Eiq '^[[:space:]]*ssl-mode[[:space:]]*=[[:space:]]*VERIFY_IDENTITY[[:space:]]*$' \
  "${credentials_file}" \
  || fail "MySQL defaults file must enforce ssl-mode=VERIFY_IDENTITY"
grep -Eiq '^[[:space:]]*ssl-ca[[:space:]]*=[[:space:]]*[^[:space:]]+' \
  "${credentials_file}" \
  || fail "MySQL defaults file must name a CA certificate"
grep -Eq '^[[:space:]]*(age1|ssh-(rsa|ed25519))[A-Za-z0-9+/=_-]*[[:space:]]*$' \
  "${recipients_file}" \
  || fail "age recipients file must contain at least one public recipient"
if [[ -n "${upload_hook}" ]]; then
  [[ "${upload_hook}" == /* && -f "${upload_hook}" && -x "${upload_hook}" \
      && ! -L "${upload_hook}" ]] \
    || fail "BACKUP_UPLOAD_HOOK must be an absolute executable non-symlink file"
fi

for required_bin in "${mysqldump_bin}" "${age_bin}" "${gzip_bin}" flock; do
  command -v "${required_bin}" >/dev/null 2>&1 \
    || fail "required command not found: ${required_bin}"
done
"${mysqldump_bin}" --version | grep -Eq 'Ver[[:space:]]+8\.4\.' \
  || fail "mysqldump 8.4 is required"
"${age_bin}" --version >/dev/null 2>&1 || fail "age command is unavailable"

install -d -m 0700 "${backup_dir}" "$(dirname -- "${lock_file}")"
exec 9>"${lock_file}"
flock -n 9 || fail "another backup is already running"

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
filename="${database}-${timestamp}.sql.gz.age"
final_backup="${backup_dir}/${filename}"
final_checksum="${final_backup}.sha256"
partial_backup="${backup_dir}/.${filename}.partial.$$"
partial_checksum="${backup_dir}/.${filename}.sha256.partial.$$"
[[ ! -e "${final_backup}" && ! -L "${final_backup}" ]] \
  || fail "backup already exists for timestamp ${timestamp}"

cleanup() {
  [[ ! -e "${partial_backup}" && ! -L "${partial_backup}" ]] \
    || unlink "${partial_backup}"
  [[ ! -e "${partial_checksum}" && ! -L "${partial_checksum}" ]] \
    || unlink "${partial_checksum}"
}
trap cleanup EXIT

"${mysqldump_bin}" \
  --defaults-extra-file="${credentials_file}" \
  --single-transaction \
  --quick \
  --routines \
  --events \
  --triggers \
  --hex-blob \
  --no-tablespaces \
  --set-gtid-purged=OFF \
  --default-character-set=utf8mb4 \
  --tz-utc \
  "${database}" \
  | "${gzip_bin}" -9 \
  | "${age_bin}" --encrypt --recipients-file "${recipients_file}" \
      --output "${partial_backup}"

[[ -s "${partial_backup}" ]] || fail "encrypted backup is empty"
chmod 0600 "${partial_backup}"
if command -v sha256sum >/dev/null 2>&1; then
  digest="$(sha256sum "${partial_backup}" | awk '{print $1}')"
elif command -v shasum >/dev/null 2>&1; then
  digest="$(shasum -a 256 "${partial_backup}" | awk '{print $1}')"
else
  fail "sha256sum or shasum is required"
fi
printf '%s  %s\n' "${digest}" "${filename}" >"${partial_checksum}"
chmod 0600 "${partial_checksum}"

mv -- "${partial_backup}" "${final_backup}"
mv -- "${partial_checksum}" "${final_checksum}"

if [[ -n "${upload_hook}" ]]; then
  "${upload_hook}" "${final_backup}" "${final_checksum}" \
    || fail "local backup succeeded but off-host upload hook failed"
fi

find "${backup_dir}" -mindepth 1 -maxdepth 1 -type f \
  \( -name "${database}-*.sql.gz.age" -o -name "${database}-*.sql.gz.age.sha256" \) \
  -mtime "+${retention_days}" -delete

printf 'Created encrypted backup %s with checksum %s.\n' \
  "${final_backup}" "${final_checksum}"
