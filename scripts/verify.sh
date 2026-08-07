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
production_check_script="${project_root}/scripts/check-production.sh"
systemd_dir="${project_root}/deploy/systemd"
config_example_dir="${project_root}/deploy/config"
production_runbook="${project_root}/docs/production-runbook.md"
ci_workflow="${project_root}/.github/workflows/verify.yml"
dependabot_config="${project_root}/.github/dependabot.yml"
environment_example="${project_root}/.env.example"
server_port="${AFTER_SCHOOL_VERIFY_SERVER_PORT:-18081}"
management_port="${AFTER_SCHOOL_VERIFY_MANAGEMENT_PORT:-18082}"
mysql_port="${AFTER_SCHOOL_VERIFY_MYSQL_PORT:-18306}"
demo_password="${AFTER_SCHOOL_DEMO_PASSWORD:-123456}"
run_root=""
mysql_pid=""
server_pid=""
prod_guard_pid=""
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

cleanup() {
  local mysql_admin_pid=""
  local mysql_admin_attempt=0

  terminate_pid "${prod_guard_pid}" "prod Flyway guard"
  prod_guard_pid=""
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
    gzip stat find install; do
  command -v "${required_command}" >/dev/null 2>&1 \
    || fail "missing required command: ${required_command}"
done

[[ -d "${server_dir}" ]] || fail "missing backend directory"
[[ -d "${web_dir}" ]] || fail "missing frontend directory"

log_step "Checking production operations contract"

for executable_script in \
  "${web_install_script}" \
  "${web_select_script}" \
  "${server_install_script}" \
  "${server_select_script}" \
  "${backup_script}" \
  "${restore_script}" \
  "${monitor_script}" \
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
  V10__preserve_reviewed_leave_withdrawal_history.sql; do
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
  uk_course_evaluation_target; do
  grep -RhFq "${guard}" "${migration_dir}" || fail "missing schema guard ${guard}"
done

log_step "Running backend automated tests and packaging"

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
  "BOOT-INF/classes/com/afterschool/platform/demo/DemoTimelineRefresher.class" \
  "BOOT-INF/classes/db/migration/V5__add_academic_planning_and_resource_constraints.sql" \
  "BOOT-INF/classes/db/migration/V6__add_leave_and_attendance_correction_workflows.sql" \
  "BOOT-INF/classes/db/migration/V7__add_supervision_audit_evaluation_and_reporting.sql" \
  "BOOT-INF/classes/db/migration/V9__separate_current_guardian_authorization.sql" \
  "BOOT-INF/classes/db/migration/V10__preserve_reviewed_leave_withdrawal_history.sql" \
  "BOOT-INF/classes/db/demo/V4__seed_demo_workflow.sql" \
  "BOOT-INF/classes/db/demo/V8__seed_comprehensive_graduation_workflow.sql" \
  "BOOT-INF/classes/db/demo/V11__simplify_demo_login_credentials.sql"; do
  "${jdk21_home}/bin/jar" tf "${jar_path}" | grep -Fq "${jar_entry}" \
    || fail "backend JAR is missing ${jar_entry}"
done

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
    'INSERT INTO `flyway_schema_history` VALUES (1);'
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
  elif [[ "${args}" == *"table_name = 'flyway_schema_history'"* ]]; then
    printf '1\n'
  elif [[ "${args}" == *"information_schema.tables"* ]]; then
    printf '18\n'
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
"${backup_script}" >/dev/null
backup_test_file="$(find "${backup_test_dir}" -maxdepth 1 -type f \
  -name '*.sql.gz.age' -print -quit)"
[[ -n "${backup_test_file}" && -s "${backup_test_file}" \
    && -s "${backup_test_file}.sha256" ]] \
  || fail "encrypted backup and checksum were not created"
[[ -z "$(find "${backup_test_dir}" -maxdepth 1 -type f -name '*.sql' -print -quit)" ]] \
  || fail "backup pipeline wrote plaintext SQL to disk"
"${restore_script}" "${backup_test_file}" after_school_restore_verify \
  "${backup_credentials}" "${restore_identity}" >/dev/null
unset AFTER_SCHOOL_MYSQLDUMP_BIN AFTER_SCHOOL_MYSQL_BIN AFTER_SCHOOL_AGE_BIN \
  MYSQL_DATABASE BACKUP_LOCK_FILE MYSQL_DEFAULTS_FILE AGE_RECIPIENTS_FILE
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
export BACKUP_MAX_AGE_HOURS=36
export VERIFY_HEALTH_FAILED=0
export VERIFY_BACKUP_SERVICE_FAILED=0
"${monitor_script}" >/dev/null
[[ "$(sed -n '1p' "${MONITOR_STATE_FILE}")" == "HEALTHY" ]] \
  || fail "health monitor did not record healthy state"
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
  BACKUP_MAX_AGE_HOURS VERIFY_HEALTH_FAILED VERIFY_BACKUP_SERVICE_FAILED BACKUP_DIR
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

for port in "${server_port}" "${management_port}" "${mysql_port}"; do
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

  for _ in {1..40}; do
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
    fail "${guard_name} smoke test exceeded 10 seconds"
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

db_name="after_school_verify"
db_user="after_school_verify"
db_password="Verify-Only-Db-Password-2026"
"${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" -uroot <<SQL
CREATE DATABASE ${db_name} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER '${db_user}'@'127.0.0.1' IDENTIFIED BY '${db_password}';
GRANT ALL PRIVILEGES ON ${db_name}.* TO '${db_user}'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL

start_server() {
  local active_profile="${1:-demo}"
  local wait_for_demo_timeline="${2:-true}"
  local active_profile_value=""
  if [[ "${active_profile}" != "default" ]]; then
    active_profile_value="${active_profile}"
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
      DB_URL="jdbc:mysql://127.0.0.1:${mysql_port}/${db_name}?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=%2B08%3A00&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&useSSL=false" \
      DB_USERNAME="${db_user}" \
      DB_PASSWORD="${db_password}" \
      SPRINGDOC_ENABLED=false \
      APP_CORS_ALLOWED_ORIGIN="http://localhost:5173" \
      java -jar "${jar_path}"
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

log_step "Migrating the fresh database through the production-only V10 schema"

start_server default false
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
[[ "${production_history}" == "8:0:10" ]] \
  || fail "default profile did not stop at the production-only V10 schema: ${production_history}"
production_demo_users="$(
  "${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
    -uroot --skip-column-names "${db_name}" -e \
    "SELECT COUNT(*) FROM sys_user
     WHERE username IN ('admin', 'school_admin', 'teacher_wang', 'parent_chen')"
)"
[[ "${production_demo_users}" == "0" ]] \
  || fail "default profile unexpectedly inserted demo accounts"
stop_server

log_step "Switching the existing V10 schema to the real demo profile"

start_server
base_url="http://127.0.0.1:${server_port}/api"
curl --noproxy '*' -fsS "${base_url}/public/system-info" \
  | jq -e '.status == "ready"' >/dev/null || fail "system-info contract failed"

table_count="$(
  "${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
    -uroot --skip-column-names -e \
    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${db_name}' AND table_name != 'flyway_schema_history'"
)"
[[ "${table_count}" == "25" ]] || fail "expected 25 domain tables, found ${table_count}"
migration_count="$(
  "${mysql_client}" --no-defaults --protocol=socket --socket="${mysql_socket}" \
    -uroot --skip-column-names "${db_name}" -e \
    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1"
)"
[[ "${migration_count}" == "11" ]] || fail "expected 11 successful Flyway migrations"
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

login_as() {
  local username="$1"
  local expected_role="$2"
  local cookie_jar="$3"
  local password="${4:-${demo_password}}"
  local output_file="${run_root}/login-${username}.json"
  local body
  body="$(jq -nc --arg username "${username}" --arg password "${password}" \
    '{username:$username,password:$password}')"
  write_api 200 POST /auth/login "${cookie_jar}" "${body}" "${output_file}"
  jq -e --arg role "${expected_role}" '.role == $role' "${output_file}" >/dev/null \
    || fail "${username} did not receive role ${expected_role}"
  local me_status
  me_status="$(status_of "${run_root}/me-${username}.json" \
    -c "${cookie_jar}" -b "${cookie_jar}" "${base_url}/auth/me")"
  [[ "${me_status}" == "200" ]] || fail "${username} session was not persisted"
}

log_step "Verifying authentication, CSRF, CORS and four-role data scopes"

unauth_status="$(status_of "${run_root}/unauth.json" "${base_url}/auth/me")"
[[ "${unauth_status}" == "401" ]] || fail "unauthenticated /auth/me must return 401"

bad_jar="${run_root}/bad.cookies"
bad_body='{"username":"admin","password":"wrong-password"}'
write_api 401 POST /auth/login "${bad_jar}" "${bad_body}" "${run_root}/bad-login.json"
jq -e '.code == "INVALID_CREDENTIALS"' "${run_root}/bad-login.json" >/dev/null \
  || fail "bad credentials error contract failed"

csrf_missing_status="$(
  status_of "${run_root}/csrf-missing.json" \
    -X POST -H 'Content-Type: application/json' --data-binary '{}' \
    "${base_url}/auth/login"
)"
[[ "${csrf_missing_status}" == "403" ]] || fail "unsafe request without CSRF must return 403"

allowed_cors="$(
  curl --noproxy '*' -sS -D - -o /dev/null \
    -X OPTIONS \
    -H 'Origin: http://localhost:5173' \
    -H 'Access-Control-Request-Method: POST' \
    "${base_url}/auth/logout"
)"
grep -Fiq 'Access-Control-Allow-Origin: http://localhost:5173' <<<"${allowed_cors}" \
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
parent_jar="${run_root}/parent.cookies"
teacher_jar="${run_root}/teacher.cookies"
regulator_jar="${run_root}/regulator.cookies"
login_as school_admin SCHOOL_ADMIN "${admin_jar}"

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
  '{schoolId:1,classId:$classId,studentNo:"S-VERIFY-001",fullName:"验收学生",gender:"FEMALE",dateOfBirth:"2015-08-08",status:"ACTIVE"}')"
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

create_offering() {
  local code="$1"
  local teacher_id="$2"
  local week_day="$3"
  local grade_course_id="$4"
  local capacity="$5"
  local enrollment_start="$6"
  local enrollment_end="$7"
  local output="$8"
  local body
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
    '{schoolId:1,courseId:$courseId,teacherId:$teacherId,offeringCode:$code,term:$term,weekDay:$weekDay,startTime:"16:30:00",endTime:"17:30:00",startDate:$startDate,endDate:$endDate,enrollmentStart:$enrollmentStart,enrollmentEnd:$enrollmentEnd,capacity:$capacity,classroom:"验收教室",status:"PUBLISHED"}')"
  write_api 201 POST /offerings "${admin_jar}" "${body}" "${output}"
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
    const start = new Date(now)
    start.setDate(start.getDate() + 1)
    const end = new Date(start)
    const past = new Date(now)
    past.setDate(past.getDate() - 1)
    const weekDay = start.getDay() === 0 ? 7 : start.getDay()
    process.stdout.write(
      `${format(start)} ${format(end)} ${weekDay} ${format(past)}`,
    )
  '
)"
read -r attendance_start_date attendance_end_date attendance_week_day attendance_past_date \
  <<<"${attendance_dates}"
attendance_offering_body="$(
  jq -nc \
    --argjson courseId "${course_id}" \
    --argjson weekDay "${attendance_week_day}" \
    --arg startDate "${attendance_start_date}" \
    --arg endDate "${attendance_end_date}" \
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
      status:"PUBLISHED"
    }'
)"
write_api 201 POST /offerings "${admin_jar}" "${attendance_offering_body}" \
  "${run_root}/attendance-offering.json"
attendance_offering_id="$(jq -r '.id' "${run_root}/attendance-offering.json")"

login_as parent_chen GUARDIAN "${parent_jar}"
parent_students_status="$(
  status_of "${run_root}/parent-students.json" \
    -c "${parent_jar}" -b "${parent_jar}" "${base_url}/guardian/students"
)"
[[ "${parent_students_status}" == "200" ]] || fail "guardian could not list bound students"
jq -e 'length == 2 and all(.id == 1 or .id == 2)' "${run_root}/parent-students.json" >/dev/null \
  || fail "guardian student scope is incorrect"

enroll_body="$(jq -nc --argjson offeringId "${base_offering_id}" \
  '{studentId:1,offeringId:$offeringId}')"
write_api 201 POST /enrollments "${parent_jar}" "${enroll_body}" "${run_root}/enrolled.json"
write_api 409 POST /enrollments "${parent_jar}" "${enroll_body}" "${run_root}/duplicate.json"
jq -e '.code == "ALREADY_ENROLLED"' "${run_root}/duplicate.json" >/dev/null \
  || fail "duplicate enrollment rule failed"

grade_body="$(jq -nc --argjson offeringId "${grade_offering_id}" \
  '{studentId:1,offeringId:$offeringId}')"
write_api 409 POST /enrollments "${parent_jar}" "${grade_body}" "${run_root}/grade-reject.json"
jq -e '.code == "GRADE_NOT_ELIGIBLE"' "${run_root}/grade-reject.json" >/dev/null \
  || fail "grade enrollment rule failed"

future_body="$(jq -nc --argjson offeringId "${future_offering_id}" \
  '{studentId:1,offeringId:$offeringId}')"
write_api 409 POST /enrollments "${parent_jar}" "${future_body}" "${run_root}/time-reject.json"
jq -e '.code == "OUTSIDE_ENROLLMENT_WINDOW"' "${run_root}/time-reject.json" >/dev/null \
  || fail "enrollment-window rule failed"

conflict_body="$(jq -nc --argjson offeringId "${conflict_offering_id}" \
  '{studentId:1,offeringId:$offeringId}')"
write_api 409 POST /enrollments "${parent_jar}" "${conflict_body}" "${run_root}/conflict-reject.json"
jq -e '.code == "STUDENT_SCHEDULE_CONFLICT"' "${run_root}/conflict-reject.json" >/dev/null \
  || fail "student schedule-conflict rule failed"

not_bound_body="$(jq -nc --argjson offeringId "${base_offering_id}" \
  '{studentId:3,offeringId:$offeringId}')"
write_api 404 POST /enrollments "${parent_jar}" "${not_bound_body}" "${run_root}/not-bound.json"

for student_id in 1 2; do
  attendance_enrollment_body="$(
    jq -nc \
      --argjson studentId "${student_id}" \
      --argjson offeringId "${attendance_offering_id}" \
      '{studentId:$studentId,offeringId:$offeringId}'
  )"
  write_api 201 POST /enrollments "${parent_jar}" \
    "${attendance_enrollment_body}" \
    "${run_root}/attendance-enrollment-${student_id}.json"
done

log_step "Verifying concurrent capacity protection"

capacity_token="$(csrf_for "${parent_jar}")"
capacity_body_1="$(jq -nc --argjson offeringId "${capacity_offering_id}" \
  '{studentId:1,offeringId:$offeringId}')"
capacity_body_2="$(jq -nc --argjson offeringId "${capacity_offering_id}" \
  '{studentId:2,offeringId:$offeringId}')"
(
  curl --noproxy '*' -sS -o "${run_root}/capacity-1.json" \
    -w '%{http_code}' -b "${parent_jar}" \
    -X POST -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: ${capacity_token}" \
    --data-binary "${capacity_body_1}" \
    "${base_url}/enrollments" >"${run_root}/capacity-1.status"
) &
capacity_pid_1="$!"
(
  curl --noproxy '*' -sS -o "${run_root}/capacity-2.json" \
    -w '%{http_code}' -b "${parent_jar}" \
    -X POST -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: ${capacity_token}" \
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

write_api 200 POST "/offerings/${attendance_offering_id}/sessions/generate" \
  "${teacher_jar}" '{}' \
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

log_step "Verifying the complete school rectification workflow"

login_as admin REGULATOR "${regulator_jar}"
write_api 200 POST /supervision/alerts/scan "${regulator_jar}" \
  '{"schoolId":1,"lowAttendanceThreshold":0.8,"deadlineDays":7}' \
  "${run_root}/overdue-supervision-scan.json"
jq -e '.candidateCount >= 2 and .createdCount >= 1' \
  "${run_root}/overdue-supervision-scan.json" >/dev/null \
  || fail "overdue-attendance scan did not create the expected alert"
open_alerts_status="$(
  status_of "${run_root}/open-supervision-alerts.json" \
    -c "${regulator_jar}" -b "${regulator_jar}" \
    "${base_url}/supervision/alerts?schoolId=1&status=OPEN"
)"
[[ "${open_alerts_status}" == "200" ]] || fail "regulator could not list open alerts"
workflow_alert_id="$(
  jq -r --argjson sessionId "${session_id}" \
    '[.[] | select(
      .alertType == "OVERDUE_ATTENDANCE"
      and .sessionId == $sessionId
    )][0].id // empty' \
    "${run_root}/open-supervision-alerts.json"
)"
[[ "${workflow_alert_id}" =~ ^[0-9]+$ ]] \
  || fail "scan did not return an open alert for the overdue attendance session"
write_api 200 POST "/supervision/alerts/${workflow_alert_id}/transition" \
  "${admin_jar}" \
  '{"targetStatus":"ACKNOWLEDGED","comment":"学校已接收预警并核对课次名单"}' \
  "${run_root}/workflow-alert-acknowledged.json"
write_api 200 POST "/supervision/alerts/${workflow_alert_id}/transition" \
  "${admin_jar}" \
  '{"targetStatus":"RECTIFYING","comment":"已安排任课教师补录完整考勤"}' \
  "${run_root}/workflow-alert-rectifying.json"

attendance_body="$(
  jq -c '{records: map({studentId:.studentId,status:"PRESENT",remark:"验收通过"})}' \
    "${run_root}/attendance-list.json"
)"
write_api 200 PUT "/sessions/${session_id}/attendance" "${teacher_jar}" \
  "${attendance_body}" "${run_root}/attendance-saved.json"
jq -e 'length == 2 and all(.status == "PRESENT")' \
  "${run_root}/attendance-saved.json" >/dev/null || fail "attendance save failed"
write_api 200 POST "/supervision/alerts/${workflow_alert_id}/transition" \
  "${admin_jar}" \
  '{"targetStatus":"WAITING_VERIFY","comment":"考勤已补录，提交监管复核"}' \
  "${run_root}/workflow-alert-waiting.json"
write_api 200 POST "/supervision/alerts/${workflow_alert_id}/transition" \
  "${regulator_jar}" \
  '{"targetStatus":"RETURNED","comment":"请补充说明纸质签到表复核结果"}' \
  "${run_root}/workflow-alert-returned.json"
write_api 200 POST "/supervision/alerts/${workflow_alert_id}/transition" \
  "${admin_jar}" \
  '{"targetStatus":"RECTIFYING","comment":"已补充签到表复核说明"}' \
  "${run_root}/workflow-alert-resumed.json"
write_api 200 POST "/supervision/alerts/${workflow_alert_id}/transition" \
  "${admin_jar}" \
  '{"targetStatus":"WAITING_VERIFY","comment":"整改材料已补充，重新提交复核"}' \
  "${run_root}/workflow-alert-resubmitted.json"
write_api 200 POST "/supervision/alerts/${workflow_alert_id}/transition" \
  "${regulator_jar}" \
  '{"targetStatus":"CLOSED","comment":"整改证据完整，监管复核通过"}' \
  "${run_root}/workflow-alert-closed.json"
workflow_history_status="$(
  status_of "${run_root}/workflow-alert-history.json" \
    -c "${regulator_jar}" -b "${regulator_jar}" \
    "${base_url}/supervision/alerts/${workflow_alert_id}/history"
)"
[[ "${workflow_history_status}" == "200" ]] \
  || fail "regulator could not read the complete rectification history"
jq -e '
  map(.actionType) == [
    "CREATE",
    "ACKNOWLEDGE",
    "START_RECTIFICATION",
    "SUBMIT_VERIFICATION",
    "RETURN_FOR_RECTIFICATION",
    "RESUME_RECTIFICATION",
    "SUBMIT_VERIFICATION",
    "VERIFY_CLOSE"
  ]
  and .[1].actorRole == "SCHOOL_ADMIN"
  and .[4].actorRole == "REGULATOR"
  and .[-1].toStatus == "CLOSED"
' "${run_root}/workflow-alert-history.json" >/dev/null \
  || fail "school rectification history did not preserve the complete workflow"

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

log_step "Verifying academic planning, resources, leave and correction workflows"

term_body='{"termCode":"VERIFY-2098","termName":"2098 验收学期","startDate":"2098-01-01","endDate":"2098-03-31","status":"ACTIVE"}'
write_api 201 POST /terms "${regulator_jar}" "${term_body}" \
  "${run_root}/academic-term.json"
verify_term_id="$(jq -r '.id' "${run_root}/academic-term.json")"
[[ "${verify_term_id}" =~ ^[0-9]+$ ]] || fail "academic term creation returned no id"

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
write_api 200 POST "/service-plans/${verify_plan_id}/transitions" \
  "${admin_jar}" '{"targetStatus":"SUBMITTED"}' \
  "${run_root}/service-plan-submitted.json"
jq -e '.status == "SUBMITTED"' "${run_root}/service-plan-submitted.json" >/dev/null \
  || fail "school could not submit service plan"
write_api 200 POST "/service-plans/${verify_plan_id}/transitions" \
  "${regulator_jar}" '{"targetStatus":"FILED"}' \
  "${run_root}/service-plan-filed.json"
write_api 200 POST "/service-plans/${verify_plan_id}/transitions" \
  "${regulator_jar}" '{"targetStatus":"ACTIVE"}' \
  "${run_root}/service-plan-active.json"
jq -e '.status == "ACTIVE"' "${run_root}/service-plan-active.json" >/dev/null \
  || fail "regulator could not file and activate service plan"

room_body='{"schoolId":1,"roomCode":"ROOM-VERIFY-2098","roomName":"验收综合教室","location":"验收楼 101","capacity":30,"status":"ACTIVE"}'
write_api 201 POST /rooms "${admin_jar}" "${room_body}" \
  "${run_root}/academic-room.json"
verify_room_id="$(jq -r '.id' "${run_root}/academic-room.json")"

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
      status:"PUBLISHED",
      termId:$termId,
      planId:$planId,
      roomId:$roomId
    }'
)"
write_api 201 POST /offerings "${admin_jar}" "${planned_offering_body}" \
  "${run_root}/planned-offering.json"
planned_offering_id="$(jq -r '.id' "${run_root}/planned-offering.json")"
jq -e \
  --argjson termId "${verify_term_id}" \
  --argjson planId "${verify_plan_id}" \
  --argjson roomId "${verify_room_id}" \
  '.termId == $termId and .planId == $planId and .roomId == $roomId
   and .classroom == "验收综合教室"' \
  "${run_root}/planned-offering.json" >/dev/null \
  || fail "planned offering did not retain normalized academic resources"

planned_enrollment_body="$(
  jq -nc --argjson offeringId "${planned_offering_id}" \
    '{studentId:2,offeringId:$offeringId}'
)"
write_api 201 POST /enrollments "${parent_jar}" "${planned_enrollment_body}" \
  "${run_root}/planned-enrollment.json"
write_api 200 POST "/offerings/${planned_offering_id}/sessions/generate" \
  "${teacher_jar}" '{}' "${run_root}/planned-sessions.json"
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

write_api 200 POST "/offerings/${planned_offering_id}/sessions/generate" \
  "${teacher_jar}" '{}' "${run_root}/planned-sessions-regenerated.json"
jq -e \
  --arg originalDate "${reschedule_original_date}" \
  'any(.[]; (.sessionDate | tostring | startswith("2098-01-06")))
   and all(.[]; (.sessionDate | tostring | startswith($originalDate) | not))' \
  "${run_root}/planned-sessions-regenerated.json" >/dev/null \
  || fail "session regeneration recreated the original date of an applied reschedule"

leave_body="$(
  jq -nc --argjson sessionId "${planned_session_id}" \
    '{sessionId:$sessionId,studentId:2,reason:"验收请假申请"}'
)"
write_api 200 POST /leave-requests "${parent_jar}" "${leave_body}" \
  "${run_root}/leave-request.json"
leave_request_id="$(jq -r '.id' "${run_root}/leave-request.json")"
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
       == (["id", "offeringId", "rating", "studentId", "submittedAt"] | sort))
' "${run_root}/evaluation-mine-before.json" >/dev/null \
  || fail "guardian own-evaluation projection or scope is incorrect"

evaluation_body="$(
  jq -nc --argjson offeringId "${history_offering_id}" \
    '{
      studentId:1,
      offeringId:$offeringId,
      rating:5,
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
     .studentId == 1 and .offeringId == $offeringId and .rating == 5)
   and all(.[];
     ((keys | sort)
      == (["id", "offeringId", "rating", "studentId", "submittedAt"] | sort)))' \
  "${run_root}/evaluation-mine-after.json" >/dev/null \
  || fail "guardian own-evaluation list did not reflect the new submission"

evaluation_list_status="$(
  status_of "${run_root}/evaluation-list.json" \
    -c "${regulator_jar}" -b "${regulator_jar}" \
    "${base_url}/evaluations"
)"
[[ "${evaluation_list_status}" == "200" ]] || fail "regulator could not list evaluations"
jq -e \
  'length >= 2
   and all(has("studentName") | not)
   and all(has("guardianName") | not)
   and all(has("comment") | not)' \
  "${run_root}/evaluation-list.json" >/dev/null \
  || fail "regulator evaluation projection leaked personal details"

write_api 200 POST /supervision/alerts/scan "${regulator_jar}" \
  '{"schoolId":1,"lowAttendanceThreshold":0.8,"deadlineDays":7}' \
  "${run_root}/supervision-scan.json"
jq -e \
  '.candidateCount >= 1 and .createdCount >= 0
   and .deduplicatedCount >= 1' \
  "${run_root}/supervision-scan.json" >/dev/null \
  || fail "supervision scan or active-alert deduplication failed"
waiting_alerts_status="$(
  status_of "${run_root}/waiting-supervision-alerts.json" \
    -c "${regulator_jar}" -b "${regulator_jar}" \
    "${base_url}/supervision/alerts?schoolId=1&status=WAITING_VERIFY"
)"
[[ "${waiting_alerts_status}" == "200" ]] \
  || fail "regulator could not list waiting-verification alerts"
seeded_alert_id="$(
  jq -r \
    '[.[] | select(.alertType == "LOW_ATTENDANCE")][0].id // empty' \
    "${run_root}/waiting-supervision-alerts.json"
)"
[[ "${seeded_alert_id}" =~ ^[0-9]+$ ]] \
  || fail "seeded waiting-verification alert could not be resolved"
seeded_history_before_status="$(
  status_of "${run_root}/seeded-history-before.json" \
    -c "${regulator_jar}" -b "${regulator_jar}" \
    "${base_url}/supervision/alerts/${seeded_alert_id}/history"
)"
[[ "${seeded_history_before_status}" == "200" ]] \
  || fail "seeded supervision history could not be read"
seeded_history_before_count="$(jq -r 'length' "${run_root}/seeded-history-before.json")"
[[ "${seeded_history_before_count}" =~ ^[0-9]+$ ]] \
  || fail "seeded supervision history count is invalid"
write_api 200 POST "/supervision/alerts/${seeded_alert_id}/transition" \
  "${regulator_jar}" \
  '{"targetStatus":"CLOSED","comment":"验收复核通过，关闭预警"}' \
  "${run_root}/supervision-closed.json"
jq -e '.status == "CLOSED"' "${run_root}/supervision-closed.json" >/dev/null \
  || fail "regulator could not close waiting-verification alert"
supervision_history_status="$(
  status_of "${run_root}/supervision-history.json" \
    -c "${regulator_jar}" -b "${regulator_jar}" \
    "${base_url}/supervision/alerts/${seeded_alert_id}/history"
)"
[[ "${supervision_history_status}" == "200" ]] || fail "supervision history could not be read"
jq -e --argjson previousCount "${seeded_history_before_count}" \
  'length == ($previousCount + 1)
   and .[-1].actionType == "VERIFY_CLOSE"
   and .[-1].actorRole == "REGULATOR"' \
  "${run_root}/supervision-history.json" >/dev/null \
  || fail "supervision action history is incomplete"

audit_status="$(
  status_of "${run_root}/audit-logs.json" \
    -c "${regulator_jar}" -b "${regulator_jar}" \
    "${base_url}/audit-logs?schoolId=1"
)"
[[ "${audit_status}" == "200" ]] || fail "regulator could not read operation audit"
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
     .actorRole == "REGULATOR"
     and .httpMethod == "POST"
     and .requestPath == "/api/supervision/alerts/scan"
     and .responseStatus == 200)
   and any(.[];
     .actorRole == "SCHOOL_ADMIN"
     and .httpMethod == "POST"
     and (.requestPath | startswith("/api/supervision/alerts/"))
     and (.requestPath | endswith("/transition"))
     and .responseStatus == 200)
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
    -c "${regulator_jar}" -b "${regulator_jar}" \
    "${base_url}/reports/course-performance?schoolId=1"
)"
[[ "${performance_status}" == "200" ]] || fail "course performance report failed"
jq -e 'length > 0 and all(has("courseName"))' \
  "${run_root}/course-performance.json" >/dev/null \
  || fail "course performance report returned no usable rows"
performance_csv_status="$(
  status_of "${run_root}/course-performance.csv" \
    -c "${regulator_jar}" -b "${regulator_jar}" \
    "${base_url}/reports/course-performance.csv?schoolId=1"
)"
[[ "${performance_csv_status}" == "200" ]] || fail "course performance CSV export failed"
grep -q '课程名称' "${run_root}/course-performance.csv" \
  || fail "course performance CSV header is missing"

report_status="$(
  status_of "${run_root}/report.json" \
    -c "${regulator_jar}" -b "${regulator_jar}" "${base_url}/reports/overview"
)"
[[ "${report_status}" == "200" ]] || fail "regulator report failed"
jq -e '
  .schoolCount == 2
  and .studentCount >= 4
  and .teacherCount >= 3
  and .courseCount >= 5
  and .enrollmentCount >= 4
  and (.schools | length == 2)
' "${run_root}/report.json" >/dev/null || fail "regulator report totals are inconsistent"
regulator_write='{"schoolId":1,"className":"越权班级","grade":1,"schoolYear":"2026-2027","status":"ACTIVE"}'
write_api 403 POST /classes "${regulator_jar}" "${regulator_write}" \
  "${run_root}/regulator-write.json"

school_admin_body='{"username":"verify_school_admin","displayName":"验收学校管理员","mobile":"13800000903","password":"VerifySchoolAdmin@2026","enabled":true}'
write_api 201 POST /schools/2/admins "${regulator_jar}" "${school_admin_body}" \
  "${run_root}/created-school-admin.json"
jq -e '.schoolId == 2 and .username == "verify_school_admin"' \
  "${run_root}/created-school-admin.json" >/dev/null \
  || fail "regulator could not provision a school administrator"
provisioned_admin_jar="${run_root}/provisioned-admin.cookies"
login_as verify_school_admin SCHOOL_ADMIN "${provisioned_admin_jar}" \
  "VerifySchoolAdmin@2026"
jq -e '.schoolId == 2' "${run_root}/login-verify_school_admin.json" >/dev/null \
  || fail "provisioned school administrator received the wrong school scope"
provisioned_classes_status="$(
  status_of "${run_root}/provisioned-admin-classes.json" \
    -c "${provisioned_admin_jar}" -b "${provisioned_admin_jar}" \
    "${base_url}/classes"
)"
[[ "${provisioned_classes_status}" == "200" ]] \
  || fail "provisioned school administrator could not list classes"
jq -e 'length > 0 and all(.schoolId == 2)' \
  "${run_root}/provisioned-admin-classes.json" >/dev/null \
  || fail "provisioned school administrator scope leaked another school"

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
old_password_body='{"username":"verify_parent","password":"VerifyParent@2026"}'
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
[[ "${migration_count_after_restart}" == "11" ]] \
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
printf "[verify] Frontend: Vitest + typecheck + production build + audit passed\n"
printf "[verify] Database: MySQL %s default V10 -> demo V4/V8/V11 + guarded re-anchor/restart/closed-term preservation passed\n" "${mysql_version}"
printf "[verify] E2E: auth/RBAC/CRUD/planning/enrollment/leave/teaching/correction/supervision/evaluation/audit/reports passed\n"
printf "[verify] Operations: atomic frontend/backend rollback + encrypted backup/restore + backup-service-aware health transitions passed\n"
printf "[verify] Supply chain: CI + OWASP high-severity gate + SBOM contracts passed\n"
printf "[verify] Observability: liveness/readiness/Prometheus + database-outage readiness rejection passed\n"
