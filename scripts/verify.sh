#!/usr/bin/env bash

set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd -- "${script_dir}/.." && pwd)"
server_dir="${project_root}/after-school-service-server"
web_dir="${project_root}/after-school-service-web"
nginx_config="${project_root}/deploy/nginx/after-school-service.conf"
web_install_script="${project_root}/scripts/install-web-release.sh"
web_select_script="${project_root}/scripts/select-web-release.sh"
server_install_script="${project_root}/scripts/install-server-release.sh"
server_select_script="${project_root}/scripts/select-server-release.sh"
backup_script="${project_root}/scripts/backup-mysql.sh"
restore_script="${project_root}/scripts/restore-mysql-backup.sh"
monitor_script="${project_root}/scripts/monitor-health.sh"
run_demo_script="${project_root}/scripts/run-demo.sh"
production_check_script="${project_root}/scripts/check-production.sh"
verify_xlsx_script="${project_root}/scripts/VerifyXlsx.java"
systemd_dir="${project_root}/deploy/systemd"
config_example_dir="${project_root}/deploy/config"
production_runbook="${project_root}/docs/production-runbook.md"
ci_workflow="${project_root}/.github/workflows/verify.yml"
dependabot_config="${project_root}/.github/dependabot.yml"
environment_example="${project_root}/.env.example"
server_port="${AFTER_SCHOOL_VERIFY_SERVER_PORT:-18081}"
management_port="${AFTER_SCHOOL_VERIFY_MANAGEMENT_PORT:-18082}"
mysql_port="${AFTER_SCHOOL_VERIFY_MYSQL_PORT:-18306}"
web_port="${AFTER_SCHOOL_VERIFY_WEB_PORT:-15173}"
demo_password="${AFTER_SCHOOL_DEMO_PASSWORD:-123456}"
run_root=""
mysql_pid=""
server_pid=""
web_pid=""
prod_guard_pid=""
race_barrier_pid=""
race_curl_pid_a=""
race_curl_pid_b=""
race_barrier_fd_open=false
nginx_full_active_config=""
nginx_active_config=""
delivery_test_root=""
operations_test_root=""

log_step() {
  printf "\n[verify] %s\n" "$1"
}

fail() {
  printf "\n[verify] FAILED: %s\n" "$1" >&2
  if [[ -n "${run_root}" && -f "${run_root}/server.log" ]]; then
    tail -120 "${run_root}/server.log" >&2 || true
  fi
  if [[ -n "${run_root}" && -f "${run_root}/mysql.log" ]]; then
    tail -80 "${run_root}/mysql.log" >&2 || true
  fi
  if [[ -n "${run_root}" && -f "${run_root}/web.log" ]]; then
    tail -80 "${run_root}/web.log" >&2 || true
  fi
  exit 1
}

pid_is_zombie() {
  local pid="${1:-}"
  [[ -n "${pid}" ]] || return 1
  ps -o stat= -p "${pid}" 2>/dev/null | grep -Eq '^[[:space:]]*Z'
}

terminate_pid() {
  local pid="${1:-}"
  local process_name="${2:-process}"
  local attempt=0
  local term_attempts=40
  local kill_attempts=20

  [[ -n "${pid}" ]] || return 0
  if ! kill -0 "${pid}" >/dev/null 2>&1; then
    wait "${pid}" >/dev/null 2>&1 || true
    return 0
  fi

  kill -TERM "${pid}" >/dev/null 2>&1 || true
  while [[ "${attempt}" -lt "${term_attempts}" ]]; do
    if ! kill -0 "${pid}" >/dev/null 2>&1 || pid_is_zombie "${pid}"; then
      wait "${pid}" >/dev/null 2>&1 || true
      return 0
    fi
    sleep 0.25
    attempt=$((attempt + 1))
  done

  if kill -0 "${pid}" >/dev/null 2>&1; then
    printf '[verify] WARN: %s PID %s ignored TERM; sending KILL\n' \
      "${process_name}" "${pid}" >&2
    kill -KILL "${pid}" >/dev/null 2>&1 || true
  fi

  attempt=0
  while [[ "${attempt}" -lt "${kill_attempts}" ]]; do
    if ! kill -0 "${pid}" >/dev/null 2>&1 || pid_is_zombie "${pid}"; then
      wait "${pid}" >/dev/null 2>&1 || true
      return 0
    fi
    sleep 0.25
    attempt=$((attempt + 1))
  done

  printf '[verify] WARN: %s PID %s did not exit after KILL; skipping wait\n' \
    "${process_name}" "${pid}" >&2
}

release_reschedule_race_barrier() {
  if [[ "${race_barrier_fd_open}" == "true" ]]; then
    printf '%s\n' 'ROLLBACK;' >&9 || true
    exec 9>&-
    race_barrier_fd_open=false
  fi
}

cleanup() {
  local mysql_admin_pid=""
  local mysql_admin_attempt=0

  terminate_pid "${race_curl_pid_a}" "first reschedule race request"
  race_curl_pid_a=""
  terminate_pid "${race_curl_pid_b}" "second reschedule race request"
  race_curl_pid_b=""
  release_reschedule_race_barrier
  terminate_pid "${race_barrier_pid}" "reschedule race barrier"
  race_barrier_pid=""
  terminate_pid "${prod_guard_pid}" "prod Flyway guard"
  prod_guard_pid=""
  terminate_pid "${web_pid}" "frontend preview"
  web_pid=""
  terminate_pid "${server_pid}" "backend"
  server_pid=""
  if [[ -n "${mysql_pid}" ]] && kill -0 "${mysql_pid}" >/dev/null 2>&1; then
    if [[ -n "${mysql_admin:-}" && -S "${mysql_socket:-}" ]]; then
      "${mysql_admin}" --no-defaults --protocol=socket \
        --connect-timeout=2 --shutdown-timeout=5 \
        --socket="${mysql_socket}" -uroot shutdown >/dev/null 2>&1 &
      mysql_admin_pid="$!"
      while [[ "${mysql_admin_attempt}" -lt 32 ]]; do
        if ! kill -0 "${mysql_admin_pid}" >/dev/null 2>&1 \
            || pid_is_zombie "${mysql_admin_pid}"; then
          wait "${mysql_admin_pid}" >/dev/null 2>&1 || true
          mysql_admin_pid=""
          break
        fi
        sleep 0.25
        mysql_admin_attempt=$((mysql_admin_attempt + 1))
      done
      terminate_pid "${mysql_admin_pid}" "mysqladmin shutdown"
    fi
  fi
  terminate_pid "${mysql_pid}" "MySQL"
  mysql_pid=""
  if [[ -n "${run_root}" && -d "${run_root}" ]]; then
    rm -rf -- "${run_root}"
  fi
  if [[ -n "${delivery_test_root}" && -d "${delivery_test_root}" ]]; then
    rm -rf -- "${delivery_test_root}"
  fi
  if [[ -n "${operations_test_root}" && -d "${operations_test_root}" ]]; then
    rm -rf -- "${operations_test_root}"
  fi
}

trap cleanup EXIT

for required_command in curl jq lsof mvn rsync sed awk grep ps readlink flock \
    gzip stat find install mkfifo; do
  command -v "${required_command}" >/dev/null 2>&1 \
    || fail "missing required command: ${required_command}"
done

[[ -d "${server_dir}" ]] || fail "missing backend directory"
[[ -d "${web_dir}" ]] || fail "missing frontend directory"
[[ -s "${verify_xlsx_script}" ]] || fail "missing independent XLSX verifier"

log_step "Checking production operations contract"

for executable_script in \
  "${web_install_script}" \
  "${web_select_script}" \
  "${server_install_script}" \
  "${server_select_script}" \
  "${backup_script}" \
  "${restore_script}" \
  "${monitor_script}" \
  "${run_demo_script}" \
  "${production_check_script}"; do
  [[ -x "${executable_script}" ]] \
    || fail "production operations script must be executable: ${executable_script}"
done
bash -n \
  "${web_install_script}" \
  "${web_select_script}" \
  "${server_install_script}" \
  "${server_select_script}" \
  "${backup_script}" \
  "${restore_script}" \
  "${monitor_script}" \
  "${run_demo_script}" \
  "${production_check_script}" \
  || fail "production operations script syntax failed"

for required_artifact in \
  "${systemd_dir}/after-school-service.service" \
  "${systemd_dir}/after-school-backup.service" \
  "${systemd_dir}/after-school-backup.timer" \
  "${systemd_dir}/after-school-healthcheck.service" \
  "${systemd_dir}/after-school-healthcheck.timer" \
  "${config_example_dir}/after-school-service.conf.example" \
  "${config_example_dir}/after-school-backup.conf.example" \
  "${config_example_dir}/after-school-monitor.conf.example" \
  "${config_example_dir}/mysql-backup.cnf.example" \
  "${config_example_dir}/age-recipients.example" \
  "${production_runbook}"; do
  [[ -s "${required_artifact}" ]] \
    || fail "missing production operations artifact: ${required_artifact}"
done

service_unit="${systemd_dir}/after-school-service.service"
for service_contract in \
  'User=after-school' \
  'LoadCredential=spring.datasource.password:/etc/after-school-service/db-password' \
  'UnsetEnvironment=DB_PASSWORD' \
  'Restart=on-failure' \
  'TimeoutStopSec=45s' \
  'ProtectSystem=strict' \
  'NoNewPrivileges=true' \
  'CapabilityBoundingSet='; do
  grep -Fxq "${service_contract}" "${service_unit}" \
    || fail "backend systemd unit is missing: ${service_contract}"
done
for backup_contract in \
  '--single-transaction' \
  '--set-gtid-purged=OFF' \
  '--no-tablespaces' \
  '--recipients-file' \
  'ssl-mode=VERIFY_IDENTITY'; do
  grep -Fq -- "${backup_contract}" "${backup_script}" \
    || grep -Fq -- "${backup_contract}" "${config_example_dir}/mysql-backup.cnf.example" \
    || fail "encrypted backup contract is missing: ${backup_contract}"
done
grep -Fq 'target database already exists; restore requires a new database' \
  "${restore_script}" \
  || fail "restore script must refuse existing target databases"
grep -Fq 'HEALTH_ALERT_HOOK must be owned by root' "${monitor_script}" \
  || fail "health monitor must protect the alert hook"
grep -Fq 'check-production: ALL CHECKS PASSED' "${production_check_script}" \
  || fail "production check must have an explicit all-pass terminal state"
grep -Fq 'latest backup service run failed' "${monitor_script}" \
  || fail "health monitor must surface a failed backup service run"
grep -Fq 'MYSQL_DATABASE must contain only letters, digits and underscore' \
  "${monitor_script}" \
  || fail "health monitor must validate its target database"
grep -Fq 'sha256sum --check --status' "${monitor_script}" \
  || fail "health monitor must verify backup checksums"
grep -Fq 'RESTORE_REQUIRED_FLYWAY_VERSION' "${restore_script}" \
  || fail "restore script must validate the required Flyway version"
grep -Fq 'fk_supervision_alert_scan_run' "${restore_script}" \
  || fail "restore script must validate supervision scan-run integrity"
grep -Fq 'env -i' "${run_demo_script}" \
  || fail "demo launcher must clear inherited production environment variables"
grep -Fq 'after_school_demo' "${run_demo_script}" \
  || fail "demo launcher must target the dedicated local demo database"
grep -Fq 'SERVER_ADDRESS=127.0.0.1' "${run_demo_script}" \
  || fail "demo launcher must bind the application server to loopback"
grep -Fq 'MANAGEMENT_SERVER_ADDRESS=127.0.0.1' "${run_demo_script}" \
  || fail "demo launcher must bind management endpoints to loopback"
grep -Fq 'MANAGEMENT_SERVER_PORT="${demo_management_port}"' "${run_demo_script}" \
  || fail "demo launcher must isolate management endpoints on a separate port"
grep -Fq 'production release JAR must not package demo payload' \
  "${server_install_script}" \
  || fail "production release installer must reject demo artifacts"
grep -Fq 'TLS certificate path must resolve to a regular file' \
  "${production_check_script}" \
  || fail "production check must accept certificate paths that resolve through renewal symlinks"
if grep -Fq 'TLS certificate must be a regular non-symlink file' \
    "${production_check_script}"; then
  fail "production check still rejects standard certificate renewal symlinks"
fi
for database_url_contract in \
  'sslMode=VERIFY_IDENTITY' \
  'connectionTimeZone=%2B08%3A00' \
  'forceConnectionTimeZoneToSession=true'; do
  grep -Fq "${database_url_contract}" \
    "${config_example_dir}/after-school-service.conf.example" \
    || fail "production database URL contract is missing: ${database_url_contract}"
done
for backup_rpo_contract in \
  'OnCalendar=*-*-* 00,12:15:00' \
  'RandomizedDelaySec=15m' \
  'BACKUP_MAX_AGE_HOURS=18'; do
  grep -Fxq "${backup_rpo_contract}" \
    "${systemd_dir}/after-school-backup.timer" \
    "${config_example_dir}/after-school-monitor.conf.example" \
    || fail "backup RPO contract is missing: ${backup_rpo_contract}"
done
for delivery_script in install-server-release.sh select-server-release.sh \
    install-web-release.sh select-web-release.sh backup-mysql.sh \
    restore-mysql-backup.sh monitor-health.sh check-production.sh; do
  grep -Fq "${delivery_script}" "${production_runbook}" \
    || fail "production runbook installation list is missing: ${delivery_script}"
done
for environment_database_contract in \
  'connectionTimeZone=%2B08%3A00' \
  'forceConnectionTimeZoneToSession=true' \
  'sslMode=VERIFY_IDENTITY'; do
  grep -Fq "${environment_database_contract}" "${environment_example}" \
    || fail "environment example is missing production DB contract: ${environment_database_contract}"
done
[[ -s "${ci_workflow}" ]] || fail "CI verification workflow is missing"
[[ -s "${dependabot_config}" ]] || fail "dependency update configuration is missing"
for supply_chain_contract in \
  'dependency-check-maven' \
  'cyclonedx-maven-plugin' \
  '<failBuildOnCVSS>7.0</failBuildOnCVSS>' \
  'mvn --batch-mode --no-transfer-progress clean verify -Psecurity'; do
  grep -Fq "${supply_chain_contract}" \
    "${server_dir}/pom.xml" "${ci_workflow}" \
    || fail "backend supply-chain contract is missing: ${supply_chain_contract}"
done
for dependency_ecosystem in maven npm github-actions; do
  grep -Fq "package-ecosystem: ${dependency_ecosystem}" "${dependabot_config}" \
    || fail "dependency update configuration is missing: ${dependency_ecosystem}"
done
grep -Fq '@Size(min = 12, max = 72) String password' \
  "${server_dir}/src/main/java/com/afterschool/platform/people/PeopleController.java" \
  || fail "managed teacher and guardian passwords must require 12 characters"
grep -Fq '任一项未满足时' "${production_runbook}" \
  || fail "production runbook must define a fail-closed launch gate"

log_step "Checking same-origin production delivery contract"

[[ -f "${nginx_config}" ]] || fail "missing production Nginx configuration"
nginx_full_active_config="$(sed 's/[[:space:]]*#.*$//' "${nginx_config}")"
for edge_zone_contract in \
  'limit_req_zone $binary_remote_addr zone=after_school_login:10m rate=6r/m;' \
  'limit_req_zone $binary_remote_addr zone=after_school_api:10m rate=30r/s;' \
  'limit_conn_zone $binary_remote_addr zone=after_school_connections:10m;'; do
  [[ "$(grep -Fxc "${edge_zone_contract}" <<<"${nginx_full_active_config}")" == "1" ]] \
    || fail "production Nginx configuration is missing edge limit zone: ${edge_zone_contract}"
done
if ! nginx_active_config="$(
  awk '
    /^[[:space:]]*server[[:space:]]*\{[[:space:]]*$/ && !in_server {
      in_server = 1
      block = $0 ORS
      opened = $0
      closed = $0
      depth = gsub(/\{/, "", opened) - gsub(/\}/, "", closed)
      has_https = 0
      next
    }
    in_server {
      block = block $0 ORS
      if ($0 ~ /^[[:space:]]*listen[[:space:]]+443[[:space:]]+ssl[[:space:]]*;[[:space:]]*$/) {
        has_https = 1
      }
      opened = $0
      closed = $0
      depth += gsub(/\{/, "", opened) - gsub(/\}/, "", closed)
      if (depth == 0) {
        if (has_https) {
          https_server_count++
          https_server = block
        }
        in_server = 0
      }
    }
    END {
      if (https_server_count != 1) {
        exit 1
      }
      printf "%s", https_server
    }
  ' <<<"${nginx_full_active_config}"
)"; then
  fail "production Nginx configuration must define exactly one HTTPS server block"
fi

nginx_has_active_directive() {
  local directive_pattern="$1"
  grep -Eq "${directive_pattern}" <<<"${nginx_active_config}"
}

nginx_location_has_directive() {
  local location_pattern="$1"
  local directive_pattern="$2"
  local case_insensitive="${3:-false}"
  local location_count=""
  local location_line_number=""
  location_count="$(
    grep -Ec "${location_pattern}" <<<"${nginx_active_config}" || true
  )"
  [[ "${location_count}" == "1" ]] || return 1
  location_line_number="$(
    grep -En "${location_pattern}" <<<"${nginx_active_config}" \
      | sed -n '1{s/:.*//;p;}' || true
  )"
  [[ -n "${location_line_number}" ]] || return 1

  awk \
    -v target_line="${location_line_number}" \
    -v directive_pattern="${directive_pattern}" \
    -v case_insensitive="${case_insensitive}" '
    BEGIN {
      effective_pattern = case_insensitive == "true" \
        ? tolower(directive_pattern) \
        : directive_pattern
    }
    NR == target_line {
      in_location = 1
      opened = $0
      closed = $0
      depth = gsub(/\{/, "", opened) - gsub(/\}/, "", closed)
      next
    }
    in_location {
      candidate = case_insensitive == "true" ? tolower($0) : $0
      if (depth == 1 && candidate ~ effective_pattern) {
        match_count++
      }
      opened = $0
      closed = $0
      depth += gsub(/\{/, "", opened) - gsub(/\}/, "", closed)
      if (depth == 0) {
        if (match_count == 1) {
          valid = 1
        }
        in_location = 0
      }
    }
    END { exit(valid ? 0 : 1) }
  ' <<<"${nginx_active_config}"
}

nginx_location_has_direct_deny() {
  nginx_location_has_directive \
    "$1" \
    '^[[:space:]]*deny[[:space:]]+all[[:space:]]*;[[:space:]]*$'
}

api_location_pattern='^[[:space:]]*location[[:space:]]+\^~[[:space:]]+/api/[[:space:]]*\{[[:space:]]*$'
login_location_pattern='^[[:space:]]*location[[:space:]]+=[[:space:]]+/api/auth/login[[:space:]]*\{[[:space:]]*$'
actuator_location_pattern='^[[:space:]]*location[[:space:]]+\^~[[:space:]]+/actuator/[[:space:]]*\{[[:space:]]*$'
livez_location_pattern='^[[:space:]]*location[[:space:]]+=[[:space:]]+/livez[[:space:]]*\{[[:space:]]*$'
readyz_location_pattern='^[[:space:]]*location[[:space:]]+=[[:space:]]+/readyz[[:space:]]*\{[[:space:]]*$'
release_location_pattern='^[[:space:]]*location[[:space:]]+/releases/[[:space:]]*\{[[:space:]]*$'
root_location_pattern='^[[:space:]]*location[[:space:]]+/[[:space:]]*\{[[:space:]]*$'

nginx_has_active_directive \
  '^[[:space:]]*listen[[:space:]]+443[[:space:]]+ssl[[:space:]]*;[[:space:]]*$' \
  || fail "production Nginx configuration must terminate HTTPS"
nginx_has_active_directive \
  "${api_location_pattern}" \
  || fail "production Nginx configuration must isolate the /api/ prefix"
nginx_has_active_directive \
  "${login_location_pattern}" \
  || fail "production Nginx configuration must isolate the login endpoint"
for backend_location_pattern in "${api_location_pattern}" "${login_location_pattern}"; do
  nginx_location_has_directive \
    "${backend_location_pattern}" \
    '^[[:space:]]*proxy_pass[[:space:]]+http://127\.0\.0\.1:8081[[:space:]]*;[[:space:]]*$' \
    || fail "production Nginx API locations must preserve the URI for the backend"
  if nginx_location_has_directive \
      "${backend_location_pattern}" \
      '^[[:space:]]*proxy_pass[[:space:]]+http://127\.0\.0\.1:8081/[[:space:]]*;[[:space:]]*$'; then
    fail "production Nginx proxy_pass must not strip the /api/ prefix"
  fi
  for proxy_timeout_contract in \
    'proxy_connect_timeout[[:space:]]+5s' \
    'proxy_send_timeout[[:space:]]+30s' \
    'proxy_read_timeout[[:space:]]+30s'; do
    nginx_location_has_directive \
      "${backend_location_pattern}" \
      "^[[:space:]]*${proxy_timeout_contract}[[:space:]]*;[[:space:]]*$" \
      || fail "production Nginx API locations must bound proxy timeouts"
  done
done
nginx_location_has_directive \
  "${login_location_pattern}" \
  '^[[:space:]]*limit_req[[:space:]]+zone=after_school_login[[:space:]]+burst=5[[:space:]]+nodelay[[:space:]]*;[[:space:]]*$' \
  || fail "production Nginx login endpoint must use its strict rate limit"
nginx_location_has_directive \
  "${api_location_pattern}" \
  '^[[:space:]]*limit_req[[:space:]]+zone=after_school_api[[:space:]]+burst=60[[:space:]]+nodelay[[:space:]]*;[[:space:]]*$' \
  || fail "production Nginx API must use the global rate limit"
for forwarded_header_contract in \
  'Host:\\$host' \
  'X-Real-IP:\\$remote_addr' \
  'X-Forwarded-For:\\$remote_addr' \
  'X-Forwarded-Host:\\$host' \
  'X-Forwarded-Port:\\$server_port' \
  'X-Forwarded-Proto:\\$scheme'; do
  forwarded_header="${forwarded_header_contract%%:*}"
  forwarded_value="${forwarded_header_contract#*:}"
  for backend_location_pattern in "${api_location_pattern}" "${login_location_pattern}"; do
    nginx_location_has_directive \
      "${backend_location_pattern}" \
      "^[[:space:]]*proxy_set_header[[:space:]]+${forwarded_header}[[:space:]]+${forwarded_value}[[:space:]]*;[[:space:]]*$" \
      || fail "production Nginx configuration is missing ${forwarded_header} forwarding"
    nginx_location_has_directive \
      "${backend_location_pattern}" \
      "^[[:space:]]*proxy_set_header[[:space:]]+${forwarded_header}[[:space:]]+[^;]+[[:space:]]*;[[:space:]]*$" \
      true \
      || fail "production Nginx configuration must set ${forwarded_header} exactly once per API location"
  done
done
for local_only_location_pattern in \
  "${actuator_location_pattern}" \
  "${livez_location_pattern}" \
  "${readyz_location_pattern}"; do
  nginx_location_has_directive \
    "${local_only_location_pattern}" \
    '^[[:space:]]*return[[:space:]]+404[[:space:]]*;[[:space:]]*$' \
    || fail "production Nginx must hide management and probe endpoints"
done
for server_limit_contract in \
  '^[[:space:]]*limit_conn[[:space:]]+after_school_connections[[:space:]]+40[[:space:]]*;[[:space:]]*$' \
  '^[[:space:]]*limit_conn_status[[:space:]]+429[[:space:]]*;[[:space:]]*$' \
  '^[[:space:]]*limit_req_status[[:space:]]+429[[:space:]]*;[[:space:]]*$'; do
  nginx_has_active_directive "${server_limit_contract}" \
    || fail "production Nginx configuration is missing a server limit contract"
done
security_header_directives=(
  'add_header Strict-Transport-Security "max-age=31536000" always;'
  'add_header Content-Security-Policy "default-src '\''self'\''; script-src '\''self'\''; style-src '\''self'\'' '\''unsafe-inline'\''; img-src '\''self'\'' data: blob:; font-src '\''self'\'' data:; connect-src '\''self'\''; object-src '\''none'\''; base-uri '\''self'\''; frame-ancestors '\''none'\''; form-action '\''self'\''; upgrade-insecure-requests" always;'
  'add_header X-Content-Type-Options "nosniff" always;'
  'add_header X-Frame-Options "DENY" always;'
  'add_header Referrer-Policy "strict-origin-when-cross-origin" always;'
  'add_header Permissions-Policy "camera=(), microphone=(), geolocation=(), payment=(), usb=()" always;'
)
for security_header_directive in "${security_header_directives[@]}"; do
  [[ "$(grep -Fc "${security_header_directive}" <<<"${nginx_active_config}")" == "3" ]] \
    || fail "security headers must cover server responses, SPA routes and versioned assets"
done
nginx_has_active_directive "${root_location_pattern}" \
  || fail "production Nginx configuration must define the SPA root location"
nginx_has_active_directive \
  '^[[:space:]]*root[[:space:]]+/var/www/after-school-service/current[[:space:]]*;[[:space:]]*$' \
  || fail "production Nginx root must use the atomically selected current release"
nginx_has_active_directive "${release_location_pattern}" \
  || fail "production Nginx configuration must expose versioned releases"
nginx_location_has_directive \
  "${release_location_pattern}" \
  '^[[:space:]]*root[[:space:]]+/var/www/after-school-service[[:space:]]*;[[:space:]]*$' \
  || fail "versioned assets must resolve from the release root"
nginx_location_has_directive \
  "${release_location_pattern}" \
  '^[[:space:]]*try_files[[:space:]]+\\$uri[[:space:]]+=404[[:space:]]*;[[:space:]]*$' \
  || fail "missing versioned assets must return 404"
nginx_location_has_directive \
  "${release_location_pattern}" \
  '^[[:space:]]*add_header[[:space:]]+Cache-Control[[:space:]]+"public, max-age=31536000, immutable"[[:space:]]*;[[:space:]]*$' \
  || fail "versioned assets must be immutable-cacheable"
nginx_location_has_directive \
  "${root_location_pattern}" \
  '^[[:space:]]*try_files[[:space:]]+\\$uri[[:space:]]+\\$uri/[[:space:]]+/index\.html[[:space:]]*;[[:space:]]*$' \
  || fail "production Nginx configuration must provide Vue Router SPA fallback"
nginx_location_has_directive \
  "${root_location_pattern}" \
  '^[[:space:]]*add_header[[:space:]]+Cache-Control[[:space:]]+"no-cache"[[:space:]]*;[[:space:]]*$' \
  || fail "SPA HTML and route responses must revalidate after a release switch"
nginx_has_active_directive \
  '^[[:space:]]*autoindex[[:space:]]+off[[:space:]]*;[[:space:]]*$' \
  || fail "production Nginx configuration must disable directory listings"
nginx_location_has_direct_deny \
  '^[[:space:]]*location[[:space:]]+~[[:space:]]+/\\\.\(\?!well-known\(\?:/\|\$\)\)[[:space:]]*\{[[:space:]]*$' \
  || fail "production Nginx configuration must deny hidden files outside .well-known"
nginx_location_has_direct_deny \
  '^[[:space:]]*location[[:space:]]+~\*[[:space:]]+\^/\(\?:deploy\|node_modules\|scripts\|src\|target\)\(\?:/\|\$\)[[:space:]]*\{[[:space:]]*$' \
  || fail "production Nginx configuration must deny internal directories"
nginx_location_has_direct_deny \
  '^[[:space:]]*location[[:space:]]+~\*[[:space:]]+\^/\(\?:package\(\?:-lock\)\?\\\.json\|pom\\\.xml\|tsconfig\(\?:\\\.\[\^/\]\+\)\?\\\.json\|vite\\\.config\\\.\[\^/\]\+\)\$[[:space:]]*\{[[:space:]]*$' \
  || fail "production Nginx configuration must deny source configuration files"
nginx_location_has_direct_deny \
  '^[[:space:]]*location[[:space:]]+~\*[[:space:]]+\\\.\(\?:bak\|conf\|env\|ini\|log\|map\|sql\|sqlite\|swp\|toml\|ya\?ml\)\$[[:space:]]*\{[[:space:]]*$' \
  || fail "production Nginx configuration must deny sensitive file extensions"

log_step "Testing atomic versioned web delivery"

[[ -x "${web_install_script}" ]] || fail "web release installer must be executable"
grep -Fq 'mv -Tf -- "${next_link}" "${web_root}/current"' "${web_install_script}" \
  || fail "web release installer must atomically replace current on GNU/Linux"
grep -Fq 'mv -hf -- "${next_link}" "${web_root}/current"' "${web_install_script}" \
  || fail "web release installer must atomically replace current on BSD/macOS"
grep -Fq 'find "${releases_root}" -mindepth 1 -maxdepth 1 -type d -print0' \
  "${web_install_script}" \
  || fail "web release cleanup must be scoped to direct release directories"
if grep -Eq 'rsync[[:space:]].*--delete.*[[:space:]]+/var/www/after-school-service/?[[:space:]]*$' \
    "${project_root}/README.md"; then
  fail "production documentation must not overwrite the live web root in place"
fi

delivery_test_root="$(mktemp -d -t after-school-delivery.XXXXXX)"
first_dist="${delivery_test_root}/dist-first"
second_dist="${delivery_test_root}/dist-second"
invalid_dist="${delivery_test_root}/dist-invalid"
delivery_root="${delivery_test_root}/www"
mkdir -p "${first_dist}/assets" "${second_dist}/assets" "${invalid_dist}/assets"
printf '<script src="/releases/release-one/assets/app-one.js"></script>\n' \
  >"${first_dist}/index.html"
printf 'one\n' >"${first_dist}/assets/app-one.js"
"${web_install_script}" "${first_dist}" release-one "${delivery_root}" 7 >/dev/null
[[ "$(readlink "${delivery_root}/current")" == "releases/release-one" ]] \
  || fail "first web release was not selected"

printf '<script src="/releases/wrong/assets/app.js"></script>\n' \
  >"${invalid_dist}/index.html"
printf 'invalid\n' >"${invalid_dist}/assets/app.js"
if "${web_install_script}" "${invalid_dist}" release-invalid "${delivery_root}" 7 \
    >/dev/null 2>&1; then
  fail "web release installer accepted a mismatched Vite base"
fi
[[ "$(readlink "${delivery_root}/current")" == "releases/release-one" ]] \
  || fail "failed web release validation changed the active release"

printf '<script src="/releases/release-two/assets/app-two.js"></script>\n' \
  >"${second_dist}/index.html"
printf 'two\n' >"${second_dist}/assets/app-two.js"
"${web_install_script}" "${second_dist}" release-two "${delivery_root}" 7 >/dev/null
[[ "$(readlink "${delivery_root}/current")" == "releases/release-two" ]] \
  || fail "second web release was not selected"
[[ -f "${delivery_root}/releases/release-one/assets/app-one.js" ]] \
  || fail "the previous release was removed before the grace period"
"${web_select_script}" release-one "${delivery_root}" >/dev/null
[[ "$(readlink "${delivery_root}/current")" == "releases/release-one" ]] \
  || fail "frontend rollback did not select the retained release"
"${web_select_script}" release-two "${delivery_root}" >/dev/null
[[ "$(readlink "${delivery_root}/current")" == "releases/release-two" ]] \
  || fail "frontend roll-forward did not restore the latest release"

log_step "Selecting Java 21 and a supported Node.js runtime"

jdk21_home="${AFTER_SCHOOL_JDK21_HOME:-}"
if [[ -z "${jdk21_home}" ]]; then
  if [[ -x /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java ]]; then
    jdk21_home="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
  elif [[ -x /usr/libexec/java_home ]]; then
    jdk21_home="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
  fi
fi
[[ -x "${jdk21_home}/bin/java" ]] || fail "Java 21 not found"
java_version="$("${jdk21_home}/bin/java" -version 2>&1 | sed -n '1p')"
[[ "${java_version}" == *'"21.'* ]] || fail "selected JDK is not Java 21"

node_bin_dir="${AFTER_SCHOOL_NODE_BIN:-}"
if [[ -z "${node_bin_dir}" && -x /opt/homebrew/opt/node@22/bin/node ]]; then
  node_bin_dir="/opt/homebrew/opt/node@22/bin"
fi
if [[ -z "${node_bin_dir}" ]]; then
  node_bin_dir="$(dirname -- "$(command -v node)")"
fi
[[ -x "${node_bin_dir}/node" && -x "${node_bin_dir}/npm" ]] \
  || fail "Node.js and npm not found"
"${node_bin_dir}/node" -e '
  const [major, minor] = process.versions.node.split(".").map(Number)
  if (!((major === 22 && minor >= 18) || (major === 24 && minor >= 11) || major > 24)) {
    process.exit(1)
  }
' || fail "Node.js must satisfy ^22.18.0 || >=24.11.0"
printf "[verify] Java: %s\n" "${java_version}"
printf "[verify] Node: %s\n" "$("${node_bin_dir}/node" --version)"

log_step "Checking migration and authorization contracts"

migration_dir="${server_dir}/src/main/resources/db/migration"
[[ "$(grep -hEc '^CREATE TABLE[[:space:]]+' "${migration_dir}"/V1__*.sql)" == "13" ]] \
  || fail "V1 must create exactly 13 domain tables"
for migration in \
  V1__create_core_schema.sql \
  V2__seed_roles.sql \
  V3__harden_tenant_and_teaching_integrity.sql \
  V5__add_academic_planning_and_resource_constraints.sql \
  V6__add_leave_and_attendance_correction_workflows.sql \
  V7__add_supervision_audit_evaluation_and_reporting.sql \
  V9__separate_current_guardian_authorization.sql \
  V10__preserve_reviewed_leave_withdrawal_history.sql \
  V12__add_supervision_scan_runs.sql \
  V13__link_supervision_alert_scan_runs.sql \
  V14__align_opening_report_business_model.sql \
  V16__add_student_accounts_and_four_role_identity.sql \
  V17__add_student_grades_and_revision_history.sql \
  V18__scope_academic_terms_to_school.sql; do
  [[ -f "${migration_dir}/${migration}" ]] || fail "missing migration ${migration}"
done
demo_migration="${server_dir}/src/main/resources/db/demo/V4__seed_demo_workflow.sql"
[[ -f "${demo_migration}" ]] || fail "missing demo-only migration V4__seed_demo_workflow.sql"
comprehensive_demo_migration="${server_dir}/src/main/resources/db/demo/V8__seed_comprehensive_graduation_workflow.sql"
[[ -f "${comprehensive_demo_migration}" ]] \
  || fail "missing demo-only migration V8__seed_comprehensive_graduation_workflow.sql"
credential_demo_migration="${server_dir}/src/main/resources/db/demo/V11__simplify_demo_login_credentials.sql"
[[ -f "${credential_demo_migration}" ]] \
  || fail "missing demo-only migration V11__simplify_demo_login_credentials.sql"
demo_scan_run_bridge_migration="${server_dir}/src/main/resources/db/demo/V4_1__seed_demo_scan_run_parent.sql"
[[ -f "${demo_scan_run_bridge_migration}" ]] \
  || fail "missing demo scan-run compatibility bridge migration"
opening_report_demo_migration="${server_dir}/src/main/resources/db/demo/V15__align_demo_with_opening_report.sql"
[[ -f "${opening_report_demo_migration}" ]] \
  || fail "missing demo opening-report alignment migration"
[[ -f "${server_dir}/src/main/resources/db/demo/V20__seed_student_login_accounts.sql" \
    && -f "${server_dir}/src/main/resources/db/demo/V21__seed_student_grades.sql" ]] \
  || fail "missing four-role student demo migrations"
[[ -f "${server_dir}/src/main/resources/db/migration/V18__scope_academic_terms_to_school.sql" ]] \
  || fail "missing school-scoped academic term migration"
if grep -RhEq "AfterSchool@2026|123456|'regulator'|'admin'" "${migration_dir}"; then
  fail "default production migrations must not contain demo accounts or passwords"
fi
for guard in \
  fk_enrollment_offering_school \
  fk_enrollment_student_school \
  fk_attendance_enrollment \
  uk_enrollment_offering_student \
  fk_offering_plan_school_term \
  uk_leave_active_session_student \
  uk_revision_correction \
  uk_supervision_alert_dedup \
  fk_supervision_alert_scan_run \
  uk_course_evaluation_target \
  uk_plan_item_category \
  uk_regulator_notification_alert \
  fk_enrollment_action_identity \
  uk_rectification_notice_alert \
  uk_student_user \
  uk_student_grade_target \
  idx_grade_revision_grade \
  uk_academic_term_school_code \
  fk_academic_term_school; do
  grep -RhFq "${guard}" "${migration_dir}" || fail "missing schema guard ${guard}"
done

log_step "Running backend automated tests and packaging production/demo artifacts"

(
  cd "${server_dir}"
  env -u SPRING_FLYWAY_LOCATIONS \
    -u SPRING_FLYWAY_OUT_OF_ORDER \
    -u SPRING_PROFILES_ACTIVE \
    JAVA_HOME="${jdk21_home}" PATH="${jdk21_home}/bin:${PATH}" \
    mvn -B clean verify
)
test_count="$(
  awk '/Tests run:/{for(i=1;i<=NF;i++) if($i=="run:"){value=$(i+1); gsub(",","",value); sum+=value}} END{print sum + 0}' \
    "${server_dir}"/target/surefire-reports/*.txt
)"
[[ "${test_count}" -ge 80 ]] || fail "expected at least 80 backend tests, found ${test_count}"
jar_path="${server_dir}/target/after-school-service-server-0.0.1-SNAPSHOT.jar"
[[ -f "${jar_path}" ]] || fail "backend executable JAR was not generated"
[[ -s "${server_dir}/target/classes/META-INF/sbom/application.cdx.json" ]] \
  || fail "backend CycloneDX SBOM was not generated"
for jar_entry in \
  "META-INF/sbom/application.cdx.json" \
  "BOOT-INF/classes/com/afterschool/platform/auth/AuthController.class" \
  "BOOT-INF/classes/com/afterschool/platform/registration/EnrollmentService.class" \
  "BOOT-INF/classes/com/afterschool/platform/teaching/TeachingService.class" \
  "BOOT-INF/classes/com/afterschool/platform/academic/AcademicService.class" \
  "BOOT-INF/classes/com/afterschool/platform/leavecorrection/LeaveCorrectionService.class" \
  "BOOT-INF/classes/com/afterschool/platform/supervision/SupervisionService.class" \
  "BOOT-INF/classes/com/afterschool/platform/evaluation/EvaluationService.class" \
  "BOOT-INF/classes/com/afterschool/platform/audit/OperationAuditService.class" \
  "BOOT-INF/classes/com/afterschool/platform/report/ReportController.class" \
  "BOOT-INF/classes/com/afterschool/platform/rectification/RectificationController.class" \
  "BOOT-INF/classes/db/migration/V5__add_academic_planning_and_resource_constraints.sql" \
  "BOOT-INF/classes/db/migration/V6__add_leave_and_attendance_correction_workflows.sql" \
  "BOOT-INF/classes/db/migration/V7__add_supervision_audit_evaluation_and_reporting.sql" \
  "BOOT-INF/classes/db/migration/V9__separate_current_guardian_authorization.sql" \
  "BOOT-INF/classes/db/migration/V10__preserve_reviewed_leave_withdrawal_history.sql" \
  "BOOT-INF/classes/db/migration/V12__add_supervision_scan_runs.sql" \
  "BOOT-INF/classes/db/migration/V13__link_supervision_alert_scan_runs.sql" \
  "BOOT-INF/classes/db/migration/V14__align_opening_report_business_model.sql" \
  "BOOT-INF/classes/db/migration/V16__add_student_accounts_and_four_role_identity.sql" \
  "BOOT-INF/classes/db/migration/V17__add_student_grades_and_revision_history.sql" \
  "BOOT-INF/classes/db/migration/V18__scope_academic_terms_to_school.sql"; do
  "${jdk21_home}/bin/jar" tf "${jar_path}" | grep -Fq "${jar_entry}" \
    || fail "backend JAR is missing ${jar_entry}"
done
if "${jdk21_home}/bin/jar" tf "${jar_path}" \
    | grep -Eq \
      '^BOOT-INF/classes/(db/demo/|application-demo\.yml$|com/afterschool/platform/demo/DemoTimelineRefresher\.class$)'; then
  fail "production backend JAR must not package demo payload"
fi

(
  cd "${server_dir}"
  env -u SPRING_FLYWAY_LOCATIONS \
    -u SPRING_FLYWAY_OUT_OF_ORDER \
    -u SPRING_PROFILES_ACTIVE \
    JAVA_HOME="${jdk21_home}" PATH="${jdk21_home}/bin:${PATH}" \
    mvn -B verify -Pdemo-artifact
)
demo_jar_path="${server_dir}/target/after-school-service-server-0.0.1-SNAPSHOT-demo.jar"
[[ -f "${demo_jar_path}" ]] || fail "separately packaged demo JAR was not generated"
for demo_jar_entry in \
  "BOOT-INF/classes/application-demo.yml" \
  "BOOT-INF/classes/com/afterschool/platform/demo/DemoTimelineRefresher.class" \
  "BOOT-INF/classes/db/demo/V4__seed_demo_workflow.sql" \
  "BOOT-INF/classes/db/demo/V4_1__seed_demo_scan_run_parent.sql" \
  "BOOT-INF/classes/db/demo/V8__seed_comprehensive_graduation_workflow.sql" \
  "BOOT-INF/classes/db/demo/V11__simplify_demo_login_credentials.sql" \
  "BOOT-INF/classes/db/demo/V15__align_demo_with_opening_report.sql" \
  "BOOT-INF/classes/db/demo/V20__seed_student_login_accounts.sql" \
  "BOOT-INF/classes/db/demo/V21__seed_student_grades.sql" \
  "BOOT-INF/classes/db/migration/V13__link_supervision_alert_scan_runs.sql" \
  "BOOT-INF/classes/db/migration/V14__align_opening_report_business_model.sql"; do
  "${jdk21_home}/bin/jar" tf "${demo_jar_path}" | grep -Fq "${demo_jar_entry}" \
    || fail "demo backend JAR is missing ${demo_jar_entry}"
done
(
  cd "${server_dir}"
  # Do not clean here: prove the production archive still excludes db/demo
  # after a demo-artifact build has populated target/classes.
  env -u SPRING_FLYWAY_LOCATIONS \
    -u SPRING_FLYWAY_OUT_OF_ORDER \
    -u SPRING_PROFILES_ACTIVE \
    JAVA_HOME="${jdk21_home}" PATH="${jdk21_home}/bin:${PATH}" \
    mvn -B -DskipTests package
)
if "${jdk21_home}/bin/jar" tf "${jar_path}" \
    | grep -Eq \
      '^BOOT-INF/classes/(db/demo/|application-demo\.yml$|com/afterschool/platform/demo/DemoTimelineRefresher\.class$)'; then
  fail "production JAR retained demo payload after an incremental demo build"
fi
if demo_launcher_output="$(
  DEMO_DB_PASSWORD='verify-demo-password' \
    "${run_demo_script}" "${jar_path}" 2>&1
)"; then
  fail "demo launcher accepted the production JAR"
fi
[[ "${demo_launcher_output}" == *"separately packaged demo artifact"* ]] \
  || fail "demo launcher did not reject the production JAR clearly"

log_step "Testing backend release, encrypted backup and health-monitor contracts"

operations_test_root="$(mktemp -d -t after-school-operations.XXXXXX)"
verify_release_root="${operations_test_root}/server"
export VERIFY_RELEASE_ROOT="${verify_release_root}"
verify_mock_systemctl() { return 0; }
verify_mock_release_curl() {
  local current_target=""
  current_target="$(readlink "${VERIFY_RELEASE_ROOT}/current" 2>/dev/null || true)"
  if [[ "${current_target}" == "releases/release-bad" ]]; then
    return 22
  fi
  printf '{"status":"UP"}\n'
}
export -f verify_mock_systemctl verify_mock_release_curl
export AFTER_SCHOOL_SYSTEMCTL_BIN=verify_mock_systemctl
export AFTER_SCHOOL_CURL_BIN=verify_mock_release_curl
export AFTER_SCHOOL_HEALTH_ATTEMPTS=1
export AFTER_SCHOOL_HEALTH_INTERVAL_SECONDS=1
"${server_install_script}" "${jar_path}" release-one "${verify_release_root}" 30 \
  >/dev/null
[[ "$(readlink "${verify_release_root}/current")" == "releases/release-one" ]] \
  || fail "first backend release was not selected"
[[ -s "${verify_release_root}/releases/release-one/app.jar.sha256" ]] \
  || fail "backend release checksum was not installed"
if demo_release_output="$(
  "${server_install_script}" "${demo_jar_path}" demo-artifact-forbidden \
    "${verify_release_root}" 30 2>&1
)"; then
  fail "production release installer accepted the demo JAR"
fi
[[ "${demo_release_output}" == *"must not package demo payload"* ]] \
  || fail "production release installer did not explain demo JAR rejection"
if bad_release_output="$(
  "${server_install_script}" "${jar_path}" release-bad "${verify_release_root}" 30 \
    2>&1
)"; then
  fail "unhealthy backend release was accepted"
fi
[[ "${bad_release_output}" == *"restored release-one"* ]] \
  || fail "unhealthy backend release did not report automatic rollback"
[[ "$(readlink "${verify_release_root}/current")" == "releases/release-one" ]] \
  || fail "unhealthy backend release changed the selected release"
[[ ! -e "${verify_release_root}/releases/release-bad" ]] \
  || fail "failed backend release was not cleaned up"
unset AFTER_SCHOOL_SYSTEMCTL_BIN AFTER_SCHOOL_CURL_BIN \
  AFTER_SCHOOL_HEALTH_ATTEMPTS AFTER_SCHOOL_HEALTH_INTERVAL_SECONDS
unset -f verify_mock_systemctl verify_mock_release_curl

backup_test_dir="${operations_test_root}/backups"
backup_credentials="${operations_test_root}/mysql.cnf"
backup_recipients="${operations_test_root}/recipients"
restore_identity="${operations_test_root}/identity"
install -m 0600 "${config_example_dir}/mysql-backup.cnf.example" \
  "${backup_credentials}"
install -m 0600 "${config_example_dir}/age-recipients.example" \
  "${backup_recipients}"
install -m 0600 "${config_example_dir}/age-recipients.example" \
  "${restore_identity}"
verify_mock_mysqldump() {
  if [[ "${1:-}" == "--version" ]]; then
    printf 'mysqldump  Ver 8.4.6 for verify\n'
    return 0
  fi
  printf '%s\n' \
    'CREATE TABLE `flyway_schema_history` (`installed_rank` int);' \
    'INSERT INTO `flyway_schema_history` VALUES (1);' \
    'CREATE TABLE `school` (`id` bigint);' \
    'CREATE TABLE `supervision_alert` (`id` bigint, `scan_run_id` char(36));' \
    'CREATE TABLE `supervision_scan_run` (`id` char(36));'
}
verify_mock_age() {
  if [[ "${1:-}" == "--version" ]]; then
    printf 'age 1.2.1\n'
    return 0
  fi
  if [[ "${1:-}" == "--decrypt" ]]; then
    local input="${@: -1}"
    cat "${input}"
    return 0
  fi
  local output=""
  while [[ "$#" -gt 0 ]]; do
    case "$1" in
      --output)
        output="$2"
        shift 2
        ;;
      *) shift ;;
    esac
  done
  [[ -n "${output}" ]] || return 2
  cat >"${output}"
}
verify_mock_mysql() {
  if [[ "${1:-}" == "--version" ]]; then
    printf 'mysql  Ver 8.4.6 for verify\n'
    return 0
  fi
  local args="$*"
  if [[ "${args}" == *"information_schema.SCHEMATA"* ]]; then
    printf '0\n'
  elif [[ "${args}" == *"FROM \`after_school_restore_verify\`.\`flyway_schema_history\`"* ]]; then
    printf '20:0:%s\n' "${VERIFY_RESTORE_REQUIRED_VERSION_PRESENT:-1}"
  elif [[ "${args}" == *"information_schema.referential_constraints"* ]]; then
    printf '10\n'
  elif [[ "${args}" == *"table_name IN ("* && "${args}" == *"supervision_scan_run"* ]]; then
    printf '19\n'
  elif [[ "${args}" == *"FROM \`after_school_restore_verify\`.\`supervision_alert\`"* ]]; then
    printf '0\n'
  elif [[ "${args}" == *"information_schema.tables"* ]]; then
    printf '34\n'
  elif [[ "${args}" == *"--execute="* ]]; then
    return 0
  else
    cat >/dev/null
  fi
}
export -f verify_mock_mysqldump verify_mock_age verify_mock_mysql
export AFTER_SCHOOL_MYSQLDUMP_BIN=verify_mock_mysqldump
export AFTER_SCHOOL_MYSQL_BIN=verify_mock_mysql
export AFTER_SCHOOL_AGE_BIN=verify_mock_age
export MYSQL_DATABASE=after_school_service
export BACKUP_DIR="${backup_test_dir}"
export BACKUP_LOCK_FILE="${operations_test_root}/backup.lock"
export MYSQL_DEFAULTS_FILE="${backup_credentials}"
export AGE_RECIPIENTS_FILE="${backup_recipients}"
export RESTORE_REQUIRED_FLYWAY_VERSION=18
"${backup_script}" >/dev/null
backup_test_file="$(find "${backup_test_dir}" -maxdepth 1 -type f \
  -name '*.sql.gz.age' -print -quit)"
[[ -n "${backup_test_file}" && -s "${backup_test_file}" \
    && -s "${backup_test_file}.sha256" ]] \
  || fail "encrypted backup and checksum were not created"
[[ -z "$(find "${backup_test_dir}" -maxdepth 1 -type f -name '*.sql' -print -quit)" ]] \
  || fail "backup pipeline wrote plaintext SQL to disk"
backup_checksum_file="${backup_test_file}.sha256"
backup_checksum_original="$(<"${backup_checksum_file}")"
alternate_backup_file="${backup_test_dir}/other-complete-backup.sql.gz.age"
cp "${backup_test_file}" "${alternate_backup_file}"
if command -v sha256sum >/dev/null 2>&1; then
  (
    cd "${backup_test_dir}"
    sha256sum "$(basename -- "${alternate_backup_file}")" \
      >"$(basename -- "${backup_checksum_file}")"
  )
else
  (
    cd "${backup_test_dir}"
    shasum -a 256 "$(basename -- "${alternate_backup_file}")" \
      >"$(basename -- "${backup_checksum_file}")"
  )
fi
if mismatched_restore_output="$(
  "${restore_script}" "${backup_test_file}" after_school_restore_checksum_target \
    "${backup_credentials}" "${restore_identity}" 2>&1
)"; then
  fail "restore accepted a checksum that validates a different complete backup"
fi
[[ "${mismatched_restore_output}" == *"points to another file"* ]] \
  || fail "restore did not identify a checksum targeting another backup"
printf '%s\n' "${backup_checksum_original}" >"${backup_checksum_file}"
"${restore_script}" "${backup_test_file}" after_school_restore_verify \
  "${backup_credentials}" "${restore_identity}" >/dev/null
export VERIFY_RESTORE_REQUIRED_VERSION_PRESENT=0
if "${restore_script}" "${backup_test_file}" after_school_restore_verify \
    "${backup_credentials}" "${restore_identity}" >/dev/null 2>&1; then
  fail "restore accepted a backup that predates the required Flyway version"
fi
unset VERIFY_RESTORE_REQUIRED_VERSION_PRESENT
unset AFTER_SCHOOL_MYSQLDUMP_BIN AFTER_SCHOOL_MYSQL_BIN AFTER_SCHOOL_AGE_BIN \
  MYSQL_DATABASE BACKUP_LOCK_FILE MYSQL_DEFAULTS_FILE AGE_RECIPIENTS_FILE \
  RESTORE_REQUIRED_FLYWAY_VERSION
unset -f verify_mock_mysqldump verify_mock_age verify_mock_mysql

verify_mock_monitor_systemctl() {
  if [[ "${1:-}" == "is-failed" ]]; then
    [[ "${VERIFY_BACKUP_SERVICE_FAILED:-0}" == "1" ]]
    return
  fi
  return 0
}
verify_mock_monitor_curl() {
  if [[ "${VERIFY_HEALTH_FAILED:-0}" == "1" ]]; then
    return 22
  fi
  printf '{"status":"UP"}\n'
}
export -f verify_mock_monitor_systemctl verify_mock_monitor_curl
export AFTER_SCHOOL_SYSTEMCTL_BIN=verify_mock_monitor_systemctl
export AFTER_SCHOOL_CURL_BIN=verify_mock_monitor_curl
export MONITOR_STATE_FILE="${operations_test_root}/monitor-state/current"
export MYSQL_DATABASE=after_school_service
export BACKUP_MAX_AGE_HOURS=18
export VERIFY_HEALTH_FAILED=0
export VERIFY_BACKUP_SERVICE_FAILED=0
"${monitor_script}" >/dev/null
[[ "$(sed -n '1p' "${MONITOR_STATE_FILE}")" == "HEALTHY" ]] \
  || fail "health monitor did not record healthy state"
printf '%064d  %s\n' 0 "$(basename -- "${backup_test_file}")" >"${backup_checksum_file}"
if "${monitor_script}" >/dev/null 2>&1; then
  fail "health monitor accepted a malformed or mismatched backup checksum"
fi
[[ "$(sed -n '1p' "${MONITOR_STATE_FILE}")" == "FAILED" ]] \
  || fail "health monitor did not record failed checksum state"
printf '%s\n' "${backup_checksum_original}" >"${backup_checksum_file}"
"${monitor_script}" >/dev/null
[[ "$(sed -n '1p' "${MONITOR_STATE_FILE}")" == "HEALTHY" ]] \
  || fail "health monitor did not recover after checksum restoration"
export MYSQL_DATABASE=another_database
if "${monitor_script}" >/dev/null 2>&1; then
  fail "health monitor accepted a backup belonging to another database"
fi
[[ "$(sed -n '1p' "${MONITOR_STATE_FILE}")" == "FAILED" ]] \
  || fail "health monitor did not record missing target-database backup"
export MYSQL_DATABASE=after_school_service
"${monitor_script}" >/dev/null
[[ "$(sed -n '1p' "${MONITOR_STATE_FILE}")" == "HEALTHY" ]] \
  || fail "health monitor did not recover after restoring target database"
export VERIFY_BACKUP_SERVICE_FAILED=1
if "${monitor_script}" >/dev/null 2>&1; then
  fail "health monitor accepted a failed backup service run"
fi
[[ "$(sed -n '1p' "${MONITOR_STATE_FILE}")" == "FAILED" ]] \
  || fail "health monitor did not record failed backup service state"
export VERIFY_BACKUP_SERVICE_FAILED=0
"${monitor_script}" >/dev/null
[[ "$(sed -n '1p' "${MONITOR_STATE_FILE}")" == "HEALTHY" ]] \
  || fail "health monitor did not recover after a successful backup service run"
export VERIFY_HEALTH_FAILED=1
if "${monitor_script}" >/dev/null 2>&1; then
  fail "health monitor accepted failed readiness"
fi
[[ "$(sed -n '1p' "${MONITOR_STATE_FILE}")" == "FAILED" ]] \
  || fail "health monitor did not record failed state"
export VERIFY_HEALTH_FAILED=0
"${monitor_script}" >/dev/null
[[ "$(sed -n '1p' "${MONITOR_STATE_FILE}")" == "HEALTHY" ]] \
  || fail "health monitor did not record recovered state"
unset AFTER_SCHOOL_SYSTEMCTL_BIN AFTER_SCHOOL_CURL_BIN MONITOR_STATE_FILE \
  MYSQL_DATABASE BACKUP_MAX_AGE_HOURS VERIFY_HEALTH_FAILED \
  VERIFY_BACKUP_SERVICE_FAILED BACKUP_DIR
unset -f verify_mock_monitor_systemctl verify_mock_monitor_curl

log_step "Running frontend tests, typecheck, production build and audit"

(
  cd "${web_dir}"
  PATH="${node_bin_dir}:${PATH}" npm ci --prefer-offline --no-audit --no-fund
  PATH="${node_bin_dir}:${PATH}" npm test
  PATH="${node_bin_dir}:${PATH}" npm run build
  if ! PATH="${node_bin_dir}:${PATH}" npm audit --audit-level=high; then
    printf "[verify] npm audit failed once; retrying after a short delay\n" >&2
    sleep 2
    PATH="${node_bin_dir}:${PATH}" npm audit --audit-level=high
  fi
)
[[ -f "${web_dir}/dist/index.html" ]] || fail "frontend artifact was not generated"

log_step "Starting a disposable MySQL 8.4 instance"

mysql_prefix="${AFTER_SCHOOL_MYSQL84_HOME:-/opt/homebrew/opt/mysql@8.4}"
mysql_server="${mysql_prefix}/bin/mysqld"
mysql_client="${mysql_prefix}/bin/mysql"
mysql_admin="${mysql_prefix}/bin/mysqladmin"
[[ -x "${mysql_server}" && -x "${mysql_client}" && -x "${mysql_admin}" ]] \
  || fail "MySQL 8.4 not found at ${mysql_prefix}; install mysql@8.4 or set AFTER_SCHOOL_MYSQL84_HOME"

for port in "${server_port}" "${management_port}" "${mysql_port}" "${web_port}"; do
  if lsof -nP -iTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1; then
    fail "verification port ${port} is already in use"
  fi
done

run_root="$(mktemp -d -t after-school-verify.XXXXXX)"
mysql_data="${run_root}/mysql-data"
mysql_socket="${run_root}/mysql.sock"
mysql_log="${run_root}/mysql.log"
mkdir -p "${mysql_data}" "${run_root}/tmp"

log_step "Rejecting demo migrations from the real prod profile"

await_prod_guard_failure() {
  local log_path="$1"
  local expected_message="$2"
  local guard_name="$3"
  local guard_exited=false
  local guard_status=0

  for _ in {1..120}; do
    if ! kill -0 "${prod_guard_pid}" >/dev/null 2>&1; then
      if wait "${prod_guard_pid}"; then
        guard_status=0
      else
        guard_status="$?"
      fi
      prod_guard_pid=""
      guard_exited=true
      break
    fi
    sleep 0.25
  done
  if [[ "${guard_exited}" != "true" ]]; then
    terminate_pid "${prod_guard_pid}" "${guard_name}"
    prod_guard_pid=""
    fail "${guard_name} smoke test exceeded 30 seconds"
  fi
  if [[ "${guard_status}" == "0" ]]; then
    fail "prod accepted ${guard_name}"
  fi
  if ! grep -Fq "${expected_message}" "${log_path}"; then
    tail -80 "${log_path}" >&2 || true
    fail "${guard_name} did not report the expected failure"
  fi
  if grep -Fq 'Communications link failure' "${log_path}"; then
    fail "${guard_name} ran after a database connection attempt"
  fi
}

env -u SPRING_FLYWAY_LOCATIONS \
    -u SPRING_FLYWAY_OUT_OF_ORDER \
    SPRING_PROFILES_ACTIVE=prod \
    SPRING_FLYWAY_LOCATIONS='classpath:db/migration,classpath:db/demo' \
    DB_URL='jdbc:mysql://127.0.0.1:1/unused?sslMode=VERIFY_IDENTITY&connectionTimeZone=%2B08%3A00&forceConnectionTimeZoneToSession=true&connectTimeout=1000' \
    DB_USERNAME='guard_test' \
    DB_PASSWORD='guard-test-placeholder' \
    SERVER_PORT=0 \
    JAVA_HOME="${jdk21_home}" \
    PATH="${jdk21_home}/bin:${PATH}" \
    java -jar "${jar_path}" >"${run_root}/prod-location-guard.log" 2>&1 &
prod_guard_pid="$!"
await_prod_guard_failure \
  "${run_root}/prod-location-guard.log" \
  'Production Flyway locations must resolve exactly to classpath:db/migration' \
  'demo Flyway locations'

log_step "Rejecting out-of-order migrations from the real prod profile"

env -u SPRING_FLYWAY_LOCATIONS \
    -u SPRING_FLYWAY_OUT_OF_ORDER \
    SPRING_PROFILES_ACTIVE=prod \
    SPRING_FLYWAY_OUT_OF_ORDER=true \
    DB_URL='jdbc:mysql://127.0.0.1:1/unused?sslMode=VERIFY_IDENTITY&connectionTimeZone=%2B08%3A00&forceConnectionTimeZoneToSession=true&connectTimeout=1000' \
    DB_USERNAME='guard_test' \
    DB_PASSWORD='guard-test-placeholder' \
    SERVER_PORT=0 \
    JAVA_HOME="${jdk21_home}" \
    PATH="${jdk21_home}/bin:${PATH}" \
    java -jar "${jar_path}" >"${run_root}/prod-order-guard.log" 2>&1 &
prod_guard_pid="$!"
await_prod_guard_failure \
  "${run_root}/prod-order-guard.log" \
  'Production Flyway out-of-order must remain disabled' \
  'Flyway out-of-order override'

"${mysql_server}" --no-defaults \
  --initialize-insecure \
  --basedir="${mysql_prefix}" \
  --datadir="${mysql_data}" \
  --log-error="${mysql_log}"

"${mysql_server}" --no-defaults \
  --basedir="${mysql_prefix}" \
  --datadir="${mysql_data}" \
  --bind-address=127.0.0.1 \
  --port="${mysql_port}" \
  --socket="${mysql_socket}" \
  --pid-file="${run_root}/mysql.pid" \
  --tmpdir="${run_root}/tmp" \
  --log-error="${mysql_log}" \
  --default-time-zone=+00:00 \
  --skip-name-resolve \
  --mysqlx=0 &
mysql_pid="$!"

mysql_ready=false
for _ in {1..60}; do
  if ! kill -0 "${mysql_pid}" >/dev/null 2>&1; then
    fail "disposable MySQL exited before becoming ready"
  fi
  if "${mysql_admin}" --no-defaults --protocol=socket \
      --socket="${mysql_socket}" -uroot ping >/dev/null 2>&1; then
    mysql_ready=true
    break
  fi
  sleep 1
done
[[ "${mysql_ready}" == "true" ]] || fail "MySQL 8.4 did not become ready"

mysql_version="$(
  "${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
    -uroot --skip-column-names -e 'SELECT VERSION()'
)"
[[ "${mysql_version}" == 8.4.* ]] || fail "expected MySQL 8.4.x, got ${mysql_version}"
printf "[verify] MySQL: %s\n" "${mysql_version}"

db_name="after_school_demo"
upgrade_db_name="after_school_upgrade_verify"
db_user="after_school_demo"
db_password="Verify-Only-Db-Password-2026"
"${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" -uroot <<SQL
CREATE DATABASE ${db_name} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE ${upgrade_db_name} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER '${db_user}'@'127.0.0.1' IDENTIFIED BY '${db_password}';
GRANT ALL PRIVILEGES ON ${db_name}.* TO '${db_user}'@'127.0.0.1';
GRANT ALL PRIVILEGES ON ${upgrade_db_name}.* TO '${db_user}'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL

start_server() {
  local active_profile="${1:-demo}"
  local wait_for_demo_timeline="${2:-true}"
  local target_db_name="${3:-${db_name}}"
  local flyway_target="${4:-latest}"
  local bootstrap_enabled="${5:-false}"
  local demo_timeline_enabled="true"
  if [[ "${flyway_target}" == "15" ]]; then
    demo_timeline_enabled="false"
  fi
  local active_profile_value=""
  local runtime_jar="${jar_path}"
  local inherited_db_url=""
  local inherited_db_username="${db_user}"
  local inherited_db_password="${db_password}"
  if [[ "${active_profile}" != "default" ]]; then
    active_profile_value="${active_profile}"
  fi
  if [[ "${active_profile}" == "demo" ]]; then
    runtime_jar="${demo_jar_path}"
    # A successful startup proves application-demo.yml does not inherit DB_URL.
    inherited_db_url="jdbc:mysql://127.0.0.1:1/production_url_must_not_be_used?connectTimeout=1000"
    inherited_db_username="production_url_must_not_be_used"
    inherited_db_password="production-url-must-not-be-used"
  else
    inherited_db_url="jdbc:mysql://127.0.0.1:${mysql_port}/${target_db_name}?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=%2B08%3A00&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&useSSL=false"
  fi
  : >"${run_root}/server.log"
  (
    cd "${server_dir}"
    exec env -u SPRING_FLYWAY_LOCATIONS \
      -u SPRING_FLYWAY_OUT_OF_ORDER \
      -u SPRING_PROFILES_ACTIVE \
      SPRING_PROFILES_ACTIVE="${active_profile_value}" \
      JAVA_HOME="${jdk21_home}" \
      PATH="${jdk21_home}/bin:${PATH}" \
      SERVER_ADDRESS=127.0.0.1 \
      SERVER_PORT="${server_port}" \
      MANAGEMENT_SERVER_ADDRESS=127.0.0.1 \
      MANAGEMENT_SERVER_PORT="${management_port}" \
      MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE='health,prometheus' \
      MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS=never \
      MANAGEMENT_ENDPOINT_HEALTH_SHOW_COMPONENTS=never \
      MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED=true \
      MANAGEMENT_ENDPOINT_HEALTH_PROBES_ADD_ADDITIONAL_PATHS=true \
      MANAGEMENT_ENDPOINT_HEALTH_GROUP_LIVENESS_INCLUDE=livenessState \
      MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE=readinessState,db \
      SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT=2000 \
      SPRING_DATASOURCE_HIKARI_VALIDATION_TIMEOUT=1000 \
      DB_URL="${inherited_db_url}" \
      DB_USERNAME="${inherited_db_username}" \
      DB_PASSWORD="${inherited_db_password}" \
      DEMO_MYSQL_PORT="${mysql_port}" \
      DEMO_DB_NAME="${target_db_name}" \
      DEMO_DB_USERNAME="${db_user}" \
      DEMO_DB_PASSWORD="${db_password}" \
      SPRING_FLYWAY_TARGET="${flyway_target}" \
      APP_DEMO_TIMELINE_ENABLED="${demo_timeline_enabled}" \
      APP_BOOTSTRAP_ENABLED="${bootstrap_enabled}" \
      APP_BOOTSTRAP_USERNAME='initial_school_admin_verify' \
      APP_BOOTSTRAP_PASSWORD='InitialSchoolAdminVerify@2026' \
      APP_BOOTSTRAP_DISPLAY_NAME='初始验收教务管理员' \
      APP_BOOTSTRAP_SCHOOL_CODE='BOOTSTRAP-VERIFY' \
      APP_BOOTSTRAP_SCHOOL_NAME='生产初始化验收学校' \
      APP_BOOTSTRAP_DISTRICT_CODE='VERIFY-DISTRICT' \
      SPRINGDOC_ENABLED=false \
      APP_CORS_ALLOWED_ORIGIN="http://127.0.0.1:${web_port}" \
      java -jar "${runtime_jar}"
  ) >"${run_root}/server.log" 2>&1 &
  server_pid="$!"

  local ready=false
  for _ in {1..60}; do
    if ! kill -0 "${server_pid}" >/dev/null 2>&1; then
      fail "backend exited before becoming ready"
    fi
    if curl --noproxy '*' -fsS \
        "http://127.0.0.1:${server_port}/api/public/system-info" >/dev/null 2>&1 \
        && curl --noproxy '*' -fsS \
          "http://127.0.0.1:${server_port}/livez" \
          | jq -e '.status == "UP"' >/dev/null 2>&1 \
        && curl --noproxy '*' -fsS \
          "http://127.0.0.1:${server_port}/readyz" \
          | jq -e '.status == "UP"' >/dev/null 2>&1 \
        && curl --noproxy '*' -fsS \
          "http://127.0.0.1:${management_port}/actuator/prometheus" \
          | grep -Eq '^jvm_info'; then
      if [[ "${wait_for_demo_timeline}" != "true" ]] \
          || grep -Fq 'Demo timeline check completed' "${run_root}/server.log"; then
        ready=true
        break
      fi
    fi
    sleep 1
  done
  [[ "${ready}" == "true" ]] || fail "backend did not become ready"
}

stop_server() {
  terminate_pid "${server_pid}" "backend"
  server_pid=""
}

log_step "Proving a populated demo V15 database upgrades intact to V18"

start_server demo false "${upgrade_db_name}" 15
upgrade_v15_history="$(
  "${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
    -uroot --skip-column-names "${upgrade_db_name}" -e \
    "SELECT CONCAT(MAX(CAST(version AS UNSIGNED)), ':', COUNT(*))
     FROM flyway_schema_history WHERE success = 1"
)"
[[ "${upgrade_v15_history}" == "15:16" ]] \
  || fail "expected a populated V15 demo database before upgrade: ${upgrade_v15_history}"
upgrade_v15_receipt="$(
  "${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
    -uroot --skip-column-names "${upgrade_db_name}" -e \
    "SELECT CONCAT(
       (SELECT COUNT(*) FROM school), ':',
       (SELECT COUNT(*) FROM student), ':',
       (SELECT COUNT(*) FROM enrollment), ':',
       (SELECT COUNT(*) FROM operation_audit), ':',
       (SELECT COUNT(*) FROM academic_term))"
)"
[[ "${upgrade_v15_receipt}" =~ ^2:[1-9][0-9]*:[1-9][0-9]*:[1-9][0-9]*:[1-9][0-9]*$ ]] \
  || fail "V15 upgrade fixture was not populated: ${upgrade_v15_receipt}"
stop_server

start_server demo true "${upgrade_db_name}" latest
upgrade_v18_history="$(
  "${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
    -uroot --skip-column-names "${upgrade_db_name}" -e \
    "SELECT MAX(CAST(version AS UNSIGNED))
     FROM flyway_schema_history WHERE success = 1"
)"
[[ "${upgrade_v18_history}" == "21" ]] \
  || fail "populated V15 database did not upgrade through the current demo schema"
upgrade_retained_receipt="$(
  "${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
    -uroot --skip-column-names "${upgrade_db_name}" -e \
    "SELECT CONCAT(
       (SELECT COUNT(*) FROM school), ':',
       (SELECT COUNT(*) FROM student), ':',
       (SELECT COUNT(*) FROM enrollment), ':',
       (SELECT COUNT(*) FROM operation_audit), ':',
       (SELECT COUNT(*) FROM academic_term WHERE school_id IS NOT NULL), ':',
       (SELECT COUNT(*) FROM student_grade))"
)"
IFS=: read -r upgrade_school_count upgrade_student_count \
  upgrade_enrollment_count upgrade_audit_count upgrade_term_count \
  upgrade_grade_count <<<"${upgrade_retained_receipt}"
IFS=: read -r v15_school_count v15_student_count v15_enrollment_count \
  v15_audit_count v15_term_count <<<"${upgrade_v15_receipt}"
[[ "${upgrade_school_count}" -eq "${v15_school_count}" \
    && "${upgrade_student_count}" -ge "${v15_student_count}" \
    && "${upgrade_enrollment_count}" -ge "${v15_enrollment_count}" \
    && "${upgrade_audit_count}" -ge "${v15_audit_count}" \
    && "${upgrade_term_count}" -ge "${v15_term_count}" \
    && "${upgrade_grade_count}" -ge 1 ]] \
  || fail "V15 business data was not retained by the V18/V21 upgrade"
upgrade_scoped_term_receipt="$(
  "${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
    -uroot --skip-column-names "${upgrade_db_name}" -e \
    "SELECT CONCAT(
         COUNT(DISTINCT plan.term_id), ':',
         COUNT(DISTINCT term.term_code), ':',
         SUM(plan.school_id <> term.school_id)
     )
     FROM school_service_plan plan
     JOIN academic_term term ON term.id = plan.term_id
     WHERE plan.id IN (1, 2)"
)"
[[ "${upgrade_scoped_term_receipt}" == "2:1:0" ]] \
  || fail "V15 shared academic terms were not split by school during upgrade"
stop_server

log_step "Migrating the fresh database through the production-only V18 schema"

start_server default false "${db_name}" latest true
production_history="$(
  "${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
    -uroot --skip-column-names "${db_name}" -e \
    "SELECT CONCAT(
         COUNT(*), ':',
         COALESCE(SUM(version IN ('4', '8')), 0), ':',
         MAX(CAST(version AS UNSIGNED))
     )
     FROM flyway_schema_history
     WHERE success = 1"
)"
[[ "${production_history}" == "14:0:18" ]] \
  || fail "default profile did not stop at the production-only V18 schema: ${production_history}"
production_demo_users="$(
  "${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
    -uroot --skip-column-names "${db_name}" -e \
    "SELECT COUNT(*) FROM sys_user
     WHERE username IN ('admin', 'school_admin', 'teacher_wang', 'parent_chen')"
)"
[[ "${production_demo_users}" == "0" ]] \
  || fail "default profile unexpectedly inserted demo accounts"
production_bootstrap_receipt="$(
  "${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
    -uroot --skip-column-names "${db_name}" -e \
    "SELECT CONCAT(
         school.school_code, ':', role.role_code, ':',
         user_account.enabled, ':', role.enabled
     )
     FROM sys_user user_account
     JOIN school ON school.id = user_account.school_id
     JOIN sys_role role ON role.id = user_account.role_id
     WHERE user_account.username = 'initial_school_admin_verify'"
)"
[[ "${production_bootstrap_receipt}" == "BOOTSTRAP-VERIFY:SCHOOL_ADMIN:1:1" ]] \
  || fail "empty production database bootstrap did not create an active school admin"
prod_base_url="http://127.0.0.1:${server_port}/api"
bootstrap_cookie_jar="${run_root}/bootstrap-admin.cookies"
curl --noproxy '*' -fsS -c "${bootstrap_cookie_jar}" -b "${bootstrap_cookie_jar}" \
  "${prod_base_url}/public/csrf" >"${run_root}/bootstrap-csrf.json"
bootstrap_csrf_token="$(
  awk '$6 == "XSRF-TOKEN" { value = $7 } END { print value }' \
    "${bootstrap_cookie_jar}"
)"
bootstrap_login_status="$(
  curl --noproxy '*' -sS -o "${run_root}/bootstrap-login.json" -w '%{http_code}' \
    -c "${bootstrap_cookie_jar}" -b "${bootstrap_cookie_jar}" \
    -X POST -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: ${bootstrap_csrf_token}" \
    --data-binary '{"username":"initial_school_admin_verify","password":"InitialSchoolAdminVerify@2026","expectedRole":"SCHOOL_ADMIN"}' \
    "${prod_base_url}/auth/login"
)"
[[ "${bootstrap_login_status}" == "200" ]] \
  || fail "bootstrapped production school admin could not log in"
jq -e '.role == "SCHOOL_ADMIN" and .schoolId != null' \
  "${run_root}/bootstrap-login.json" >/dev/null \
  || fail "bootstrapped production account received the wrong role or scope"
stop_server

log_step "Continuing with the populated demo database upgraded from V15"

db_name="${upgrade_db_name}"
start_server
base_url="http://127.0.0.1:${server_port}/api"
curl --noproxy '*' -fsS "${base_url}/public/system-info" \
  | jq -e '.status == "ready"' >/dev/null || fail "system-info contract failed"

table_count="$(
  "${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
    -uroot --skip-column-names -e \
    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${db_name}' AND table_name != 'flyway_schema_history'"
)"
[[ "${table_count}" == "34" ]] || fail "expected 34 domain tables, found ${table_count}"
migration_count="$(
  "${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
    -uroot --skip-column-names "${db_name}" -e \
    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1"
)"
[[ "${migration_count}" == "21" ]] || fail "expected 21 successful Flyway migrations"
scan_run_fk_count="$(
  "${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
    -uroot --skip-column-names "${db_name}" -e \
    "SELECT COUNT(*)
     FROM information_schema.referential_constraints
     WHERE constraint_schema = '${db_name}'
       AND table_name = 'supervision_alert'
       AND constraint_name = 'fk_supervision_alert_scan_run'
       AND referenced_table_name = 'supervision_scan_run'"
)"
[[ "${scan_run_fk_count}" == "1" ]] \
  || fail "supervision alerts must reference a persisted scan run"
scan_run_orphan_count="$(
  "${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
    -uroot --skip-column-names "${db_name}" -e \
    "SELECT COUNT(*)
     FROM supervision_alert AS alert
     LEFT JOIN supervision_scan_run AS scan_run
       ON scan_run.id = alert.scan_run_id
     WHERE scan_run.id IS NULL"
)"
[[ "${scan_run_orphan_count}" == "0" ]] \
  || fail "demo switch left supervision alerts without a scan-run parent"
demo_seeded_scan_run="$(
  "${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
    -uroot --skip-column-names "${db_name}" -e \
    "SELECT CONCAT(trigger_source, ':', status, ':', candidate_count, ':', created_count)
     FROM supervision_scan_run
     WHERE id = '11111111-1111-4111-8111-111111111111'"
)"
[[ "${demo_seeded_scan_run}" == "SCHEDULED:SUCCESS:1:1" ]] \
  || fail "demo scan-run bridge did not preserve the V8 alert provenance"
demo_operational_offerings="$(
  "${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
    -uroot --skip-column-names "${db_name}" -e \
    "SELECT COUNT(*)
     FROM course_offering
     WHERE offering_code IN (
         'O-DEMO-ART-001',
         'O-DEMO-TECH-001',
         'O-DEMO-SCI-001'
     )
       AND status = 'PUBLISHED'
       AND TIMESTAMP(
           DATE_ADD(
               start_date,
               INTERVAL MOD(week_day - WEEKDAY(start_date) - 1 + 7, 7) DAY
           ),
           start_time
       ) > CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+08:00')"
)"
[[ "${demo_operational_offerings}" == "3" ]] \
  || fail "demo profile did not provide three enrollable future offerings"
demo_future_sessions="$(
  "${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
    -uroot --skip-column-names "${db_name}" -e \
    "SELECT COUNT(*)
     FROM lesson_session
     WHERE id IN (2, 3)
       AND status = 'SCHEDULED'
       AND TIMESTAMP(session_date, start_time)
           > CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+08:00')"
)"
[[ "${demo_future_sessions}" == "2" ]] \
  || fail "demo profile did not provide two future leave/teaching sessions"

status_of() {
  local output_file="$1"
  shift
  curl --noproxy '*' -sS -o "${output_file}" -w '%{http_code}' "$@"
}

csrf_for() {
  local cookie_jar="$1"
  local csrf_file="${run_root}/csrf.json"
  curl --noproxy '*' -fsS -c "${cookie_jar}" -b "${cookie_jar}" \
    "${base_url}/public/csrf" >"${csrf_file}"
  jq -e '
    .headerName == "X-XSRF-TOKEN"
    and .parameterName == "_csrf"
    and (.token == null)
  ' "${csrf_file}" >/dev/null || fail "invalid CSRF bootstrap response"
  local raw_token
  raw_token="$(awk '$6 == "XSRF-TOKEN" { value = $7 } END { print value }' "${cookie_jar}")"
  [[ -n "${raw_token}" ]] || fail "CSRF bootstrap did not set XSRF-TOKEN cookie"
  printf '%s' "${raw_token}"
}

write_api() {
  local expected_status="$1"
  local method="$2"
  local path="$3"
  local cookie_jar="$4"
  local body="$5"
  local output_file="$6"
  local csrf_token
  csrf_token="$(csrf_for "${cookie_jar}")"
  local actual_status
  actual_status="$(
    status_of "${output_file}" \
      -c "${cookie_jar}" -b "${cookie_jar}" \
      -X "${method}" \
      -H 'Content-Type: application/json' \
      -H "X-XSRF-TOKEN: ${csrf_token}" \
      --data-binary "${body}" \
      "${base_url}${path}"
  )"
  [[ "${actual_status}" == "${expected_status}" ]] \
    || fail "${method} ${path} returned ${actual_status}, expected ${expected_status}: $(cat "${output_file}")"
}

upload_api() {
  local expected_status="$1"
  local path="$2"
  local cookie_jar="$3"
  local file_path="$4"
  local content_type="$5"
  local output_file="$6"
  local csrf_token
  csrf_token="$(csrf_for "${cookie_jar}")"
  local actual_status
  actual_status="$(
    status_of "${output_file}" \
      -c "${cookie_jar}" -b "${cookie_jar}" \
      -X POST \
      -H "X-XSRF-TOKEN: ${csrf_token}" \
      -F "file=@${file_path};type=${content_type}" \
      "${base_url}${path}"
  )"
  [[ "${actual_status}" == "${expected_status}" ]] \
    || fail "POST ${path} returned ${actual_status}, expected ${expected_status}: $(cat "${output_file}")"
}

assert_xlsx() {
  local file_path="$1"
  local min_data_rows="${2:-0}"
  local expected_header="${3:-}"
  [[ -s "${file_path}" ]] || fail "Excel file is empty: ${file_path}"
  local verifier_args=("${file_path}" --min-data-rows "${min_data_rows}")
  if [[ -n "${expected_header}" ]]; then
    verifier_args+=(--expect-header "${expected_header}")
  fi
  "${jdk21_home}/bin/java" "${verify_xlsx_script}" "${verifier_args[@]}" >/dev/null \
    || fail "independent XLSX parsing failed: ${file_path}"
}

login_as() {
  local username="$1"
  local expected_role="$2"
  local cookie_jar="$3"
  local password="${4:-${demo_password}}"
  local output_file="${run_root}/login-${username}.json"
  local body
  body="$(jq -nc --arg username "${username}" --arg password "${password}" \
    --arg expectedRole "${expected_role}" \
    '{username:$username,password:$password,expectedRole:$expectedRole}')"
  write_api 200 POST /auth/login "${cookie_jar}" "${body}" "${output_file}"
  jq -e --arg role "${expected_role}" '.role == $role' "${output_file}" >/dev/null \
    || fail "${username} did not receive role ${expected_role}"
  local me_status
  me_status="$(status_of "${run_root}/me-${username}.json" \
    -c "${cookie_jar}" -b "${cookie_jar}" "${base_url}/auth/me")"
  [[ "${me_status}" == "200" ]] || fail "${username} session was not persisted"
}

log_step "Verifying authentication, CSRF, CORS and four-role data scopes"

active_roles="$(
  "${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
    -uroot --skip-column-names "${db_name}" -e \
    "SELECT GROUP_CONCAT(role_code ORDER BY role_code SEPARATOR ',')
     FROM sys_role WHERE enabled = TRUE"
)"
[[ "${active_roles}" == "GUARDIAN,SCHOOL_ADMIN,STUDENT,TEACHER" ]] \
  || fail "active roles are not exactly the four opening-report roles: ${active_roles}"
if grep -Eq "path:[[:space:]]*'/regulator'|roles:[[:space:]]*\['REGULATOR'\]" \
    "${web_dir}/src/router/index.ts"; then
  fail "frontend router still exposes a regulator role route"
fi

unauth_status="$(status_of "${run_root}/unauth.json" "${base_url}/auth/me")"
[[ "${unauth_status}" == "401" ]] || fail "unauthenticated /auth/me must return 401"

bad_jar="${run_root}/bad.cookies"
bad_body='{"username":"admin","password":"wrong-password","expectedRole":"SCHOOL_ADMIN"}'
write_api 401 POST /auth/login "${bad_jar}" "${bad_body}" "${run_root}/bad-login.json"
jq -e '.code == "INVALID_CREDENTIALS"' "${run_root}/bad-login.json" >/dev/null \
  || fail "bad credentials error contract failed"

disabled_regulator_body="$(jq -nc --arg password "${demo_password}" \
  '{username:"admin",password:$password,expectedRole:"SCHOOL_ADMIN"}')"
write_api 401 POST /auth/login "${bad_jar}" "${disabled_regulator_body}" \
  "${run_root}/disabled-regulator-login.json"
invalid_fifth_role_body="$(jq -nc --arg password "${demo_password}" \
  '{username:"admin",password:$password,expectedRole:"REGULATOR"}')"
write_api 400 POST /auth/login "${bad_jar}" "${invalid_fifth_role_body}" \
  "${run_root}/invalid-fifth-role-login.json"

csrf_missing_status="$(
  status_of "${run_root}/csrf-missing.json" \
    -X POST -H 'Content-Type: application/json' --data-binary '{}' \
    "${base_url}/auth/login"
)"
[[ "${csrf_missing_status}" == "403" ]] || fail "unsafe request without CSRF must return 403"

allowed_cors="$(
  curl --noproxy '*' -sS -D - -o /dev/null \
    -X OPTIONS \
    -H "Origin: http://127.0.0.1:${web_port}" \
    -H 'Access-Control-Request-Method: POST' \
    "${base_url}/auth/logout"
)"
grep -Fiq "Access-Control-Allow-Origin: http://127.0.0.1:${web_port}" <<<"${allowed_cors}" \
  || fail "allowed CORS origin was not accepted"
blocked_cors="$(
  curl --noproxy '*' -sS -D - -o /dev/null \
    -X OPTIONS \
    -H 'Origin: https://malicious.invalid' \
    -H 'Access-Control-Request-Method: POST' \
    "${base_url}/auth/logout"
)"
if grep -Fiq 'Access-Control-Allow-Origin: https://malicious.invalid' <<<"${blocked_cors}"; then
  fail "untrusted CORS origin was accepted"
fi

admin_jar="${run_root}/admin.cookies"
second_admin_jar="${run_root}/second-admin.cookies"
parent_jar="${run_root}/parent.cookies"
teacher_jar="${run_root}/teacher.cookies"
student_one_jar="${run_root}/student-one.cookies"
student_two_jar="${run_root}/student-two.cookies"
login_as school_admin SCHOOL_ADMIN "${admin_jar}"
login_as school_admin_2 SCHOOL_ADMIN "${second_admin_jar}"

login_wall_clock_delta="$(
  "${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
    -uroot --skip-column-names "${db_name}" -e \
    "SELECT ABS(TIMESTAMPDIFF(SECOND, last_login_at, DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 8 HOUR))) FROM sys_user WHERE username='school_admin'"
)"
[[ "${login_wall_clock_delta}" -le 30 ]] \
  || fail "application and database DATETIME clocks are not both using +08:00"

admin_classes_status="$(
  status_of "${run_root}/admin-classes.json" \
    -c "${admin_jar}" -b "${admin_jar}" "${base_url}/classes"
)"
[[ "${admin_classes_status}" == "200" ]] || fail "school admin could not list classes"
jq -e 'length > 0 and all(.schoolId == 1)' "${run_root}/admin-classes.json" >/dev/null \
  || fail "school admin class scope leaked another school"
cross_school_status="$(
  status_of "${run_root}/cross-school.json" \
    -c "${admin_jar}" -b "${admin_jar}" "${base_url}/classes?schoolId=2"
)"
[[ "${cross_school_status}" == "403" ]] || fail "cross-school query must return 403"

class_body='{"schoolId":1,"className":"验收五年级一班","grade":5,"schoolYear":"2026-2027","status":"ACTIVE"}'
write_api 201 POST /classes "${admin_jar}" "${class_body}" "${run_root}/created-class.json"
class_id="$(jq -r '.id' "${run_root}/created-class.json")"

teacher_body='{"schoolId":1,"teacherNo":"T-VERIFY-001","fullName":"验收教师","username":"verify_teacher","password":"VerifyTeacher@2026","phone":"13800000901","title":"课程教师","status":"ACTIVE"}'
write_api 201 POST /teachers "${admin_jar}" "${teacher_body}" "${run_root}/created-teacher.json"
verify_teacher_id="$(jq -r '.id' "${run_root}/created-teacher.json")"

student_body="$(jq -nc --argjson classId "${class_id}" \
  '{schoolId:1,classId:$classId,studentNo:"S-VERIFY-001",fullName:"验收学生",username:"verify_student",password:"VerifyStudent@2026",gender:"FEMALE",dateOfBirth:"2015-08-08",status:"ACTIVE"}')"
write_api 201 POST /students "${admin_jar}" "${student_body}" "${run_root}/created-student.json"
verify_student_id="$(jq -r '.id' "${run_root}/created-student.json")"

guardian_body="$(jq -nc --argjson studentId "${verify_student_id}" \
  '{schoolId:1,fullName:"验收家长",mobile:"13800000902",username:"verify_parent",password:"VerifyParent@2026",studentIds:[$studentId],relationship:"母亲",primary:true,status:"ACTIVE"}')"
write_api 201 POST /guardians "${admin_jar}" "${guardian_body}" "${run_root}/created-guardian.json"
jq -e --argjson studentId "${verify_student_id}" \
  '.studentIds | index($studentId) != null' "${run_root}/created-guardian.json" >/dev/null \
  || fail "guardian-student binding was not returned"

course_body='{"schoolId":1,"courseCode":"C-VERIFY-001","courseName":"验收综合课程","category":"综合实践","description":"一次性验收课程","targetGradeMin":1,"targetGradeMax":6,"defaultCapacity":20,"status":"ACTIVE"}'
write_api 201 POST /courses "${admin_jar}" "${course_body}" "${run_root}/created-course.json"
course_id="$(jq -r '.id' "${run_root}/created-course.json")"

rule_dates="$(
  TZ=Asia/Shanghai "${node_bin_dir}/node" -e '
    const pad = (value) => String(value).padStart(2, "0")
    const date = (value) =>
      `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())}`
    const dateTime = (value) =>
      `${date(value)}T${pad(value.getHours())}:${pad(value.getMinutes())}:${pad(value.getSeconds())}`
    const now = new Date()
    const fixtureYear = now.getFullYear() + 2
    const start = new Date(fixtureYear, 0, 1, 0, 0, 0)
    const end = new Date(fixtureYear, 5, 30, 23, 59, 59)
    const openStart = new Date(now)
    openStart.setDate(openStart.getDate() - 1)
    const openEnd = new Date(fixtureYear - 1, 11, 31, 23, 59, 59)
    const futureStart = new Date(now.getFullYear() + 1, 0, 1, 0, 0, 0)
    process.stdout.write(
      `${fixtureYear} ${date(start)} ${date(end)} ${dateTime(openStart)} ${dateTime(openEnd)} ${dateTime(futureStart)}`,
    )
  '
)"
read -r rule_year rule_start_date rule_end_date \
  rule_enrollment_start rule_enrollment_end rule_future_enrollment_start \
  <<<"${rule_dates}"
rule_term="VERIFY-${rule_year}"
strict_term_body="$(jq -nc \
  --arg termCode "${rule_term}" \
  --arg termName "${rule_year} 验收学期" \
  --arg startDate "${rule_start_date}" \
  --arg endDate "${rule_end_date}" \
  '{termCode:$termCode,termName:$termName,startDate:$startDate,endDate:$endDate,status:"DRAFT"}')"
write_api 201 POST /terms "${admin_jar}" "${strict_term_body}" \
  "${run_root}/strict-term-draft.json"
strict_term_id="$(jq -r '.id // empty' "${run_root}/strict-term-draft.json")"
strict_term_body="$(jq '.status = "ACTIVE"' <<<"${strict_term_body}")"
write_api 200 PUT "/terms/${strict_term_id}" "${admin_jar}" \
  "${strict_term_body}" "${run_root}/strict-term-active.json"

strict_term_school_two_body="$(jq '.status = "DRAFT" | .termName = "第二学校同码验收学期"' \
  <<<"${strict_term_body}")"
write_api 201 POST /terms "${second_admin_jar}" \
  "${strict_term_school_two_body}" "${run_root}/strict-term-school-two.json"
strict_term_school_two_id="$(jq -r '.id // empty' \
  "${run_root}/strict-term-school-two.json")"
[[ "${strict_term_school_two_id}" =~ ^[0-9]+$ \
    && "${strict_term_school_two_id}" != "${strict_term_id}" ]] \
  || fail "same term code was not independently created for the second school"
write_api 404 PUT "/terms/${strict_term_id}" "${second_admin_jar}" \
  "${strict_term_school_two_body}" "${run_root}/cross-school-term-update.json"
second_school_terms_status="$(
  status_of "${run_root}/second-school-terms.json" \
    -c "${second_admin_jar}" -b "${second_admin_jar}" "${base_url}/terms"
)"
[[ "${second_school_terms_status}" == "200" ]] \
  || fail "second school admin could not list own terms"
jq -e --argjson ownId "${strict_term_school_two_id}" \
  --argjson foreignId "${strict_term_id}" \
  'any(.[]; .id == $ownId and .schoolId == 2)
   and all(.id != $foreignId and .schoolId == 2)' \
  "${run_root}/second-school-terms.json" >/dev/null \
  || fail "academic term listing leaked another school"

strict_plan_body="$(jq -nc --argjson termId "${strict_term_id}" \
  '{schoolId:1,termId:$termId,planCode:"PLAN-VERIFY-STRICT",planName:"开题报告严格验收计划",description:"包含课程类型、开班规模和师资配置"}')"
write_api 201 POST /service-plans "${admin_jar}" "${strict_plan_body}" \
  "${run_root}/strict-plan.json"
strict_plan_id="$(jq -r '.id // empty' "${run_root}/strict-plan.json")"
strict_plan_item_body='{"category":"综合实践","plannedCourseCount":8,"plannedClassCount":12,"capacityPerClass":50,"plannedTeacherCount":3,"notes":"真实 HTTP 备案明细验收"}'
write_api 201 POST "/service-plans/${strict_plan_id}/items" "${admin_jar}" \
  "${strict_plan_item_body}" "${run_root}/strict-plan-item.json"
write_api 200 POST "/service-plans/${strict_plan_id}/transitions" "${admin_jar}" \
  '{"targetStatus":"SUBMITTED"}' "${run_root}/strict-plan-submitted.json"
write_api 200 POST "/service-plans/${strict_plan_id}/transitions" "${admin_jar}" \
  '{"targetStatus":"FILED"}' "${run_root}/strict-plan-filed.json"
write_api 200 POST "/service-plans/${strict_plan_id}/transitions" "${admin_jar}" \
  '{"targetStatus":"ACTIVE"}' "${run_root}/strict-plan-active.json"
[[ "${strict_term_id}" =~ ^[0-9]+$ && "${strict_plan_id}" =~ ^[0-9]+$ ]] \
  || fail "strict opening-report term and filed plan were not created"
initial_offering_status_checked=false

create_offering() {
  local code="$1"
  local teacher_id="$2"
  local week_day="$3"
  local grade_course_id="$4"
  local capacity="$5"
  local enrollment_start="$6"
  local enrollment_end="$7"
  local output="$8"
  local planning_term_id="${9:-${strict_term_id}}"
  local planning_plan_id="${10:-${strict_plan_id}}"
  local body
  local room_body
  local room_id
  room_body="$(jq -nc \
    --arg roomCode "ROOM-${code}" \
    --arg roomName "${code} 验收教室" \
    --argjson capacity "$((capacity > 50 ? capacity : 50))" \
    '{schoolId:1,roomCode:$roomCode,roomName:$roomName,location:"自动验收区",capacity:$capacity,status:"ACTIVE"}')"
  write_api 201 POST /rooms "${admin_jar}" "${room_body}" "${output}.room"
  room_id="$(jq -r '.id // empty' "${output}.room")"
  [[ "${room_id}" =~ ^[0-9]+$ ]] || fail "offering room was not created: ${code}"
  body="$(jq -nc \
    --argjson courseId "${grade_course_id}" \
    --argjson teacherId "${teacher_id}" \
    --arg code "${code}" \
    --arg term "${rule_term}" \
    --arg startDate "${rule_start_date}" \
    --arg endDate "${rule_end_date}" \
    --argjson weekDay "${week_day}" \
    --argjson capacity "${capacity}" \
    --arg enrollmentStart "${enrollment_start}" \
    --arg enrollmentEnd "${enrollment_end}" \
    --argjson termId "${planning_term_id}" \
    --argjson planId "${planning_plan_id}" \
    --argjson roomId "${room_id}" \
    '{schoolId:1,courseId:$courseId,teacherId:$teacherId,offeringCode:$code,term:$term,weekDay:$weekDay,startTime:"16:30:00",endTime:"17:30:00",startDate:$startDate,endDate:$endDate,enrollmentStart:$enrollmentStart,enrollmentEnd:$enrollmentEnd,capacity:$capacity,classroom:"验收教室",status:"DRAFT",termId:$termId,planId:$planId,roomId:$roomId}')"
  if [[ "${initial_offering_status_checked}" != "true" ]]; then
    local invalid_initial_body
    invalid_initial_body="$(jq '.status = "PUBLISHED"' <<<"${body}")"
    write_api 400 POST /offerings "${admin_jar}" "${invalid_initial_body}" \
      "${run_root}/invalid-initial-offering.json"
    jq -e '.code == "INITIAL_OFFERING_STATUS_INVALID"' \
      "${run_root}/invalid-initial-offering.json" >/dev/null \
      || fail "new offering must reject any initial status except DRAFT"
    initial_offering_status_checked=true
  fi
  write_api 201 POST /offerings "${admin_jar}" "${body}" "${output}.draft"
  local offering_id
  offering_id="$(jq -r '.id' "${output}.draft")"
  [[ "${offering_id}" =~ ^[0-9]+$ ]] \
    || fail "draft offering creation returned no id: ${code}"
  if [[ "${initial_offering_status_checked}" == "true" ]] \
      && [[ ! -f "${run_root}/unplanned-publish-checked" ]]; then
    local unplanned_publish_body
    unplanned_publish_body="$(jq 'del(.termId,.planId,.roomId) | .status = "PUBLISHED"' <<<"${body}")"
    write_api 409 PUT "/offerings/${offering_id}" "${admin_jar}" \
      "${unplanned_publish_body}" "${run_root}/unplanned-publish.json"
    jq -e '.code == "PLANNING_REFERENCES_REQUIRED"' \
      "${run_root}/unplanned-publish.json" >/dev/null \
      || fail "backend must reject publishing an offering without filed planning references"
    touch "${run_root}/unplanned-publish-checked"
  fi
  body="$(jq '.status = "PUBLISHED"' <<<"${body}")"
  write_api 200 PUT "/offerings/${offering_id}" "${admin_jar}" "${body}" "${output}"
  jq -e '.status == "PUBLISHED"' "${output}" >/dev/null \
    || fail "draft offering did not transition to PUBLISHED: ${code}"
}

create_offering O-VERIFY-BASE 1 2 "${course_id}" 20 \
  "${rule_enrollment_start}" "${rule_enrollment_end}" \
  "${run_root}/base-offering.json"
base_offering_id="$(jq -r '.id' "${run_root}/base-offering.json")"

create_offering O-VERIFY-CAPACITY 1 5 "${course_id}" 1 \
  "${rule_enrollment_start}" "${rule_enrollment_end}" \
  "${run_root}/capacity-offering.json"
capacity_offering_id="$(jq -r '.id' "${run_root}/capacity-offering.json")"

grade_course_body='{"schoolId":1,"courseCode":"C-VERIFY-GRADE","courseName":"高年级验收课程","category":"综合实践","description":"年级规则验收","targetGradeMin":4,"targetGradeMax":6,"defaultCapacity":20,"status":"ACTIVE"}'
write_api 201 POST /courses "${admin_jar}" "${grade_course_body}" "${run_root}/grade-course.json"
grade_course_id="$(jq -r '.id' "${run_root}/grade-course.json")"
create_offering O-VERIFY-GRADE 1 1 "${grade_course_id}" 20 \
  "${rule_enrollment_start}" "${rule_enrollment_end}" \
  "${run_root}/grade-offering.json"
grade_offering_id="$(jq -r '.id' "${run_root}/grade-offering.json")"

create_offering O-VERIFY-FUTURE 1 7 "${course_id}" 20 \
  "${rule_future_enrollment_start}" "${rule_enrollment_end}" \
  "${run_root}/future-offering.json"
future_offering_id="$(jq -r '.id' "${run_root}/future-offering.json")"

create_offering O-VERIFY-CONFLICT "${verify_teacher_id}" 2 "${course_id}" 20 \
  "${rule_enrollment_start}" "${rule_enrollment_end}" \
  "${run_root}/conflict-offering.json"
conflict_offering_id="$(jq -r '.id' "${run_root}/conflict-offering.json")"

attendance_dates="$(
  TZ=Asia/Shanghai "${node_bin_dir}/node" -e '
    const pad = (value) => String(value).padStart(2, "0")
    const format = (date) =>
      `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
    const now = new Date()
    const start = new Date(`${process.argv[1]}T00:00:00`)
    const end = new Date(start)
    const past = new Date(now)
    past.setDate(past.getDate() - 1)
    const weekDay = start.getDay() === 0 ? 7 : start.getDay()
    process.stdout.write(
      `${format(start)} ${format(end)} ${weekDay} ${format(past)}`,
    )
  ' "${rule_start_date}"
)"
read -r attendance_start_date attendance_end_date attendance_week_day attendance_past_date \
  <<<"${attendance_dates}"
attendance_room_body='{"schoolId":1,"roomCode":"ROOM-VERIFY-ATTEND","roomName":"考勤验收教室","location":"自动验收区","capacity":20,"status":"ACTIVE"}'
write_api 201 POST /rooms "${admin_jar}" "${attendance_room_body}" \
  "${run_root}/attendance-room.json"
attendance_room_id="$(jq -r '.id // empty' "${run_root}/attendance-room.json")"
[[ "${attendance_room_id}" =~ ^[0-9]+$ ]] \
  || fail "attendance offering room was not created"
attendance_offering_body="$(
  jq -nc \
    --argjson courseId "${course_id}" \
    --argjson weekDay "${attendance_week_day}" \
    --arg startDate "${attendance_start_date}" \
    --arg endDate "${attendance_end_date}" \
    --argjson termId "${strict_term_id}" \
    --argjson planId "${strict_plan_id}" \
    --argjson roomId "${attendance_room_id}" \
    '{
      schoolId:1,
      courseId:$courseId,
      teacherId:1,
      offeringCode:"O-VERIFY-ATTENDANCE",
      term:"VERIFY-CURRENT",
      weekDay:$weekDay,
      startTime:"13:00:00",
      endTime:"14:00:00",
      startDate:$startDate,
      endDate:$endDate,
      enrollmentStart:"2020-01-01T00:00:00",
      enrollmentEnd:"2099-12-31T23:59:59",
      capacity:10,
      classroom:"考勤验收教室",
      status:"DRAFT",
      termId:$termId,
      planId:$planId,
      roomId:$roomId
    }'
)"
write_api 201 POST /offerings "${admin_jar}" "${attendance_offering_body}" \
  "${run_root}/attendance-offering.draft.json"
attendance_offering_id="$(jq -r '.id' "${run_root}/attendance-offering.draft.json")"
[[ "${attendance_offering_id}" =~ ^[0-9]+$ ]] \
  || fail "attendance draft offering creation returned no id"
attendance_offering_body="$(
  jq '.status = "PUBLISHED"' <<<"${attendance_offering_body}"
)"
write_api 200 PUT "/offerings/${attendance_offering_id}" "${admin_jar}" \
  "${attendance_offering_body}" "${run_root}/attendance-offering.json"
jq -e '.status == "PUBLISHED"' "${run_root}/attendance-offering.json" >/dev/null \
  || fail "attendance draft offering did not transition to PUBLISHED"

login_as parent_chen GUARDIAN "${parent_jar}"
login_as student_chen_he STUDENT "${student_one_jar}"
login_as student_chen_shu STUDENT "${student_two_jar}"
parent_students_status="$(
  status_of "${run_root}/parent-students.json" \
    -c "${parent_jar}" -b "${parent_jar}" "${base_url}/guardian/students"
)"
[[ "${parent_students_status}" == "200" ]] || fail "guardian could not list bound students"
jq -e 'length == 2 and all(.id == 1 or .id == 2)' "${run_root}/parent-students.json" >/dev/null \
  || fail "guardian student scope is incorrect"

enroll_body="$(jq -nc --argjson offeringId "${base_offering_id}" \
  '{studentId:1,offeringId:$offeringId}')"
write_api 201 POST /enrollments "${student_one_jar}" "${enroll_body}" "${run_root}/enrolled.json"
write_api 409 POST /enrollments "${student_one_jar}" "${enroll_body}" "${run_root}/duplicate.json"
jq -e '.code == "ALREADY_ENROLLED"' "${run_root}/duplicate.json" >/dev/null \
  || fail "duplicate enrollment rule failed"

grade_body="$(jq -nc --argjson offeringId "${grade_offering_id}" \
  '{studentId:1,offeringId:$offeringId}')"
write_api 409 POST /enrollments "${student_one_jar}" "${grade_body}" "${run_root}/grade-reject.json"
jq -e '.code == "GRADE_NOT_ELIGIBLE"' "${run_root}/grade-reject.json" >/dev/null \
  || fail "grade enrollment rule failed"

future_body="$(jq -nc --argjson offeringId "${future_offering_id}" \
  '{studentId:1,offeringId:$offeringId}')"
write_api 409 POST /enrollments "${student_one_jar}" "${future_body}" "${run_root}/time-reject.json"
jq -e '.code == "OUTSIDE_ENROLLMENT_WINDOW"' "${run_root}/time-reject.json" >/dev/null \
  || fail "enrollment-window rule failed"

conflict_body="$(jq -nc --argjson offeringId "${conflict_offering_id}" \
  '{studentId:1,offeringId:$offeringId}')"
write_api 409 POST /enrollments "${student_one_jar}" "${conflict_body}" "${run_root}/conflict-reject.json"
jq -e '.code == "STUDENT_SCHEDULE_CONFLICT"' "${run_root}/conflict-reject.json" >/dev/null \
  || fail "student schedule-conflict rule failed"

not_bound_body="$(jq -nc --argjson offeringId "${base_offering_id}" \
  '{studentId:3,offeringId:$offeringId}')"
write_api 403 POST /enrollments "${student_one_jar}" "${not_bound_body}" "${run_root}/not-bound.json"

for student_id in 1 2; do
  attendance_enrollment_body="$(
    jq -nc \
      --argjson studentId "${student_id}" \
      --argjson offeringId "${attendance_offering_id}" \
      '{studentId:$studentId,offeringId:$offeringId}'
  )"
  enrollment_cookie="${student_one_jar}"
  [[ "${student_id}" == "2" ]] && enrollment_cookie="${student_two_jar}"
  write_api 201 POST /enrollments "${enrollment_cookie}" \
    "${attendance_enrollment_body}" \
    "${run_root}/attendance-enrollment-${student_id}.json"
done

log_step "Verifying concurrent capacity protection"

capacity_token_one="$(csrf_for "${student_one_jar}")"
capacity_token_two="$(csrf_for "${student_two_jar}")"
capacity_body_1="$(jq -nc --argjson offeringId "${capacity_offering_id}" \
  '{studentId:1,offeringId:$offeringId}')"
capacity_body_2="$(jq -nc --argjson offeringId "${capacity_offering_id}" \
  '{studentId:2,offeringId:$offeringId}')"
(
  curl --noproxy '*' -sS -o "${run_root}/capacity-1.json" \
    -w '%{http_code}' -b "${student_one_jar}" \
    -X POST -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: ${capacity_token_one}" \
    --data-binary "${capacity_body_1}" \
    "${base_url}/enrollments" >"${run_root}/capacity-1.status"
) &
capacity_pid_1="$!"
(
  curl --noproxy '*' -sS -o "${run_root}/capacity-2.json" \
    -w '%{http_code}' -b "${student_two_jar}" \
    -X POST -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: ${capacity_token_two}" \
    --data-binary "${capacity_body_2}" \
    "${base_url}/enrollments" >"${run_root}/capacity-2.status"
) &
capacity_pid_2="$!"
wait "${capacity_pid_1}"
wait "${capacity_pid_2}"
capacity_statuses="$(sort "${run_root}/capacity-1.status" "${run_root}/capacity-2.status" | tr '\n' ' ')"
[[ "${capacity_statuses}" == "201 409 " ]] \
  || fail "last-seat race must yield one 201 and one 409, got ${capacity_statuses}"
if ! jq -e '.code == "OFFERING_FULL"' \
    "${run_root}/capacity-1.json" "${run_root}/capacity-2.json" >/dev/null 2>&1; then
  grep -q '"OFFERING_FULL"' "${run_root}"/capacity-*.json \
    || fail "capacity rejection did not report OFFERING_FULL"
fi

login_as teacher_wang TEACHER "${teacher_jar}"
teacher_offerings_status="$(
  status_of "${run_root}/teacher-offerings.json" \
    -c "${teacher_jar}" -b "${teacher_jar}" "${base_url}/offerings"
)"
[[ "${teacher_offerings_status}" == "200" ]] || fail "teacher could not list offerings"
jq -e 'length > 0 and all(.teacherId == 1)' "${run_root}/teacher-offerings.json" >/dev/null \
  || fail "teacher offering scope leaked another teacher"
other_teacher_status="$(
  status_of "${run_root}/other-teacher.json" \
    -c "${teacher_jar}" -b "${teacher_jar}" "${base_url}/offerings/3/sessions"
)"
[[ "${other_teacher_status}" == "403" ]] || fail "teacher accessed another teacher's sessions"

log_step "Verifying concurrent shared-student reschedule protection"

# The two fixtures deliberately use distinct teachers, offerings and target
# rooms.  Their only shared mutable resource is student 2, so a rejected
# request proves the student-conflict path rather than a teacher/room lock.
race_shared_student_id=2
race_target_date="$(
  TZ=Asia/Shanghai "${node_bin_dir}/node" -e '
    const date = new Date(`${process.argv[1]}T00:00:00`)
    while (date.getDay() !== 3) {
      date.setDate(date.getDate() + 1)
    }
    const pad = (value) => String(value).padStart(2, "0")
    process.stdout.write(
      `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`,
    )
  ' "${rule_start_date}"
)"

race_room_a_body='{"schoolId":1,"roomCode":"ROOM-VERIFY-RACE-A","roomName":"并发调课验收教室 A","location":"验收楼 201","capacity":20,"status":"ACTIVE"}'
race_room_b_body='{"schoolId":1,"roomCode":"ROOM-VERIFY-RACE-B","roomName":"并发调课验收教室 B","location":"验收楼 202","capacity":20,"status":"ACTIVE"}'
write_api 201 POST /rooms "${admin_jar}" "${race_room_a_body}" \
  "${run_root}/race-room-a.json"
write_api 201 POST /rooms "${admin_jar}" "${race_room_b_body}" \
  "${run_root}/race-room-b.json"
race_room_a_id="$(jq -r '.id // empty' "${run_root}/race-room-a.json")"
race_room_b_id="$(jq -r '.id // empty' "${run_root}/race-room-b.json")"
[[ "${race_room_a_id}" =~ ^[0-9]+$ && "${race_room_b_id}" =~ ^[0-9]+$ ]] \
  || fail "concurrent-reschedule fixtures did not create two rooms"

race_teacher_a_body='{"schoolId":1,"teacherNo":"T-VERIFY-RACE-A","fullName":"并发调课验收教师 A","username":"verify_race_teacher_a","password":"VerifyRaceTeacherA@2026","phone":"13800000911","title":"并发验收教师","status":"ACTIVE"}'
race_teacher_b_body='{"schoolId":1,"teacherNo":"T-VERIFY-RACE-B","fullName":"并发调课验收教师 B","username":"verify_race_teacher_b","password":"VerifyRaceTeacherB@2026","phone":"13800000912","title":"并发验收教师","status":"ACTIVE"}'
write_api 201 POST /teachers "${admin_jar}" "${race_teacher_a_body}" \
  "${run_root}/race-teacher-a.json"
write_api 201 POST /teachers "${admin_jar}" "${race_teacher_b_body}" \
  "${run_root}/race-teacher-b.json"
race_teacher_a_id="$(jq -r '.id // empty' "${run_root}/race-teacher-a.json")"
race_teacher_b_id="$(jq -r '.id // empty' "${run_root}/race-teacher-b.json")"
[[ "${race_teacher_a_id}" =~ ^[0-9]+$ && "${race_teacher_b_id}" =~ ^[0-9]+$ ]] \
  || fail "concurrent-reschedule fixtures did not create two teachers"

race_term_b_body="$(jq -nc \
  --arg termCode "${rule_term}-RACE-B" \
  --arg startDate "${rule_start_date}" \
  --arg endDate "${rule_end_date}" \
  '{termCode:$termCode,termName:"并发调课验收学期 B",startDate:$startDate,endDate:$endDate,status:"DRAFT"}')"
write_api 201 POST /terms "${admin_jar}" "${race_term_b_body}" \
  "${run_root}/race-term-b-draft.json"
race_term_b_id="$(jq -r '.id // empty' "${run_root}/race-term-b-draft.json")"
race_term_b_body="$(jq '.status = "ACTIVE"' <<<"${race_term_b_body}")"
write_api 200 PUT "/terms/${race_term_b_id}" "${admin_jar}" \
  "${race_term_b_body}" "${run_root}/race-term-b.json"
race_plan_b_body="$(jq -nc --argjson termId "${race_term_b_id}" \
  '{schoolId:1,termId:$termId,planCode:"PLAN-VERIFY-RACE-B",planName:"并发调课验收计划 B",description:"与计划 A 仅共享学生，不共享学期锁"}')"
write_api 201 POST /service-plans "${admin_jar}" "${race_plan_b_body}" \
  "${run_root}/race-plan-b.json"
race_plan_b_id="$(jq -r '.id // empty' "${run_root}/race-plan-b.json")"
write_api 201 POST "/service-plans/${race_plan_b_id}/items" "${admin_jar}" \
  '{"category":"综合实践","plannedCourseCount":1,"plannedClassCount":1,"capacityPerClass":20,"plannedTeacherCount":1,"notes":"并发验收独立计划"}' \
  "${run_root}/race-plan-b-item.json"
write_api 200 POST "/service-plans/${race_plan_b_id}/transitions" "${admin_jar}" \
  '{"targetStatus":"SUBMITTED"}' "${run_root}/race-plan-b-submitted.json"
write_api 200 POST "/service-plans/${race_plan_b_id}/transitions" "${admin_jar}" \
  '{"targetStatus":"FILED"}' "${run_root}/race-plan-b-filed.json"
write_api 200 POST "/service-plans/${race_plan_b_id}/transitions" "${admin_jar}" \
  '{"targetStatus":"ACTIVE"}' "${run_root}/race-plan-b-active.json"

create_offering O-VERIFY-RACE-A "${race_teacher_a_id}" 1 "${course_id}" 20 \
  "${rule_enrollment_start}" "${rule_enrollment_end}" \
  "${run_root}/race-offering-a.json"
create_offering O-VERIFY-RACE-B "${race_teacher_b_id}" 2 "${course_id}" 20 \
  "${rule_enrollment_start}" "${rule_enrollment_end}" \
  "${run_root}/race-offering-b.json" "${race_term_b_id}" "${race_plan_b_id}"
race_offering_a_id="$(jq -r '.id // empty' "${run_root}/race-offering-a.json")"
race_offering_b_id="$(jq -r '.id // empty' "${run_root}/race-offering-b.json")"
[[ "${race_offering_a_id}" =~ ^[0-9]+$ && "${race_offering_b_id}" =~ ^[0-9]+$ ]] \
  || fail "concurrent-reschedule fixtures did not create two offerings"

for race_offering_id in "${race_offering_a_id}" "${race_offering_b_id}"; do
  race_enrollment_body="$(
    jq -nc \
      --argjson studentId "${race_shared_student_id}" \
      --argjson offeringId "${race_offering_id}" \
      '{studentId:$studentId,offeringId:$offeringId}'
  )"
  write_api 201 POST /enrollments "${student_two_jar}" "${race_enrollment_body}" \
    "${run_root}/race-enrollment-${race_offering_id}.json"
done

race_teacher_a_jar="${run_root}/race-teacher-a.cookies"
race_teacher_b_jar="${run_root}/race-teacher-b.cookies"
login_as verify_race_teacher_a TEACHER "${race_teacher_a_jar}" "VerifyRaceTeacherA@2026"
login_as verify_race_teacher_b TEACHER "${race_teacher_b_jar}" "VerifyRaceTeacherB@2026"
write_api 200 POST "/offerings/${race_offering_a_id}/sessions/generate" \
  "${admin_jar}" '{}' "${run_root}/race-sessions-a.json"
write_api 200 POST "/offerings/${race_offering_b_id}/sessions/generate" \
  "${admin_jar}" '{}' "${run_root}/race-sessions-b.json"
race_session_a_id="$(jq -r '.[0].id // empty' "${run_root}/race-sessions-a.json")"
race_session_b_id="$(jq -r '.[0].id // empty' "${run_root}/race-sessions-b.json")"
[[ "${race_session_a_id}" =~ ^[0-9]+$ && "${race_session_b_id}" =~ ^[0-9]+$ ]] \
  || fail "concurrent-reschedule fixtures did not generate two sessions"

race_preexisting_target_conflicts="$(
  "${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
    -uroot --skip-column-names "${db_name}" -e \
    "SELECT COUNT(*)
     FROM enrollment e
     JOIN course_offering o ON o.id = e.offering_id
     JOIN lesson_session ls ON ls.offering_id = o.id
     WHERE e.student_id = ${race_shared_student_id}
       AND e.status = 'ENROLLED'
       AND o.status NOT IN ('CANCELED', 'FINISHED')
       AND ls.status != 'CANCELED'
       AND ls.session_date = '${race_target_date}'
       AND ls.start_time < '21:00:00'
       AND ls.end_time > '20:00:00'"
)"
[[ "${race_preexisting_target_conflicts}" == "0" ]] \
  || fail "concurrent-reschedule target already conflicts before the race"

# The mutation contract deliberately uses READ_COMMITTED: hold student 2 in a
# separate MySQL transaction and wait until performance_schema observes two
# HTTP requests blocked on that exact row.  Releasing it only then proves that
# the second transaction re-reads the first committed schedule before checking
# student conflicts, without relying on a scheduling sleep.
race_barrier_fifo="${run_root}/reschedule-race-barrier.sql"
race_barrier_output="${run_root}/reschedule-race-barrier.out"
race_barrier_error="${run_root}/reschedule-race-barrier.err"
mkfifo "${race_barrier_fifo}"
"${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
  -uroot --batch --raw --skip-column-names --unbuffered "${db_name}" \
  <"${race_barrier_fifo}" >"${race_barrier_output}" 2>"${race_barrier_error}" &
race_barrier_pid="$!"
exec 9>"${race_barrier_fifo}"
race_barrier_fd_open=true
printf '%s\n' \
  "SELECT CONCAT('RACE_BARRIER_CONNECTION:', CONNECTION_ID());" \
  'SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;' \
  'START TRANSACTION;' \
  "SELECT id FROM student WHERE id = ${race_shared_student_id} FOR UPDATE;" \
  "SELECT 'RACE_BARRIER_LOCKED';" >&9

race_barrier_ready=false
race_barrier_connection_id=""
for _ in {1..100}; do
  race_barrier_connection_id="$(
    sed -n 's/^RACE_BARRIER_CONNECTION://p' "${race_barrier_output}" | sed -n '1p'
  )"
  if [[ "${race_barrier_connection_id}" =~ ^[0-9]+$ ]] \
      && grep -Fxq 'RACE_BARRIER_LOCKED' "${race_barrier_output}"; then
    race_barrier_ready=true
    break
  fi
  if ! kill -0 "${race_barrier_pid}" >/dev/null 2>&1; then
    cat "${race_barrier_error}" >&2 || true
    fail "reschedule race barrier transaction exited before acquiring student lock"
  fi
  sleep 0.05
done
[[ "${race_barrier_ready}" == "true" ]] \
  || fail "reschedule race barrier did not acquire the student lock"

race_token="$(csrf_for "${admin_jar}")"
race_body_a="$(
  jq -nc \
    --arg sessionDate "${race_target_date}" \
    --argjson roomId "${race_room_a_id}" \
    '{sessionDate:$sessionDate,startTime:"20:00:00",endTime:"21:00:00",roomId:$roomId,reason:"并发验收调课 A"}'
)"
race_body_b="$(
  jq -nc \
    --arg sessionDate "${race_target_date}" \
    --argjson roomId "${race_room_b_id}" \
    '{sessionDate:$sessionDate,startTime:"20:00:00",endTime:"21:00:00",roomId:$roomId,reason:"并发验收调课 B"}'
)"
(
  exec curl --noproxy '*' -sS --max-time 20 \
    -o "${run_root}/reschedule-race-a.json" -w '%{http_code}' \
    -b "${admin_jar}" \
    -X POST -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: ${race_token}" \
    --data-binary "${race_body_a}" \
    "${base_url}/sessions/${race_session_a_id}/reschedule"
) >"${run_root}/reschedule-race-a.status" &
race_curl_pid_a="$!"
(
  exec curl --noproxy '*' -sS --max-time 20 \
    -o "${run_root}/reschedule-race-b.json" -w '%{http_code}' \
    -b "${admin_jar}" \
    -X POST -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: ${race_token}" \
    --data-binary "${race_body_b}" \
    "${base_url}/sessions/${race_session_b_id}/reschedule"
) >"${run_root}/reschedule-race-b.status" &
race_curl_pid_b="$!"

race_waiters_ready=false
race_waiter_count=0
for _ in {1..200}; do
  race_waiter_count="$(
    "${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
      -uroot --skip-column-names -e \
      "SELECT COUNT(DISTINCT waits.REQUESTING_ENGINE_TRANSACTION_ID)
       FROM performance_schema.data_lock_waits waits
       JOIN performance_schema.data_locks blocker
         ON blocker.ENGINE = waits.ENGINE
        AND blocker.ENGINE_LOCK_ID = waits.BLOCKING_ENGINE_LOCK_ID
       JOIN performance_schema.threads blocker_thread
         ON blocker_thread.THREAD_ID = blocker.THREAD_ID
       WHERE blocker_thread.PROCESSLIST_ID = ${race_barrier_connection_id}
         AND blocker.OBJECT_SCHEMA = '${db_name}'
         AND blocker.OBJECT_NAME = 'student'
         AND blocker.INDEX_NAME = 'PRIMARY'
         AND blocker.LOCK_DATA = '${race_shared_student_id}'"
  )"
  if [[ "${race_waiter_count}" == "2" ]]; then
    race_waiters_ready=true
    break
  fi
  if ! kill -0 "${race_curl_pid_a}" >/dev/null 2>&1 \
      || ! kill -0 "${race_curl_pid_b}" >/dev/null 2>&1; then
    break
  fi
  sleep 0.05
done
if [[ "${race_waiters_ready}" != "true" ]]; then
  fail "reschedule race never reached two observed waits on the shared student lock (saw ${race_waiter_count})"
fi

printf '%s\n' 'COMMIT;' >&9
exec 9>&-
race_barrier_fd_open=false
if ! wait "${race_barrier_pid}"; then
  cat "${race_barrier_error}" >&2 || true
  fail "reschedule race barrier transaction did not commit cleanly"
fi
race_barrier_pid=""
if ! wait "${race_curl_pid_a}"; then
  fail "first reschedule race HTTP request failed"
fi
race_curl_pid_a=""
if ! wait "${race_curl_pid_b}"; then
  fail "second reschedule race HTTP request failed"
fi
race_curl_pid_b=""
race_statuses="$(
  {
    cat "${run_root}/reschedule-race-a.status"
    printf '\n'
    cat "${run_root}/reschedule-race-b.status"
    printf '\n'
  } | sort | tr '\n' ' '
)"
[[ "${race_statuses}" == "201 409 " ]] \
  || fail "shared-student concurrent reschedules must yield one 201 and one 409, got ${race_statuses}"
if [[ "$(cat "${run_root}/reschedule-race-a.status")" == "409" ]]; then
  race_conflict_output="${run_root}/reschedule-race-a.json"
  race_success_output="${run_root}/reschedule-race-b.json"
else
  race_conflict_output="${run_root}/reschedule-race-b.json"
  race_success_output="${run_root}/reschedule-race-a.json"
fi
jq -e '.code == "STUDENT_SCHEDULE_CONFLICT"' "${race_conflict_output}" >/dev/null \
  || fail "concurrent reschedule rejection did not use STUDENT_SCHEDULE_CONFLICT"
jq -e --arg targetDate "${race_target_date}" \
  '.status == "APPLIED"
   and (.adjustedSessionDate | tostring | startswith($targetDate))' \
  "${race_success_output}" >/dev/null \
  || fail "concurrent reschedule success did not apply the target schedule"
race_committed_target_count="$(
  "${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
    -uroot --skip-column-names "${db_name}" -e \
    "SELECT COUNT(*)
     FROM lesson_session
     WHERE id IN (${race_session_a_id}, ${race_session_b_id})
       AND session_date = '${race_target_date}'
       AND start_time = '20:00:00'
       AND end_time = '21:00:00'"
)"
race_applied_adjustment_count="$(
  "${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
    -uroot --skip-column-names "${db_name}" -e \
    "SELECT COUNT(*)
     FROM schedule_adjustment
     WHERE session_id IN (${race_session_a_id}, ${race_session_b_id})
       AND status = 'APPLIED'"
)"
[[ "${race_committed_target_count}" == "1" \
    && "${race_applied_adjustment_count}" == "1" ]] \
  || fail "concurrent reschedule persisted an invalid target state"

write_api 200 POST "/offerings/${attendance_offering_id}/sessions/generate" \
  "${admin_jar}" '{}' \
  "${run_root}/sessions.json"
session_id="$(jq -r '.[0].id' "${run_root}/sessions.json")"
[[ "${session_id}" =~ ^[0-9]+$ ]] || fail "session generation returned no session"
# Enrollments were accepted while the first session was still in the future.
# Move only this disposable fixture session into the past so the real API can
# exercise attendance without weakening the production time guard.
"${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
  -uroot "${db_name}" -e \
  "UPDATE enrollment SET enrolled_at=TIMESTAMP(DATE_SUB('${attendance_past_date}', INTERVAL 1 DAY), '00:00:00') WHERE offering_id=${attendance_offering_id};
   UPDATE lesson_session SET session_date='${attendance_past_date}', start_time='00:00:00', end_time='01:00:00' WHERE id=${session_id};"
attendance_status="$(
  status_of "${run_root}/attendance-list.json" \
    -c "${teacher_jar}" -b "${teacher_jar}" \
    "${base_url}/sessions/${session_id}/attendance"
)"
[[ "${attendance_status}" == "200" ]] || fail "teacher could not load attendance"

log_step "Verifying teacher-only classroom and attendance writes"

write_api 200 PUT "/sessions/${session_id}" "${teacher_jar}" \
  '{"status":"CANCELED","notes":"教师课堂记录"}' \
  "${run_root}/teacher-session-note.json"
jq -e '.status == "SCHEDULED" and .notes == "教师课堂记录"' \
  "${run_root}/teacher-session-note.json" >/dev/null \
  || fail "teacher session note update changed the schedule-owned status"
write_api 403 PUT "/sessions/${session_id}" "${admin_jar}" \
  '{"notes":"教务越权课堂记录"}' \
  "${run_root}/admin-session-write-forbidden.json"

attendance_body="$(
  jq -c '{records: map({studentId:.studentId,status:"PRESENT",remark:"验收通过"})}' \
    "${run_root}/attendance-list.json"
)"
write_api 403 PUT "/sessions/${session_id}/attendance" "${admin_jar}" \
  "${attendance_body}" "${run_root}/admin-attendance-forbidden.json"
write_api 200 PUT "/sessions/${session_id}/attendance" "${teacher_jar}" \
  "${attendance_body}" "${run_root}/attendance-saved.json"
jq -e 'length == 2 and all(.status == "PRESENT")' \
  "${run_root}/attendance-saved.json" >/dev/null || fail "attendance save failed"
attendance_month="${attendance_past_date:0:7}"
monthly_attendance_status="$(
  status_of "${run_root}/monthly-attendance.json" \
    -c "${parent_jar}" -b "${parent_jar}" \
    "${base_url}/guardian/students/1/attendance/monthly?month=${attendance_month}"
)"
[[ "${monthly_attendance_status}" == "200" ]] \
  || fail "guardian could not read monthly attendance"
jq -e --arg month "${attendance_month}" \
  '.month == $month and .summary.totalCount >= 1 and (.records | length) >= 1' \
  "${run_root}/monthly-attendance.json" >/dev/null \
  || fail "monthly attendance aggregation is incomplete"

admin_cancel_id="$(jq -r '.id' "${run_root}/enrolled.json")"
write_api 204 DELETE "/enrollments/${admin_cancel_id}" "${admin_jar}" '{}' \
  "${run_root}/admin-canceled-enrollment.json"
admin_enrollments_status="$(
  status_of "${run_root}/admin-enrollments-after-cancel.json" \
    -c "${admin_jar}" -b "${admin_jar}" "${base_url}/enrollments"
)"
[[ "${admin_enrollments_status}" == "200" ]] \
  || fail "school admin could not reload enrollments after cancellation"
jq -e --argjson id "${admin_cancel_id}" \
  'any(.[]; .id == $id and .status == "CANCELED" and .canceledByName != null)' \
  "${run_root}/admin-enrollments-after-cancel.json" >/dev/null \
  || fail "school-admin cancellation or cancellation audit failed"
enrollment_actions_status="$(
  status_of "${run_root}/enrollment-actions.json" \
    -c "${admin_jar}" -b "${admin_jar}" \
    "${base_url}/enrollments/${admin_cancel_id}/actions"
)"
[[ "${enrollment_actions_status}" == "200" ]] \
  || fail "school admin could not read enrollment change history"
jq -e 'map(.actionType) | index("ENROLL") != null and index("CANCEL") != null' \
  "${run_root}/enrollment-actions.json" >/dev/null \
  || fail "append-only enrollment history did not preserve enroll and cancel actions"

log_step "Verifying academic planning, resources, leave and correction workflows"

invalid_term_body='{"termCode":"VERIFY-2098-INVALID","termName":"2098 非法初态验收学期","startDate":"2098-01-01","endDate":"2098-03-31","status":"ACTIVE"}'
write_api 400 POST /terms "${admin_jar}" "${invalid_term_body}" \
  "${run_root}/invalid-initial-term.json"
jq -e '.code == "INITIAL_TERM_STATUS_INVALID"' \
  "${run_root}/invalid-initial-term.json" >/dev/null \
  || fail "new academic term must reject any initial status except DRAFT"

term_body='{"termCode":"VERIFY-2098","termName":"2098 验收学期","startDate":"2098-01-01","endDate":"2098-03-31","status":"DRAFT"}'
write_api 201 POST /terms "${admin_jar}" "${term_body}" \
  "${run_root}/academic-term-draft.json"
verify_term_id="$(jq -r '.id' "${run_root}/academic-term-draft.json")"
[[ "${verify_term_id}" =~ ^[0-9]+$ ]] || fail "academic term creation returned no id"
term_body="$(jq '.status = "ACTIVE"' <<<"${term_body}")"
write_api 200 PUT "/terms/${verify_term_id}" "${admin_jar}" "${term_body}" \
  "${run_root}/academic-term.json"
jq -e '.status == "ACTIVE"' "${run_root}/academic-term.json" >/dev/null \
  || fail "draft academic term did not transition to ACTIVE"

plan_body="$(
  jq -nc --argjson termId "${verify_term_id}" \
    '{
      schoolId:1,
      termId:$termId,
      planCode:"PLAN-VERIFY-2098",
      planName:"2098 学期验收服务计划",
      description:"用于一键验收备案、排课和资源冲突链路"
    }'
)"
write_api 201 POST /service-plans "${admin_jar}" "${plan_body}" \
  "${run_root}/service-plan.json"
verify_plan_id="$(jq -r '.id' "${run_root}/service-plan.json")"
plan_item_body='{"category":"综合实践","plannedCourseCount":1,"plannedClassCount":1,"capacityPerClass":30,"plannedTeacherCount":1,"notes":"2098 验收计划明细"}'
write_api 201 POST "/service-plans/${verify_plan_id}/items" "${admin_jar}" \
  "${plan_item_body}" "${run_root}/service-plan-item.json"
write_api 200 POST "/service-plans/${verify_plan_id}/transitions" \
  "${admin_jar}" '{"targetStatus":"SUBMITTED"}' \
  "${run_root}/service-plan-submitted.json"
jq -e '.status == "SUBMITTED"' "${run_root}/service-plan-submitted.json" >/dev/null \
  || fail "school could not submit service plan"
write_api 200 POST "/service-plans/${verify_plan_id}/transitions" \
  "${admin_jar}" '{"targetStatus":"FILED"}' \
  "${run_root}/service-plan-filed.json"
write_api 200 POST "/service-plans/${verify_plan_id}/transitions" \
  "${admin_jar}" '{"targetStatus":"ACTIVE"}' \
  "${run_root}/service-plan-active.json"
jq -e '.status == "ACTIVE"' "${run_root}/service-plan-active.json" >/dev/null \
  || fail "school admin could not file and activate service plan"

room_body='{"schoolId":1,"roomCode":"ROOM-VERIFY-2098","roomName":"验收综合教室","location":"验收楼 101","capacity":30,"status":"ACTIVE"}'
write_api 201 POST /rooms "${admin_jar}" "${room_body}" \
  "${run_root}/academic-room.json"
verify_room_id="$(jq -r '.id' "${run_root}/academic-room.json")"
room_update_body='{"schoolId":1,"roomCode":"ROOM-VERIFY-2098","roomName":"验收综合教室","location":"验收楼 102","capacity":30,"status":"ACTIVE"}'
write_api 200 PUT "/rooms/${verify_room_id}" "${admin_jar}" "${room_update_body}" \
  "${run_root}/academic-room-updated.json"
jq -e --argjson roomId "${verify_room_id}" \
  '.id == $roomId and .location == "验收楼 102" and .status == "ACTIVE"' \
  "${run_root}/academic-room-updated.json" >/dev/null \
  || fail "school admin could not update the academic room"

calendar_body="$(
  jq -nc --argjson termId "${verify_term_id}" \
    '{
      schoolId:1,
      termId:$termId,
      eventDate:"2098-02-01",
      dayType:"HOLIDAY",
      eventName:"验收停课日",
      description:"验证校历事件与学校租户范围"
    }'
)"
write_api 201 POST /calendar-events "${admin_jar}" "${calendar_body}" \
  "${run_root}/calendar-event.json"

planned_offering_body="$(
  jq -nc \
    --argjson courseId "${course_id}" \
    --argjson termId "${verify_term_id}" \
    --argjson planId "${verify_plan_id}" \
    --argjson roomId "${verify_room_id}" \
    '{
      schoolId:1,
      courseId:$courseId,
      teacherId:1,
      offeringCode:"O-VERIFY-PLANNED",
      term:"VERIFY-2098",
      weekDay:6,
      startTime:"10:00:00",
      endTime:"11:00:00",
      startDate:"2098-01-01",
      endDate:"2098-03-31",
      enrollmentStart:"2020-01-01T00:00:00",
      enrollmentEnd:"2097-12-31T23:59:59",
      capacity:10,
      classroom:"由标准教室自动回填",
      status:"DRAFT",
      termId:$termId,
      planId:$planId,
      roomId:$roomId
    }'
)"
write_api 201 POST /offerings "${admin_jar}" "${planned_offering_body}" \
  "${run_root}/planned-offering.draft.json"
planned_offering_id="$(jq -r '.id' "${run_root}/planned-offering.draft.json")"
[[ "${planned_offering_id}" =~ ^[0-9]+$ ]] \
  || fail "planned draft offering creation returned no id"
planned_offering_body="$(
  jq '.status = "PUBLISHED"' <<<"${planned_offering_body}"
)"
write_api 200 PUT "/offerings/${planned_offering_id}" "${admin_jar}" \
  "${planned_offering_body}" "${run_root}/planned-offering.json"
jq -e \
  --argjson termId "${verify_term_id}" \
  --argjson planId "${verify_plan_id}" \
  --argjson roomId "${verify_room_id}" \
  '.termId == $termId and .planId == $planId and .roomId == $roomId
   and .classroom == "验收综合教室" and .status == "PUBLISHED"' \
  "${run_root}/planned-offering.json" >/dev/null \
  || fail "planned offering did not retain normalized academic resources"

planned_enrollment_body="$(
  jq -nc --argjson offeringId "${planned_offering_id}" \
    '{studentId:2,offeringId:$offeringId}'
)"
write_api 201 POST /enrollments "${student_two_jar}" "${planned_enrollment_body}" \
  "${run_root}/planned-enrollment.json"
class_roster_status="$(status_of "${run_root}/class-enrollment-roster.xlsx" \
  -c "${admin_jar}" -b "${admin_jar}" \
  "${base_url}/enrollments/class-roster.xlsx?classId=2")"
[[ "${class_roster_status}" == "200" ]] || fail "class roster Excel export failed"
assert_xlsx "${run_root}/class-enrollment-roster.xlsx"
write_api 200 POST "/offerings/${planned_offering_id}/sessions/generate" \
  "${admin_jar}" '{}' "${run_root}/planned-sessions.json"
jq -e '
  length >= 2
  and all(.[]; (.sessionDate | tostring | startswith("2098-02-01") | not))
' "${run_root}/planned-sessions.json" >/dev/null \
  || fail "session generation did not skip the school-calendar holiday"
planned_session_id="$(jq -r '.[0].id' "${run_root}/planned-sessions.json")"
reschedule_session_id="$(jq -r '.[1].id' "${run_root}/planned-sessions.json")"
reschedule_original_date="$(
  jq -r '.[1].sessionDate | tostring | split("T")[0]' \
    "${run_root}/planned-sessions.json"
)"
[[ "${planned_session_id}" =~ ^[0-9]+$ && "${reschedule_session_id}" =~ ^[0-9]+$ ]] \
  || fail "planned offering did not generate enough sessions"

reschedule_body="$(
  jq -nc --argjson roomId "${verify_room_id}" \
    '{
      sessionDate:"2098-01-06",
      startTime:"12:00:00",
      endTime:"13:00:00",
      roomId:$roomId,
      reason:"验收调课：避开原定活动安排"
    }'
)"
write_api 201 POST "/sessions/${reschedule_session_id}/reschedule" \
  "${admin_jar}" "${reschedule_body}" "${run_root}/rescheduled-session.json"
jq -e '
  (.adjustedSessionDate | tostring | startswith("2098-01-06"))
  and .status == "APPLIED"
' \
  "${run_root}/rescheduled-session.json" >/dev/null \
  || fail "session reschedule was not applied or audited: $(cat "${run_root}/rescheduled-session.json")"
reschedule_adjustment_id="$(jq -r '.id // empty' "${run_root}/rescheduled-session.json")"
[[ "${reschedule_adjustment_id}" =~ ^[0-9]+$ ]] \
  || fail "session reschedule returned no schedule-adjustment id"

write_api 200 POST "/offerings/${planned_offering_id}/sessions/generate" \
  "${admin_jar}" '{}' "${run_root}/planned-sessions-regenerated.json"
jq -e \
  --arg originalDate "${reschedule_original_date}" \
  'any(.[]; (.sessionDate | tostring | startswith("2098-01-06")))
   and all(.[]; (.sessionDate | tostring | startswith($originalDate) | not))' \
  "${run_root}/planned-sessions-regenerated.json" >/dev/null \
  || fail "session regeneration recreated the original date of an applied reschedule"

write_api 403 POST "/schedule-adjustments/${reschedule_adjustment_id}/revert" \
  "${teacher_jar}" '{}' "${run_root}/rescheduled-session-revert-teacher-forbidden.json"

write_api 200 POST "/schedule-adjustments/${reschedule_adjustment_id}/revert" \
  "${admin_jar}" '{}' "${run_root}/rescheduled-session-reverted.json"
jq -e --arg originalDate "${reschedule_original_date}" \
  '.status == "REVERTED"
   and (.originalSessionDate | tostring | startswith($originalDate))' \
  "${run_root}/rescheduled-session-reverted.json" >/dev/null \
  || fail "schedule adjustment was not reverted to its original date"
reverted_sessions_status="$(
  status_of "${run_root}/planned-sessions-after-revert.json" \
    -c "${teacher_jar}" -b "${teacher_jar}" \
    "${base_url}/offerings/${planned_offering_id}/sessions"
)"
[[ "${reverted_sessions_status}" == "200" ]] \
  || fail "teacher could not reload sessions after schedule-adjustment revert"
jq -e --argjson sessionId "${reschedule_session_id}" \
  --arg originalDate "${reschedule_original_date}" \
  'any(.[]; .id == $sessionId
       and (.sessionDate | tostring | startswith($originalDate)))' \
  "${run_root}/planned-sessions-after-revert.json" >/dev/null \
  || fail "schedule-adjustment revert did not restore the session original date"
write_api 409 POST "/schedule-adjustments/${reschedule_adjustment_id}/revert" \
  "${admin_jar}" '{}' "${run_root}/rescheduled-session-revert-duplicate.json"
jq -e '.code == "SCHEDULE_ADJUSTMENT_NOT_ACTIVE"' \
  "${run_root}/rescheduled-session-revert-duplicate.json" >/dev/null \
  || fail "reverting an already reverted schedule adjustment was not rejected"

session_update_body='{"status":"SCHEDULED","notes":"验收课次备注已更新"}'
write_api 200 PUT "/sessions/${reschedule_session_id}" "${teacher_jar}" \
  "${session_update_body}" "${run_root}/planned-session-updated.json"
jq -e --argjson sessionId "${reschedule_session_id}" \
  '.id == $sessionId and .status == "SCHEDULED"
   and .notes == "验收课次备注已更新"' \
  "${run_root}/planned-session-updated.json" >/dev/null \
  || fail "teacher could not update a scheduled session status and notes"

withdraw_leave_body="$(
  jq -nc --argjson sessionId "${reschedule_session_id}" \
    '{sessionId:$sessionId,studentId:2,reason:"验收请假撤回申请"}'
)"
write_api 200 POST /leave-requests "${student_two_jar}" "${withdraw_leave_body}" \
  "${run_root}/leave-withdraw-request.json"
withdraw_leave_request_id="$(jq -r '.id // empty' "${run_root}/leave-withdraw-request.json")"
[[ "${withdraw_leave_request_id}" =~ ^[0-9]+$ ]] \
  || fail "withdrawable leave request returned no id"
jq -e '.status == "PENDING"' "${run_root}/leave-withdraw-request.json" >/dev/null \
  || fail "guardian leave request was not pending before withdrawal"
write_api 200 POST "/leave-requests/${withdraw_leave_request_id}/withdraw" \
  "${student_two_jar}" '{}' "${run_root}/leave-withdrawn.json"
jq -e '.status == "WITHDRAWN" and .withdrawnByName != null' \
  "${run_root}/leave-withdrawn.json" >/dev/null \
  || fail "guardian could not withdraw a pending leave request"

leave_body="$(
  jq -nc --argjson sessionId "${planned_session_id}" \
    '{sessionId:$sessionId,studentId:2,reason:"验收请假申请"}'
)"
write_api 200 POST /leave-requests "${student_two_jar}" "${leave_body}" \
  "${run_root}/leave-request.json"
leave_request_id="$(jq -r '.id' "${run_root}/leave-request.json")"
write_api 403 PUT "/leave-requests/${leave_request_id}/review" \
  "${admin_jar}" '{"decision":"APPROVED","remark":"教务越权审批"}' \
  "${run_root}/admin-leave-review-forbidden.json"
write_api 200 PUT "/leave-requests/${leave_request_id}/review" \
  "${teacher_jar}" '{"decision":"APPROVED","remark":"验收审批通过"}' \
  "${run_root}/leave-approved.json"
jq -e '.status == "APPROVED"' "${run_root}/leave-approved.json" >/dev/null \
  || fail "teacher could not approve own offering leave request"

leave_prefill_status="$(
  status_of "${run_root}/leave-prefill.json" \
    -c "${teacher_jar}" -b "${teacher_jar}" \
    "${base_url}/sessions/${planned_session_id}/attendance"
)"
[[ "${leave_prefill_status}" == "200" ]] || fail "teacher could not load leave-prefilled attendance"
jq -e \
  'any(.[]; .studentId == 2 and .status == "LEAVE"
      and .leaveRequestId != null and .leaveReason == "验收请假申请")' \
  "${run_root}/leave-prefill.json" >/dev/null \
  || fail "approved leave was not prefilled into attendance"

correction_attendance_id="$(jq -r '.[0].id' "${run_root}/attendance-saved.json")"
correction_student_id="$(jq -r '.[0].studentId' "${run_root}/attendance-saved.json")"
canceled_correction_body="$(
  jq -nc \
    --argjson sessionId "${session_id}" \
    --argjson studentId "${correction_student_id}" \
    '{
      sessionId:$sessionId,
      studentId:$studentId,
      requestedStatus:"ABSENT",
      requestedRemark:"待撤销的验收纠错",
      reason:"验收取消待审批考勤纠错"
    }'
)"
write_api 200 POST /attendance-corrections "${teacher_jar}" \
  "${canceled_correction_body}" "${run_root}/correction-cancel-request.json"
canceled_correction_request_id="$(jq -r '.id // empty' "${run_root}/correction-cancel-request.json")"
[[ "${canceled_correction_request_id}" =~ ^[0-9]+$ ]] \
  || fail "cancelable attendance correction returned no id"
jq -e '.status == "PENDING"' "${run_root}/correction-cancel-request.json" >/dev/null \
  || fail "attendance correction was not pending before cancellation"
write_api 200 POST "/attendance-corrections/${canceled_correction_request_id}/cancel" \
  "${teacher_jar}" '{}' "${run_root}/correction-canceled.json"
jq -e '.status == "CANCELED" and .canceledByName != null' \
  "${run_root}/correction-canceled.json" >/dev/null \
  || fail "teacher could not cancel a pending attendance correction"

correction_body="$(
  jq -nc \
    --argjson sessionId "${session_id}" \
    --argjson studentId "${correction_student_id}" \
    '{
      sessionId:$sessionId,
      studentId:$studentId,
      requestedStatus:"LATE",
      requestedRemark:"迟到五分钟",
      reason:"验收已完成课次纠错"
    }'
)"
write_api 200 POST /attendance-corrections "${teacher_jar}" \
  "${correction_body}" "${run_root}/correction-request.json"
correction_request_id="$(jq -r '.id' "${run_root}/correction-request.json")"
write_api 200 PUT "/attendance-corrections/${correction_request_id}/review" \
  "${admin_jar}" '{"decision":"APPROVED","remark":"签到材料核验通过"}' \
  "${run_root}/correction-applied.json"
jq -e '.status == "APPLIED" and .currentAttendanceStatus == "LATE"' \
  "${run_root}/correction-applied.json" >/dev/null \
  || fail "attendance correction was not atomically applied"
revision_status="$(
  status_of "${run_root}/attendance-revisions.json" \
    -c "${admin_jar}" -b "${admin_jar}" \
    "${base_url}/attendance/${correction_attendance_id}/revisions"
)"
[[ "${revision_status}" == "200" ]] || fail "attendance revision history could not be read"
jq -e \
  --argjson correctionId "${correction_request_id}" \
  'length == 1 and .[0].correctionRequestId == $correctionId
   and .[0].oldStatus == "PRESENT" and .[0].newStatus == "LATE"' \
  "${run_root}/attendance-revisions.json" >/dev/null \
  || fail "attendance revision history is incomplete"

grade_body="$(jq -nc --argjson offeringId "${attendance_offering_id}" \
  '{offeringId:$offeringId,studentId:2,score:88,learningEvaluation:"课堂参与稳定"}')"
write_api 200 PUT /grades "${teacher_jar}" "${grade_body}" \
  "${run_root}/grade-created.json"
grade_id="$(jq -r '.id // empty' "${run_root}/grade-created.json")"
[[ "${grade_id}" =~ ^[0-9]+$ ]] || fail "teacher grade creation returned no id"
grade_body="$(jq '.score = 92 | .learningEvaluation = "课堂参与积极，任务完成完整"' \
  <<<"${grade_body}")"
write_api 200 PUT /grades "${teacher_jar}" "${grade_body}" \
  "${run_root}/grade-updated.json"
jq -e '.score == 92 and .learningEvaluation == "课堂参与积极，任务完成完整"' \
  "${run_root}/grade-updated.json" >/dev/null \
  || fail "teacher could not update student grade and learning evaluation"
student_grade_status="$(status_of "${run_root}/student-grades.json" \
  -c "${student_two_jar}" -b "${student_two_jar}" "${base_url}/grades")"
[[ "${student_grade_status}" == "200" ]] \
  || fail "student could not read own grade"
jq -e --argjson id "${grade_id}" 'any(.[]; .id == $id and .score == 92)' \
  "${run_root}/student-grades.json" >/dev/null \
  || fail "student grade scope or value is incorrect"
parent_grade_status="$(status_of "${run_root}/parent-grades.json" \
  -c "${parent_jar}" -b "${parent_jar}" "${base_url}/grades")"
[[ "${parent_grade_status}" == "200" ]] \
  || fail "guardian could not read bound student's grade"
jq -e --argjson id "${grade_id}" 'any(.[]; .id == $id and .studentId == 2)' \
  "${run_root}/parent-grades.json" >/dev/null \
  || fail "guardian grade scope is incorrect"
grade_revision_status="$(status_of "${run_root}/grade-revisions.json" \
  -c "${admin_jar}" -b "${admin_jar}" \
  "${base_url}/grades/${grade_id}/revisions")"
[[ "${grade_revision_status}" == "200" ]] \
  || fail "academic administrator could not read grade revision history"
jq -e 'length == 1 and .[0].oldScore == 88 and .[0].newScore == 92' \
  "${run_root}/grade-revisions.json" >/dev/null \
  || fail "grade revision history is incomplete"

"${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
  -uroot "${db_name}" -e \
  "SET SESSION cte_max_recursion_depth = 3000;
   INSERT INTO student (
       school_id, class_id, student_no, full_name, gender, status
   )
   WITH RECURSIVE sequence(n) AS (
       SELECT 1
       UNION ALL
       SELECT n + 1 FROM sequence WHERE n < 2001
   )
   SELECT 1, ${class_id}, CONCAT('XLSX-', LPAD(n, 4, '0')),
          CONCAT('导出验收学生', n), 'OTHER', 'ACTIVE'
   FROM sequence;
   INSERT INTO enrollment (
       school_id, offering_id, student_id, guardian_id, status, enrolled_at
   )
   SELECT 1, ${attendance_offering_id}, student.id, NULL, 'ENROLLED',
          CURRENT_TIMESTAMP(3)
   FROM student
   WHERE student.school_id = 1
     AND student.student_no LIKE 'XLSX-%';
   UPDATE course_offering
   SET capacity = GREATEST(capacity, 3000),
       enrolled_count = (
           SELECT COUNT(*)
           FROM enrollment
           WHERE offering_id = ${attendance_offering_id}
             AND status = 'ENROLLED'
       )
   WHERE id = ${attendance_offering_id};
   INSERT INTO student_grade (
       school_id, offering_id, student_id, teacher_id, score,
       learning_evaluation, recorded_by, updated_by
   )
   SELECT 1, ${attendance_offering_id}, student.id, offering.teacher_id,
          MOD(student.id, 101), '超过两千行导出兼容性验收',
          teacher.user_id, teacher.user_id
   FROM student
   JOIN course_offering offering ON offering.id = ${attendance_offering_id}
   JOIN teacher ON teacher.id = offering.teacher_id
   WHERE student.school_id = 1
     AND student.student_no LIKE 'XLSX-%';"
grade_xlsx_status="$(status_of "${run_root}/student-grades.xlsx" \
  -c "${admin_jar}" -b "${admin_jar}" "${base_url}/grades/export.xlsx")"
[[ "${grade_xlsx_status}" == "200" ]] || fail "student grade Excel export failed"
assert_xlsx "${run_root}/student-grades.xlsx" 2001 \
  "班级,学号,学生姓名,课程,教师,成绩,学习评价,更新时间"

log_step "Verifying supervision, evaluation, audit and extended reporting"

history_offerings_status="$(
  status_of "${run_root}/history-offerings.json" \
    -c "${admin_jar}" -b "${admin_jar}" "${base_url}/offerings"
)"
[[ "${history_offerings_status}" == "200" ]] \
  || fail "school admin could not resolve the historical evaluation offering"
history_offering_id="$(
  jq -r \
    '[.[] | select(.offeringCode == "O-DEMO-TECH-HISTORY")][0].id // empty' \
    "${run_root}/history-offerings.json"
)"
[[ "${history_offering_id}" =~ ^[0-9]+$ ]] \
  || fail "historical evaluation offering could not be resolved by code"

evaluation_mine_before_status="$(
  status_of "${run_root}/evaluation-mine-before.json" \
    -c "${parent_jar}" -b "${parent_jar}" \
    "${base_url}/evaluations/mine"
)"
[[ "${evaluation_mine_before_status}" == "200" ]] \
  || fail "guardian could not list own submitted evaluations"
jq -e '
  length == 1
  and .[0].studentId == 2
  and ((.[0] | keys | sort)
       == (["id", "offeringId", "rating", "courseRating", "teacherRating", "studentId", "submittedAt"] | sort))
' "${run_root}/evaluation-mine-before.json" >/dev/null \
  || fail "guardian own-evaluation projection or scope is incorrect"

evaluation_body="$(
  jq -nc --argjson offeringId "${history_offering_id}" \
    '{
      studentId:1,
      offeringId:$offeringId,
      courseRating:5,
      teacherRating:4,
      comment:"一键验收课程评价"
    }'
)"
write_api 201 POST /evaluations "${parent_jar}" "${evaluation_body}" \
  "${run_root}/evaluation-created.json"
write_api 409 POST /evaluations "${parent_jar}" "${evaluation_body}" \
  "${run_root}/evaluation-duplicate.json"
jq -e '.code == "EVALUATION_ALREADY_SUBMITTED"' \
  "${run_root}/evaluation-duplicate.json" >/dev/null \
  || fail "one-time evaluation guard failed"
evaluation_mine_after_status="$(
  status_of "${run_root}/evaluation-mine-after.json" \
    -c "${parent_jar}" -b "${parent_jar}" \
    "${base_url}/evaluations/mine"
)"
[[ "${evaluation_mine_after_status}" == "200" ]] \
  || fail "guardian could not reload own submitted evaluations"
jq -e \
  --argjson offeringId "${history_offering_id}" \
  'length == 2
   and any(.[];
     .studentId == 1 and .offeringId == $offeringId
     and .courseRating == 5 and .teacherRating == 4)
   and all(.[];
     ((keys | sort)
      == (["id", "offeringId", "rating", "courseRating", "teacherRating", "studentId", "submittedAt"] | sort)))' \
  "${run_root}/evaluation-mine-after.json" >/dev/null \
  || fail "guardian own-evaluation list did not reflect the new submission"

evaluation_list_status="$(
  status_of "${run_root}/evaluation-list.json" \
    -c "${admin_jar}" -b "${admin_jar}" \
    "${base_url}/evaluations"
)"
[[ "${evaluation_list_status}" == "200" ]] || fail "school admin could not list evaluations"
jq -e \
  'length >= 2
   and all(.schoolId == 1)
   and any(.[];
     .studentName != null
     and .guardianName != null
     and .courseRating >= 1
     and .teacherRating >= 1
     and has("comment"))' \
  "${run_root}/evaluation-list.json" >/dev/null \
  || fail "school admin evaluation view omitted ratings or guardian comments"

evaluation_summary_status="$(
  status_of "${run_root}/evaluation-summary.json" \
    -c "${admin_jar}" -b "${admin_jar}" \
    "${base_url}/evaluations/summary?schoolId=1"
)"
[[ "${evaluation_summary_status}" == "200" ]] \
  || fail "school admin evaluation summary failed"
jq -e '
  .evaluationCount >= 2
  and .rating5Count >= 1
  and .averageRating >= 1 and .averageRating <= 5
  and .averageCourseRating >= 1 and .averageCourseRating <= 5
  and .averageTeacherRating >= 1 and .averageTeacherRating <= 5
  and .satisfactionRate >= 0 and .satisfactionRate <= 100
  and .teacherSatisfactionRate >= 0 and .teacherSatisfactionRate <= 100
' "${run_root}/evaluation-summary.json" >/dev/null \
  || fail "evaluation summary returned inconsistent aggregates"

teacher_hours_status="$(
  status_of "${run_root}/teacher-hours.json" \
    -c "${admin_jar}" -b "${admin_jar}" \
    "${base_url}/reports/teacher-hours?schoolId=1"
)"
[[ "${teacher_hours_status}" == "200" ]] \
  || fail "school admin teacher-hours report failed"
jq -e '
  length > 0
  and any(.[];
    .teacherId == 1
    and .completedSessions >= 1
    and .completedHours >= 1)
' "${run_root}/teacher-hours.json" >/dev/null \
  || fail "teacher-hours report did not include the completed acceptance session"

school_alerts_status="$(
  status_of "${run_root}/school-supervision-alerts.json" \
    -c "${admin_jar}" -b "${admin_jar}" \
    "${base_url}/supervision/alerts"
)"
[[ "${school_alerts_status}" == "200" ]] \
  || fail "school admin could not read same-school supervision alerts"
jq -e 'all(.schoolId == 1)' "${run_root}/school-supervision-alerts.json" >/dev/null \
  || fail "school supervision alert list leaked another school"
cross_school_alerts_status="$(
  status_of "${run_root}/cross-school-supervision-alerts.json" \
    -c "${second_admin_jar}" -b "${second_admin_jar}" \
    "${base_url}/supervision/alerts?schoolId=1"
)"
[[ "${cross_school_alerts_status}" == "403" ]] \
  || fail "second school admin could query first-school supervision alerts"
scan_runs_admin_status="$(
  status_of "${run_root}/supervision-scan-runs-school-admin.json" \
    -c "${admin_jar}" -b "${admin_jar}" \
    "${base_url}/supervision/alerts/scan-runs?limit=10"
)"
[[ "${scan_runs_admin_status}" == "403" ]] \
  || fail "manual regulator scan-run endpoint became active for a four-role account"

audit_status="$(
  status_of "${run_root}/audit-logs.json" \
    -c "${admin_jar}" -b "${admin_jar}" \
    "${base_url}/audit-logs?schoolId=1"
)"
[[ "${audit_status}" == "200" ]] || fail "school admin could not read operation audit"
jq -e \
  'length > 10
   and all(.targetSchoolId == 1)
   and all(has("requestBody") | not)
   and all(.sourceFingerprint | length == 64)
   and any(.[];
     .actorRole == "SCHOOL_ADMIN"
     and .httpMethod == "POST"
     and .requestPath == "/api/service-plans"
     and .responseStatus == 201)
   and any(.[];
     .actorRole == "TEACHER"
     and .httpMethod == "PUT"
     and (.requestPath | startswith("/api/sessions/"))
     and (.requestPath | endswith("/attendance"))
     and .responseStatus == 200)
   and any(.[];
     .actorRole == "GUARDIAN"
     and .httpMethod == "POST"
     and .requestPath == "/api/evaluations"
     and .responseStatus == 201)
   and any(.[];
     .actorRole == "SCHOOL_ADMIN"
     and .httpMethod == "PUT"
     and (.requestPath | startswith("/api/attendance-corrections/"))
     and (.requestPath | endswith("/review"))
     and .responseStatus == 200)' \
  "${run_root}/audit-logs.json" >/dev/null \
  || fail "operation audit scope or sanitization contract failed"

performance_status="$(
  status_of "${run_root}/course-performance.json" \
    -c "${admin_jar}" -b "${admin_jar}" \
    "${base_url}/reports/course-performance?schoolId=1"
)"
[[ "${performance_status}" == "200" ]] || fail "course performance report failed"
jq -e 'length > 0 and all(has("courseName"))' \
  "${run_root}/course-performance.json" >/dev/null \
  || fail "course performance report returned no usable rows"
performance_csv_status="$(
  status_of "${run_root}/course-performance.csv" \
    -c "${admin_jar}" -b "${admin_jar}" \
    "${base_url}/reports/course-performance.csv?schoolId=1"
)"
[[ "${performance_csv_status}" == "200" ]] || fail "course performance CSV export failed"
grep -q '课程名称' "${run_root}/course-performance.csv" \
  || fail "course performance CSV header is missing"
for xlsx_path in \
  "/courses/import-template.xlsx" \
  "/courses.xlsx" \
  "/enrollments/roster.xlsx?offeringId=${attendance_offering_id}" \
  "/reports/course-performance.xlsx?schoolId=1" \
  "/reports/rectifications.xlsx?schoolId=1"; do
  xlsx_name="$(tr '/?=&' '_' <<<"${xlsx_path}")"
  xlsx_status="$(
    status_of "${run_root}/${xlsx_name}" \
      -c "${admin_jar}" -b "${admin_jar}" "${base_url}${xlsx_path}"
  )"
  [[ "${xlsx_status}" == "200" ]] || fail "Excel export failed: ${xlsx_path}"
  assert_xlsx "${run_root}/${xlsx_name}"
done
upload_api 200 /courses/import "${admin_jar}" \
  "${run_root}/_courses_import-template.xlsx" \
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" \
  "${run_root}/course-import-result.json"
jq -e '.processedCount == 1' "${run_root}/course-import-result.json" >/dev/null \
  || fail "course Excel import did not process the template row"

report_status="$(
  status_of "${run_root}/report.json" \
    -c "${admin_jar}" -b "${admin_jar}" "${base_url}/reports/overview"
)"
[[ "${report_status}" == "200" ]] || fail "school admin report failed"
jq -e '
  .schoolCount == 1
  and .studentCount >= 4
  and .teacherCount >= 2
  and .courseCount >= 4
  and .enrollmentCount >= 4
  and (.schools | length == 1)
  and .schools[0].schoolId == 1
' "${run_root}/report.json" >/dev/null || fail "school admin report scope is inconsistent"
verify_parent_jar="${run_root}/verify-parent.cookies"
login_as verify_parent GUARDIAN "${verify_parent_jar}" "VerifyParent@2026"
password_change_body='{"currentPassword":"VerifyParent@2026","newPassword":"VerifyParentChanged@2026"}'
write_api 204 POST /auth/change-password "${verify_parent_jar}" \
  "${password_change_body}" "${run_root}/password-changed.json"
changed_session_status="$(
  status_of "${run_root}/changed-session.json" \
    -c "${verify_parent_jar}" -b "${verify_parent_jar}" "${base_url}/auth/me"
)"
[[ "${changed_session_status}" == "401" ]] \
  || fail "password change did not invalidate the current session"
old_password_body='{"username":"verify_parent","password":"VerifyParent@2026","expectedRole":"GUARDIAN"}'
write_api 401 POST /auth/login "${verify_parent_jar}" "${old_password_body}" \
  "${run_root}/old-password-login.json"
jq -e '.code == "INVALID_CREDENTIALS"' \
  "${run_root}/old-password-login.json" >/dev/null \
  || fail "old password remained valid after password change"
login_as verify_parent GUARDIAN "${verify_parent_jar}" \
  "VerifyParentChanged@2026"

logout_token="$(csrf_for "${parent_jar}")"
logout_status="$(
  status_of "${run_root}/logout.json" \
    -c "${parent_jar}" -b "${parent_jar}" \
    -X POST -H "X-XSRF-TOKEN: ${logout_token}" \
    "${base_url}/auth/logout"
)"
[[ "${logout_status}" == "204" ]] || fail "logout failed"
after_logout_status="$(
  status_of "${run_root}/after-logout.json" \
    -c "${parent_jar}" -b "${parent_jar}" "${base_url}/auth/me"
)"
[[ "${after_logout_status}" == "401" ]] || fail "session remained valid after logout"

log_step "Running four-role desktop and mobile browser E2E"

(
  cd "${web_dir}"
  exec env \
    VITE_API_PROXY_TARGET="http://127.0.0.1:${server_port}" \
    PATH="${node_bin_dir}:${PATH}" \
    npm run preview -- --host 127.0.0.1 --port "${web_port}"
) >"${run_root}/web.log" 2>&1 &
web_pid="$!"

web_ready=false
for _ in {1..40}; do
  if ! kill -0 "${web_pid}" >/dev/null 2>&1; then
    fail "frontend preview exited before browser E2E"
  fi
  if curl --noproxy '*' --fail --silent --show-error --max-time 2 \
      "http://127.0.0.1:${web_port}/login" >/dev/null 2>&1; then
    web_ready=true
    break
  fi
  sleep 0.25
done
[[ "${web_ready}" == "true" ]] || fail "frontend preview did not become ready"

playwright_executable="${PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH:-}"
if [[ -z "${playwright_executable}" \
    && -x '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' ]]; then
  playwright_executable='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
fi

(
  cd "${web_dir}"
  E2E_BASE_URL="http://127.0.0.1:${web_port}" \
  E2E_DEMO_PASSWORD="${demo_password}" \
  PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH="${playwright_executable}" \
  PATH="${node_bin_dir}:${PATH}" \
    npm run test:e2e
) || fail "Playwright browser E2E failed"

terminate_pid "${web_pid}" "frontend preview"
web_pid=""

log_step "Checking database invariants and expired-demo re-anchoring"

inconsistent_counts="$(
  "${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
    -uroot --skip-column-names "${db_name}" -e \
    "SELECT COUNT(*) FROM course_offering o WHERE o.enrolled_count != (SELECT COUNT(*) FROM enrollment e WHERE e.offering_id=o.id AND e.status='ENROLLED')"
)"
[[ "${inconsistent_counts}" == "0" ]] || fail "offering counts diverged from enrollment rows"

stop_server
historical_fingerprint_before="$(
  "${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
    -uroot --skip-column-names "${db_name}" -e \
    "SELECT SHA2(CONCAT_WS('|',
         (SELECT CONCAT_WS(',', status, requested_at)
          FROM attendance_correction_request WHERE id = 1),
         (SELECT CONCAT_WS(',', rating, submitted_at)
          FROM course_evaluation WHERE id = 1),
         (SELECT GROUP_CONCAT(
              CONCAT_WS(',', id, from_status, to_status, action_type, acted_at)
              ORDER BY id SEPARATOR ';'
          ) FROM supervision_alert_action WHERE alert_id = 1),
         (SELECT GROUP_CONCAT(
              CONCAT_WS(',', id, actor_user_id, request_path, occurred_at)
              ORDER BY id SEPARATOR ';'
          ) FROM operation_audit WHERE id IN (1, 2))
     ), 256)"
)"
"${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
  -uroot "${db_name}" -e \
  "SET @shanghai_today = DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+08:00'));
   UPDATE course_offering
   SET start_date = DATE_SUB(@shanghai_today, INTERVAL 30 DAY),
       end_date = DATE_SUB(@shanghai_today, INTERVAL 1 DAY)
   WHERE offering_code IN (
       'O-DEMO-ART-001',
       'O-DEMO-TECH-001',
       'O-DEMO-SCI-001'
   );
   UPDATE lesson_session
   SET session_date = DATE_SUB(@shanghai_today, INTERVAL 2 DAY)
   WHERE id = 2;
   UPDATE lesson_session
   SET session_date = DATE_SUB(@shanghai_today, INTERVAL 1 DAY)
   WHERE id = 3;"
start_server
grep -Fq 'Re-anchored the expired demo timeline' "${run_root}/server.log" \
  || fail "expired demo timeline was not re-anchored on startup"
migration_count_after_restart="$(
  "${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
    -uroot --skip-column-names "${db_name}" -e \
    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1"
)"
[[ "${migration_count_after_restart}" == "21" ]] \
  || fail "Flyway restart was not idempotent"
refreshed_timeline_invariants="$(
  "${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
    -uroot --skip-column-names "${db_name}" -e \
    "SELECT
       (SELECT COUNT(*)
        FROM academic_term
        WHERE id = 1
          AND term_code = 'DEMO-CURRENT'
          AND start_date <= DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+08:00'))
          AND end_date > DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+08:00')))
       +
       (SELECT COUNT(*)
        FROM lesson_session
        WHERE id IN (2, 3)
          AND status = 'SCHEDULED'
          AND TIMESTAMP(session_date, start_time)
              > CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+08:00'))
       +
       (SELECT COUNT(*)
        FROM leave_request lr
        JOIN lesson_session ls ON ls.id = lr.session_id
        WHERE lr.id = 1
          AND lr.status = 'APPROVED'
          AND TIMESTAMP(ls.session_date, ls.start_time)
              > CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+08:00'))
       +
       (SELECT COUNT(*)
       FROM schedule_adjustment sa
        JOIN lesson_session ls ON ls.id = sa.session_id
        WHERE sa.id = 1
          AND sa.status = 'APPLIED'
          AND sa.adjusted_session_date = ls.session_date)
       +
       (SELECT COUNT(*) = 2
        FROM school_service_plan
        WHERE (id = 1 AND plan_code = 'PLAN-DEMO001-CURRENT')
           OR (id = 2 AND plan_code = 'PLAN-DEMO002-CURRENT'))
       +
       (SELECT COUNT(*)
        FROM enrollment e
        JOIN leave_request lr
          ON lr.offering_id = e.offering_id
         AND lr.student_id = e.student_id
        JOIN lesson_session ls ON ls.id = lr.session_id
        WHERE e.id = 1
          AND lr.id = 1
          AND e.enrolled_at < lr.submitted_at
          AND lr.submitted_at < lr.reviewed_at
          AND lr.reviewed_at < TIMESTAMP(ls.session_date, ls.start_time))"
)"
[[ "${refreshed_timeline_invariants}" == "7" ]] \
  || fail "re-anchored demo timeline is internally inconsistent"
historical_fingerprint_after="$(
  "${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
    -uroot --skip-column-names "${db_name}" -e \
    "SELECT SHA2(CONCAT_WS('|',
         (SELECT CONCAT_WS(',', status, requested_at)
          FROM attendance_correction_request WHERE id = 1),
         (SELECT CONCAT_WS(',', rating, submitted_at)
          FROM course_evaluation WHERE id = 1),
         (SELECT GROUP_CONCAT(
              CONCAT_WS(',', id, from_status, to_status, action_type, acted_at)
              ORDER BY id SEPARATOR ';'
          ) FROM supervision_alert_action WHERE alert_id = 1),
         (SELECT GROUP_CONCAT(
              CONCAT_WS(',', id, actor_user_id, request_path, occurred_at)
              ORDER BY id SEPARATOR ';'
          ) FROM operation_audit WHERE id IN (1, 2))
     ), 256)"
)"
[[ "${historical_fingerprint_after}" == "${historical_fingerprint_before}" ]] \
  || fail "demo timeline refresh modified append-only historical evidence"
timeline_fingerprint_before="$(
  "${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
    -uroot --skip-column-names "${db_name}" -e \
    "SELECT SHA2(CONCAT_WS('|',
         (SELECT CONCAT_WS(',', term_code, start_date, end_date)
          FROM academic_term WHERE id = 1),
         (SELECT GROUP_CONCAT(
              CONCAT_WS(',', offering_code, start_date, end_date,
                        enrollment_start, enrollment_end)
              ORDER BY offering_code SEPARATOR ';'
          ) FROM course_offering
          WHERE offering_code IN (
              'O-DEMO-ART-001',
              'O-DEMO-TECH-001',
              'O-DEMO-SCI-001'
          )),
         (SELECT GROUP_CONCAT(
              CONCAT_WS(',', id, session_date, start_time, status)
              ORDER BY id SEPARATOR ';'
          ) FROM lesson_session WHERE id IN (2, 3))
     ), 256)"
)"
stop_server
start_server
if grep -Fq 'Re-anchored the expired demo timeline' "${run_root}/server.log"; then
  fail "healthy demo timeline drifted during an idempotent restart"
fi
timeline_fingerprint_after="$(
  "${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
    -uroot --skip-column-names "${db_name}" -e \
    "SELECT SHA2(CONCAT_WS('|',
         (SELECT CONCAT_WS(',', term_code, start_date, end_date)
          FROM academic_term WHERE id = 1),
         (SELECT GROUP_CONCAT(
              CONCAT_WS(',', offering_code, start_date, end_date,
                        enrollment_start, enrollment_end)
              ORDER BY offering_code SEPARATOR ';'
          ) FROM course_offering
          WHERE offering_code IN (
              'O-DEMO-ART-001',
              'O-DEMO-TECH-001',
              'O-DEMO-SCI-001'
          )),
         (SELECT GROUP_CONCAT(
              CONCAT_WS(',', id, session_date, start_time, status)
              ORDER BY id SEPARATOR ';'
          ) FROM lesson_session WHERE id IN (2, 3))
     ), 256)"
)"
[[ "${timeline_fingerprint_after}" == "${timeline_fingerprint_before}" ]] \
  || fail "healthy demo timeline changed during restart"

log_step "Preserving an expired demo workflow after its term is closed"

stop_server
"${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
  -uroot "${db_name}" -e \
  "SET @shanghai_today = DATE(CONVERT_TZ(UTC_TIMESTAMP(), '+00:00', '+08:00'));
   UPDATE academic_term SET status = 'CLOSED' WHERE id = 1;
   UPDATE course_offering
   SET start_date = DATE_SUB(@shanghai_today, INTERVAL 30 DAY),
       end_date = DATE_SUB(@shanghai_today, INTERVAL 1 DAY)
   WHERE offering_code IN (
       'O-DEMO-ART-001',
       'O-DEMO-TECH-001',
       'O-DEMO-SCI-001'
   );
   UPDATE lesson_session
   SET session_date = DATE_SUB(@shanghai_today, INTERVAL 2 DAY)
   WHERE id = 2;
   UPDATE lesson_session
   SET session_date = DATE_SUB(@shanghai_today, INTERVAL 1 DAY)
   WHERE id = 3;"
closed_term_fingerprint_before="$(
  "${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
    -uroot --skip-column-names "${db_name}" -e \
    "SELECT SHA2(CONCAT_WS('|',
         (SELECT CONCAT_WS(
              ',', id, term_code, term_name, start_date, end_date, status
          ) FROM academic_term WHERE id = 1),
         (SELECT GROUP_CONCAT(
              CONCAT_WS(',', id, school_id, school_year, status)
              ORDER BY id SEPARATOR ';'
          ) FROM school_class WHERE id IN (1, 2, 3)),
         (SELECT GROUP_CONCAT(
              CONCAT_WS(
                  ',', id, school_id, term_id, plan_code, plan_name, status,
                  submitted_at, filed_at, activated_at
              )
              ORDER BY id SEPARATOR ';'
          ) FROM school_service_plan WHERE id IN (1, 2)),
         (SELECT GROUP_CONCAT(
              CONCAT_WS(',', id, school_id, term_id, event_date, day_type)
              ORDER BY id SEPARATOR ';'
          ) FROM school_calendar_event WHERE id IN (1, 2, 3)),
         (SELECT GROUP_CONCAT(
              CONCAT_WS(
                  ',', id, offering_code, term, term_id, plan_id, start_date,
                  end_date, enrollment_start, enrollment_end, status
              )
              ORDER BY id SEPARATOR ';'
          ) FROM course_offering
          WHERE offering_code IN (
              'O-DEMO-ART-001',
              'O-DEMO-TECH-001',
              'O-DEMO-SCI-001'
          )),
         (SELECT CONCAT_WS(',', id, status, enrolled_at)
          FROM enrollment WHERE id = 1),
         (SELECT GROUP_CONCAT(
              CONCAT_WS(',', id, session_date, start_time, status)
              ORDER BY id SEPARATOR ';'
          ) FROM lesson_session WHERE id IN (2, 3)),
         (SELECT CONCAT_WS(',', id, status, submitted_at, reviewed_at)
          FROM leave_request WHERE id = 1),
         (SELECT CONCAT_WS(
              ',',
              id,
              status,
              original_session_date,
              adjusted_session_date,
              applied_at
          ) FROM schedule_adjustment WHERE id = 1)
     ), 256)"
)"
start_server
grep -Fq 'Demo timeline check completed; changed data was preserved' \
  "${run_root}/server.log" \
  || fail "closed demo term did not preserve its expired workflow"
if grep -Fq 'Re-anchored the expired demo timeline' "${run_root}/server.log"; then
  fail "closed demo term was re-anchored"
fi
closed_term_fingerprint_after="$(
  "${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
    -uroot --skip-column-names "${db_name}" -e \
    "SELECT SHA2(CONCAT_WS('|',
         (SELECT CONCAT_WS(
              ',', id, term_code, term_name, start_date, end_date, status
          ) FROM academic_term WHERE id = 1),
         (SELECT GROUP_CONCAT(
              CONCAT_WS(',', id, school_id, school_year, status)
              ORDER BY id SEPARATOR ';'
          ) FROM school_class WHERE id IN (1, 2, 3)),
         (SELECT GROUP_CONCAT(
              CONCAT_WS(
                  ',', id, school_id, term_id, plan_code, plan_name, status,
                  submitted_at, filed_at, activated_at
              )
              ORDER BY id SEPARATOR ';'
          ) FROM school_service_plan WHERE id IN (1, 2)),
         (SELECT GROUP_CONCAT(
              CONCAT_WS(',', id, school_id, term_id, event_date, day_type)
              ORDER BY id SEPARATOR ';'
          ) FROM school_calendar_event WHERE id IN (1, 2, 3)),
         (SELECT GROUP_CONCAT(
              CONCAT_WS(
                  ',', id, offering_code, term, term_id, plan_id, start_date,
                  end_date, enrollment_start, enrollment_end, status
              )
              ORDER BY id SEPARATOR ';'
          ) FROM course_offering
          WHERE offering_code IN (
              'O-DEMO-ART-001',
              'O-DEMO-TECH-001',
              'O-DEMO-SCI-001'
          )),
         (SELECT CONCAT_WS(',', id, status, enrolled_at)
          FROM enrollment WHERE id = 1),
         (SELECT GROUP_CONCAT(
              CONCAT_WS(',', id, session_date, start_time, status)
              ORDER BY id SEPARATOR ';'
          ) FROM lesson_session WHERE id IN (2, 3)),
         (SELECT CONCAT_WS(',', id, status, submitted_at, reviewed_at)
          FROM leave_request WHERE id = 1),
         (SELECT CONCAT_WS(
              ',',
              id,
              status,
              original_session_date,
              adjusted_session_date,
              applied_at
          ) FROM schedule_adjustment WHERE id = 1)
     ), 256)"
)"
[[ "${closed_term_fingerprint_after}" == "${closed_term_fingerprint_before}" ]] \
  || fail "closed demo term was partially refreshed"

log_step "Rejecting traffic through readiness when MySQL becomes unavailable"

"${mysql_admin}" --no-defaults --protocol=socket \
  --connect-timeout=2 --shutdown-timeout=10 \
  --socket="${mysql_socket}" -uroot shutdown >/dev/null
wait "${mysql_pid}" >/dev/null 2>&1 || true
mysql_pid=""

readiness_rejected=false
readiness_body="${run_root}/readiness-down.json"
for _ in {1..20}; do
  readiness_code="$(curl --noproxy '*' --silent --show-error --max-time 5 \
    --output "${readiness_body}" --write-out '%{http_code}' \
    "http://127.0.0.1:${server_port}/readyz" || true)"
  if [[ "${readiness_code}" == "503" ]] \
      && jq -e '.status == "DOWN"' "${readiness_body}" >/dev/null 2>&1; then
    readiness_rejected=true
    break
  fi
  sleep 1
done
[[ "${readiness_rejected}" == "true" ]] \
  || fail "readiness did not become DOWN after MySQL stopped"
curl --noproxy '*' -fsS "http://127.0.0.1:${server_port}/livez" \
  | jq -e '.status == "UP"' >/dev/null \
  || fail "database outage incorrectly marked application liveness DOWN"
curl --noproxy '*' -fsS \
  "http://127.0.0.1:${management_port}/actuator/prometheus" \
  | grep -Eq '^jvm_info' \
  || fail "Prometheus metrics disappeared during a database outage"
stop_server

printf "\n[verify] SUCCESS\n"
printf "[verify] Backend: %s automated tests + executable JAR + CycloneDX SBOM passed\n" "${test_count}"
printf "[verify] Frontend: Vitest + typecheck + production build + audit + desktop/mobile Playwright passed\n"
printf "[verify] Database: MySQL %s populated V15 upgrade + default V18 -> demo V20/V21 + four-role school isolation/grade history/restart preservation passed\n" "${mysql_version}"
printf "[verify] E2E: auth/RBAC/CRUD/planning/enrollment/leave/teaching/correction/supervision/evaluation/audit/reports passed\n"
printf "[verify] Operations: atomic frontend/backend rollback + encrypted backup/restore + backup-service-aware health transitions passed\n"
printf "[verify] Supply chain: CI + OWASP high-severity gate + SBOM contracts passed\n"
printf "[verify] Observability: liveness/readiness/Prometheus + database-outage readiness rejection passed\n"
