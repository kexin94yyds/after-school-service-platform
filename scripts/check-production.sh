#!/usr/bin/env bash

set -Eeuo pipefail

usage() {
  cat >&2 <<'EOF'
Usage: check-production.sh <https-origin> <tls-certificate.pem> [backup-dir] [server-root]

Run on the Ubuntu production or staging host after units and releases are
installed. The origin must not contain a path or trailing slash.
EOF
  exit 2
}

fail() {
  printf 'check-production: %s\n' "$1" >&2
  exit 1
}

pass() {
  printf 'check-production: PASS - %s\n' "$1"
}

[[ "$#" -ge 2 && "$#" -le 4 ]] || usage
public_origin="$1"
tls_certificate="$2"
backup_dir="${3:-/var/backups/after-school-service}"
server_root="${4:-/opt/after-school-service}"

[[ "${public_origin}" =~ ^https://[A-Za-z0-9.-]+(:[0-9]+)?$ ]] \
  || fail "public origin must be an HTTPS origin without path or trailing slash"
[[ -f "${tls_certificate}" ]] \
  || fail "TLS certificate path must resolve to a regular file"
[[ "${backup_dir}" == /* && "${backup_dir}" != "/" ]] \
  || fail "backup directory must be a specific absolute directory"
[[ "${server_root}" == /* && "${server_root}" != "/" ]] \
  || fail "server root must be a specific absolute directory"

[[ -r /etc/os-release ]] || fail "cannot identify the operating system"
os_id="$(sed -n 's/^ID=//p' /etc/os-release | tr -d '"')"
os_version="$(sed -n 's/^VERSION_ID=//p' /etc/os-release | tr -d '"')"
[[ "${os_id}" == "ubuntu" && "${os_version}" == "24.04" ]] \
  || fail "production baseline requires Ubuntu 24.04"
pass "Ubuntu 24.04 baseline"

for required_command in java mysql age nginx systemctl systemd-analyze curl openssl ss \
    sha256sum readlink; do
  command -v "${required_command}" >/dev/null 2>&1 \
    || fail "required command not found: ${required_command}"
done
java -version 2>&1 | sed -n '1p' | grep -Eq 'version "21\.' \
  || fail "Java 21 is required"
mysql --version | grep -Eq 'Ver[[:space:]]+8\.4\.' \
  || fail "MySQL client 8.4 is required"
age --version >/dev/null
pass "Java 21, MySQL 8.4 and age tools"

nginx -t >/dev/null 2>&1 || fail "nginx -t failed"
for unit_file in \
  /etc/systemd/system/after-school-service.service \
  /etc/systemd/system/after-school-backup.service \
  /etc/systemd/system/after-school-backup.timer \
  /etc/systemd/system/after-school-healthcheck.service \
  /etc/systemd/system/after-school-healthcheck.timer; do
  [[ -f "${unit_file}" && ! -L "${unit_file}" ]] \
    || fail "installed systemd unit is missing: ${unit_file}"
done
systemd-analyze verify \
  /etc/systemd/system/after-school-service.service \
  /etc/systemd/system/after-school-backup.service \
  /etc/systemd/system/after-school-backup.timer \
  /etc/systemd/system/after-school-healthcheck.service \
  /etc/systemd/system/after-school-healthcheck.timer \
  >/dev/null || fail "systemd unit verification failed"
for active_unit in after-school-service.service after-school-backup.timer \
    after-school-healthcheck.timer; do
  systemctl is-enabled --quiet "${active_unit}" \
    || fail "unit is not enabled: ${active_unit}"
  systemctl is-active --quiet "${active_unit}" \
    || fail "unit is not active: ${active_unit}"
done
if systemctl is-failed --quiet after-school-backup.service; then
  fail "latest backup service run is failed"
fi
pass "Nginx syntax and systemd units"

for private_file in \
  /etc/after-school-service/db-password \
  /etc/after-school-service/mysql-backup.cnf; do
  [[ -f "${private_file}" && ! -L "${private_file}" ]] \
    || fail "required private file is missing: ${private_file}"
  file_mode="$(stat -c '%a' "${private_file}")"
  file_mode="${file_mode: -3}"
  [[ "${file_mode}" =~ ^[0-7]{3}$ ]] \
    || fail "could not determine permissions for ${private_file}"
  (( (8#${file_mode} & 077) == 0 )) \
    || fail "private file is readable by group or other users: ${private_file}"
done
pass "database credential file permissions"

current_link="${server_root}/current"
[[ -L "${current_link}" ]] || fail "backend current release link is missing"
current_target="$(readlink "${current_link}")"
[[ "${current_target}" =~ ^releases/[A-Za-z0-9][A-Za-z0-9._-]{0,79}$ ]] \
  || fail "backend current link does not point to a direct release"
current_dir="${server_root}/${current_target}"
[[ -s "${current_dir}/app.jar" && -s "${current_dir}/app.jar.sha256" ]] \
  || fail "current backend release is incomplete"
(cd "${current_dir}" && sha256sum --check --status app.jar.sha256) \
  || fail "current backend JAR checksum failed"
pass "current backend release integrity"

for port in 8081 8082; do
  listener_lines="$(ss -ltnH | awk -v suffix=":${port}" '$4 ~ suffix "$" {print $4}')"
  [[ "$(wc -l <<<"${listener_lines}" | tr -d ' ')" == "1" ]] \
    || fail "expected exactly one listener on port ${port}"
  grep -Eq "^127\\.0\\.0\\.1:${port}$" <<<"${listener_lines}" \
    || fail "port ${port} is not bound only to IPv4 loopback"
done
pass "application and management ports are loopback-only"

local_liveness="$(curl --fail --silent --show-error --max-time 3 \
  http://127.0.0.1:8081/livez)"
local_readiness="$(curl --fail --silent --show-error --max-time 3 \
  http://127.0.0.1:8081/readyz)"
grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' <<<"${local_liveness}" \
  || fail "local liveness is not UP"
grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' <<<"${local_readiness}" \
  || fail "local readiness, including the database, is not UP"
curl --fail --silent --show-error --max-time 5 \
  http://127.0.0.1:8082/actuator/prometheus \
  | grep -Eq '^jvm_info' \
  || fail "local Prometheus metrics are unavailable"
pass "local liveness, readiness and Prometheus metrics"

[[ -d "${backup_dir}" ]] || fail "backup directory does not exist"
latest_backup="$(find "${backup_dir}" -mindepth 1 -maxdepth 1 -type f \
  -name '*.sql.gz.age' -mmin -2160 -print | sort | tail -n 1)"
[[ -n "${latest_backup}" && -s "${latest_backup}.sha256" ]] \
  || fail "no encrypted backup with checksum was created in the last 36 hours"
(cd "${backup_dir}" && sha256sum --check --status \
  "$(basename -- "${latest_backup}.sha256")") \
  || fail "latest encrypted backup checksum failed"
pass "recent encrypted backup and checksum"

openssl x509 -in "${tls_certificate}" -noout -checkend 2592000 \
  || fail "TLS certificate expires within 30 days"
host_port="${public_origin#https://}"
public_host="${host_port%%:*}"
public_port="${host_port#*:}"
[[ "${public_port}" != "${host_port}" ]] || public_port=443
openssl s_client -connect "${public_host}:${public_port}" -servername "${public_host}" \
  -verify_hostname "${public_host}" -verify_return_error </dev/null 2>/dev/null \
  | grep -Fq 'Verify return code: 0 (ok)' \
  || fail "served TLS certificate or hostname verification failed"
pass "TLS chain, hostname and 30-day validity window"

http_code="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
  "http://${host_port}/")"
[[ "${http_code}" == "308" ]] || fail "HTTP entry point does not return 308"
public_headers="$(curl --fail --silent --show-error --head "${public_origin}/")"
for header_contract in \
  '^strict-transport-security:[[:space:]]*max-age=31536000' \
  '^content-security-policy:' \
  '^x-content-type-options:[[:space:]]*nosniff' \
  '^x-frame-options:[[:space:]]*DENY' \
  '^referrer-policy:[[:space:]]*strict-origin-when-cross-origin' \
  '^permissions-policy:'; do
  grep -Eiq "${header_contract}" <<<"${public_headers}" \
    || fail "public response is missing a required security header"
done
for hidden_path in /actuator/health /livez /readyz; do
  hidden_code="$(curl --silent --show-error --output /dev/null \
    --write-out '%{http_code}' "${public_origin}${hidden_path}")"
  [[ "${hidden_code}" == "404" ]] \
    || fail "management path is publicly visible: ${hidden_path}"
done
cors_headers="$(curl --fail --silent --show-error --dump-header - --output /dev/null \
  --header "Origin: ${public_origin}" "${public_origin}/api/public/system-info")"
grep -Eiq "^access-control-allow-origin:[[:space:]]*${public_origin//./\\.}[[:space:]]*$" \
  <<<"${cors_headers}" \
  || fail "public CORS origin does not match the deployed origin"
pass "HTTPS redirect, security headers, hidden management paths and exact CORS"

printf 'check-production: ALL CHECKS PASSED\n'
