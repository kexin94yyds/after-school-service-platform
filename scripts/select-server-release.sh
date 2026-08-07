#!/usr/bin/env bash

set -Eeuo pipefail

usage() {
  cat >&2 <<'EOF'
Usage: select-server-release.sh <release-id> [server-root] [service-name] [health-url]

Atomically selects an installed backend release, restarts the systemd service,
and restores the previous release if readiness does not become UP.
EOF
  exit 2
}

fail() {
  printf 'select-server-release: %s\n' "$1" >&2
  exit 1
}

[[ "$#" -ge 1 && "$#" -le 4 ]] || usage

release_id="$1"
server_root="${2:-/opt/after-school-service}"
service_name="${3:-after-school-service.service}"
health_url="${4:-http://127.0.0.1:8081/readyz}"
health_attempts="${AFTER_SCHOOL_HEALTH_ATTEMPTS:-30}"
health_interval="${AFTER_SCHOOL_HEALTH_INTERVAL_SECONDS:-2}"

[[ "${release_id}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$ ]] \
  || fail "release id may contain only letters, digits, dot, underscore and dash"
[[ "${server_root}" == /* && "${server_root}" != "/" ]] \
  || fail "server root must be a specific absolute directory"
[[ "${service_name}" =~ ^[A-Za-z0-9@_.-]+\.service$ ]] \
  || fail "service name must be a systemd .service unit"
[[ "${health_url}" =~ ^http://127\.0\.0\.1:[0-9]+/readyz$ ]] \
  || fail "health URL must be the loopback /readyz endpoint"
[[ "${health_attempts}" =~ ^[0-9]+$ && "${health_attempts}" -ge 1 \
    && "${health_attempts}" -le 120 ]] \
  || fail "health attempts must be between 1 and 120"
[[ "${health_interval}" =~ ^[0-9]+$ && "${health_interval}" -ge 1 \
    && "${health_interval}" -le 10 ]] \
  || fail "health interval must be between 1 and 10 seconds"

systemctl_bin="${AFTER_SCHOOL_SYSTEMCTL_BIN:-systemctl}"
curl_bin="${AFTER_SCHOOL_CURL_BIN:-curl}"
command -v "${systemctl_bin}" >/dev/null 2>&1 \
  || fail "systemctl command not found"
command -v "${curl_bin}" >/dev/null 2>&1 \
  || fail "curl command not found"

release_dir="${server_root}/releases/${release_id}"
current_link="${server_root}/current"
next_link="${server_root}/.current.${release_id}.$$"
[[ -s "${release_dir}/app.jar" && -s "${release_dir}/app.jar.sha256" ]] \
  || fail "release is incomplete: ${release_id}"
[[ ! -L "${release_dir}/app.jar" && ! -L "${release_dir}/app.jar.sha256" ]] \
  || fail "release artifacts must not be symbolic links"

verify_checksum() {
  if command -v sha256sum >/dev/null 2>&1; then
    (cd "${release_dir}" && sha256sum --check --status app.jar.sha256)
  elif command -v shasum >/dev/null 2>&1; then
    (cd "${release_dir}" && shasum -a 256 --check --status app.jar.sha256)
  else
    fail "sha256sum or shasum is required"
  fi
}

atomic_select() {
  local target="$1"
  local temp_link="$2"
  ln -s "${target}" "${temp_link}"
  if mv --help 2>&1 | grep -q -- '--no-target-directory'; then
    mv -Tf -- "${temp_link}" "${current_link}"
  elif [[ "$(uname -s)" == "Darwin" ]]; then
    mv -hf -- "${temp_link}" "${current_link}"
  else
    rm -f -- "${temp_link}"
    fail "atomic symlink replacement requires GNU mv -T or BSD mv -h"
  fi
}

wait_until_ready() {
  local attempt=1
  local body=""
  while [[ "${attempt}" -le "${health_attempts}" ]]; do
    if "${systemctl_bin}" is-active --quiet "${service_name}"; then
      body="$("${curl_bin}" --fail --silent --show-error --max-time 3 \
        "${health_url}" 2>/dev/null || true)"
      if grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' <<<"${body}"; then
        return 0
      fi
    fi
    sleep "${health_interval}"
    attempt=$((attempt + 1))
  done
  return 1
}

cleanup() {
  [[ ! -L "${next_link}" ]] || rm -f -- "${next_link}"
}
trap cleanup EXIT

verify_checksum || fail "release checksum verification failed: ${release_id}"

previous_target=""
if [[ -L "${current_link}" ]]; then
  previous_target="$(readlink "${current_link}")"
  [[ "${previous_target}" =~ ^releases/[A-Za-z0-9][A-Za-z0-9._-]{0,79}$ \
      && -d "${server_root}/${previous_target}" ]] \
    || fail "current must point to a valid direct release directory"
elif [[ -e "${current_link}" ]]; then
  fail "current exists but is not a symbolic link"
fi

target="releases/${release_id}"
if [[ "${previous_target}" == "${target}" ]]; then
  wait_until_ready || fail "selected release is not ready: ${release_id}"
  printf 'Backend release %s is already selected and ready.\n' "${release_id}"
  exit 0
fi

atomic_select "${target}" "${next_link}"
if "${systemctl_bin}" restart "${service_name}" && wait_until_ready; then
  printf 'Selected backend release %s; readiness is UP.\n' "${release_id}"
  exit 0
fi

if [[ -n "${previous_target}" ]]; then
  rollback_link="${server_root}/.rollback.${release_id}.$$"
  atomic_select "${previous_target}" "${rollback_link}"
  if "${systemctl_bin}" restart "${service_name}" && wait_until_ready; then
    fail "release ${release_id} failed readiness; restored ${previous_target#releases/}"
  fi
  fail "release ${release_id} and automatic rollback both failed readiness"
fi

rm -f -- "${current_link}"
"${systemctl_bin}" stop "${service_name}" >/dev/null 2>&1 || true
fail "first release ${release_id} failed readiness; service was stopped"
