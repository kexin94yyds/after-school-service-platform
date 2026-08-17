#!/usr/bin/env bash

set -Eeuo pipefail

fail() {
  printf 'monitor-health: %s\n' "$1" >&2
  exit 1
}

health_url="${HEALTH_URL:-http://127.0.0.1:8081/readyz}"
service_name="${SERVICE_NAME:-after-school-service.service}"
backup_service="${BACKUP_SERVICE_NAME:-after-school-backup.service}"
backup_timer="${BACKUP_TIMER_NAME:-after-school-backup.timer}"
database="${MYSQL_DATABASE:-after_school_service}"
backup_dir="${BACKUP_DIR:-/var/backups/after-school-service}"
backup_max_age_hours="${BACKUP_MAX_AGE_HOURS:-18}"
state_file="${MONITOR_STATE_FILE:-/var/lib/after-school-healthcheck/state}"
alert_hook="${HEALTH_ALERT_HOOK:-}"
systemctl_bin="${AFTER_SCHOOL_SYSTEMCTL_BIN:-systemctl}"
curl_bin="${AFTER_SCHOOL_CURL_BIN:-curl}"

[[ "${health_url}" =~ ^http://127\.0\.0\.1:[0-9]+/readyz$ ]] \
  || fail "HEALTH_URL must be the loopback /readyz endpoint"
[[ "${service_name}" =~ ^[A-Za-z0-9@_.-]+\.service$ ]] \
  || fail "SERVICE_NAME must be a systemd .service unit"
[[ "${backup_service}" =~ ^[A-Za-z0-9@_.-]+\.service$ ]] \
  || fail "BACKUP_SERVICE_NAME must be a systemd .service unit"
[[ "${backup_timer}" =~ ^[A-Za-z0-9@_.-]+\.timer$ ]] \
  || fail "BACKUP_TIMER_NAME must be a systemd .timer unit"
[[ "${database}" =~ ^[A-Za-z0-9_]{1,64}$ ]] \
  || fail "MYSQL_DATABASE must contain only letters, digits and underscore"
[[ "${backup_dir}" == /* && "${backup_dir}" != "/" ]] \
  || fail "BACKUP_DIR must be a specific absolute directory"
[[ "${backup_max_age_hours}" =~ ^[0-9]+$ && "${backup_max_age_hours}" -ge 1 \
    && "${backup_max_age_hours}" -le 720 ]] \
  || fail "BACKUP_MAX_AGE_HOURS must be between 1 and 720"
[[ "${state_file}" == /* ]] || fail "MONITOR_STATE_FILE must be absolute"
for required_bin in "${systemctl_bin}" "${curl_bin}"; do
  command -v "${required_bin}" >/dev/null 2>&1 \
    || fail "required command not found: ${required_bin}"
done

if [[ -n "${alert_hook}" ]]; then
  [[ "${alert_hook}" == /* && -f "${alert_hook}" && -x "${alert_hook}" \
      && ! -L "${alert_hook}" ]] \
    || fail "HEALTH_ALERT_HOOK must be an absolute executable non-symlink file"
  hook_mode="$(stat -c '%a' "${alert_hook}" 2>/dev/null \
    || stat -f '%Lp' "${alert_hook}" 2>/dev/null \
    || true)"
  hook_owner="$(stat -c '%u' "${alert_hook}" 2>/dev/null \
    || stat -f '%u' "${alert_hook}" 2>/dev/null \
    || true)"
  hook_mode="${hook_mode: -3}"
  [[ "${hook_owner}" == "0" ]] \
    || fail "HEALTH_ALERT_HOOK must be owned by root"
  [[ "${hook_mode}" =~ ^[0-7]{3}$ ]] \
    || fail "could not determine alert hook permissions"
  (( (8#${hook_mode} & 022) == 0 )) \
    || fail "HEALTH_ALERT_HOOK must not be writable by group or other users"
fi

latest_database_backup() {
  local candidate=""
  local latest=""
  while IFS= read -r -d '' candidate; do
    if [[ -z "${latest}" || "${candidate}" > "${latest}" ]]; then
      latest="${candidate}"
    fi
  done < <(find "${backup_dir}" -mindepth 1 -maxdepth 1 -type f \
      -name "${database}-*.sql.gz.age" -print0)
  printf '%s' "${latest}"
}

verify_backup_checksum() {
  local backup_path="$1"
  local checksum_path="${backup_path}.sha256"
  local backup_name="$(basename -- "${backup_path}")"
  local checksum_name="$(basename -- "${checksum_path}")"
  local checksum_record=""
  local expected_length=0
  local checksum_digest=""
  local checksum_target=""

  [[ -s "${checksum_path}" && ! -L "${checksum_path}" ]] || return 1
  checksum_record="$(<"${checksum_path}")"
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

failures=()
"${systemctl_bin}" is-active --quiet "${service_name}" \
  || failures+=("backend service is not active")
"${systemctl_bin}" is-active --quiet "${backup_timer}" \
  || failures+=("backup timer is not active")
if "${systemctl_bin}" is-failed --quiet "${backup_service}"; then
  failures+=("latest backup service run failed")
fi

health_body="$("${curl_bin}" --fail --silent --show-error --max-time 3 \
  "${health_url}" 2>/dev/null || true)"
grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' <<<"${health_body}" \
  || failures+=("readiness is not UP")

latest_backup=""
if [[ -d "${backup_dir}" ]]; then
  latest_backup="$(latest_database_backup)"
fi
if [[ -z "${latest_backup}" ]]; then
  failures+=("no encrypted backup exists for MYSQL_DATABASE=${database}")
else
  freshness_minutes=$((backup_max_age_hours * 60))
  [[ -n "$(find "${latest_backup}" -maxdepth 0 -type f \
      -mmin "-${freshness_minutes}" -print -quit)" ]] \
    || failures+=("latest encrypted backup is stale")
  verify_backup_checksum "${latest_backup}" \
    || failures+=("latest encrypted backup checksum is missing, malformed or invalid")
fi

previous_state="UNKNOWN"
if [[ -f "${state_file}" && ! -L "${state_file}" ]]; then
  previous_state="$(sed -n '1p' "${state_file}")"
fi

if [[ "${#failures[@]}" -eq 0 ]]; then
  current_state="HEALTHY"
  event_state="RECOVERED"
  message="backend readiness, backup timer and ${database} backup freshness/checksum are healthy"
else
  current_state="FAILED"
  event_state="FAILED"
  message="$(IFS='; '; printf '%s' "${failures[*]}")"
fi

state_updated=true
if [[ "${current_state}" != "${previous_state}" && -n "${alert_hook}" ]]; then
  "${alert_hook}" "${event_state}" "${message}" || state_updated=false
fi

if [[ "${state_updated}" == "true" ]]; then
  install -d -m 0700 "$(dirname -- "${state_file}")"
  partial_state="${state_file}.partial.$$"
  printf '%s\n' "${current_state}" >"${partial_state}"
  chmod 0600 "${partial_state}"
  mv -- "${partial_state}" "${state_file}"
fi

if [[ "${current_state}" == "FAILED" ]]; then
  fail "${message}"
fi
printf 'monitor-health: %s\n' "${message}"
