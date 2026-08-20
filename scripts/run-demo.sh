#!/usr/bin/env bash

set -Eeuo pipefail

usage() {
  cat >&2 <<'EOF'
Usage: run-demo.sh [after-school-service-server-...-demo.jar]

Launch only the separately packaged demo artifact against the dedicated local
after_school_demo database. Set DEMO_DB_PASSWORD and optionally
DEMO_DB_USERNAME / DEMO_MYSQL_PORT / DEMO_MANAGEMENT_PORT before invoking this script.
EOF
  exit 2
}

fail() {
  printf 'run-demo: %s\n' "$1" >&2
  exit 1
}

[[ "$#" -le 1 ]] || usage
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd -- "${script_dir}/.." && pwd)"
demo_jar="${1:-${project_root}/after-school-service-server/target/after-school-service-server-0.0.1-SNAPSHOT-demo.jar}"
java_bin="${JAVA_BIN:-java}"

[[ -f "${demo_jar}" && ! -L "${demo_jar}" ]] \
  || fail "demo JAR must be a regular file: ${demo_jar}"
[[ -n "${DEMO_DB_PASSWORD:-}" ]] \
  || fail "DEMO_DB_PASSWORD must be set for the dedicated demo database"
demo_username="${DEMO_DB_USERNAME:-after_school_demo}"
demo_port="${DEMO_MYSQL_PORT:-3306}"
demo_management_port="${DEMO_MANAGEMENT_PORT:-8082}"
[[ "${demo_username}" =~ ^[A-Za-z0-9_]{1,32}$ ]] \
  || fail "DEMO_DB_USERNAME must contain only letters, digits and underscore"
[[ "${demo_port}" =~ ^[0-9]{1,5}$ && "${demo_port}" -ge 1 \
    && "${demo_port}" -le 65535 ]] \
  || fail "DEMO_MYSQL_PORT must be between 1 and 65535"
[[ "${demo_management_port}" =~ ^[0-9]{1,5}$ \
    && "${demo_management_port}" -ge 1 \
    && "${demo_management_port}" -le 65535 ]] \
  || fail "DEMO_MANAGEMENT_PORT must be between 1 and 65535"
[[ "${demo_management_port}" != "8081" ]] \
  || fail "DEMO_MANAGEMENT_PORT must differ from the application port 8081"
command -v "${java_bin}" >/dev/null 2>&1 \
  || fail "Java command not found: ${java_bin}"

if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/jar" ]]; then
  jar_bin="${JAVA_HOME}/bin/jar"
else
  jar_bin="jar"
fi
command -v "${jar_bin}" >/dev/null 2>&1 \
  || fail "JAR command not found: ${jar_bin}"
for required_entry in \
  'BOOT-INF/classes/application-demo.yml' \
  'BOOT-INF/classes/com/afterschool/platform/demo/DemoTimelineRefresher.class' \
  'BOOT-INF/classes/db/demo/V4__seed_demo_workflow.sql' \
  'BOOT-INF/classes/db/demo/V4_1__seed_demo_scan_run_parent.sql' \
  'BOOT-INF/classes/db/demo/V8__seed_comprehensive_graduation_workflow.sql' \
  'BOOT-INF/classes/db/demo/V11__simplify_demo_login_credentials.sql' \
  'BOOT-INF/classes/db/demo/V15__align_demo_with_opening_report.sql'; do
  "${jar_bin}" tf "${demo_jar}" | grep -Fxq "${required_entry}" \
    || fail "JAR is not the separately packaged demo artifact: ${required_entry} is absent"
done

# env -i deliberately drops DB_URL, SPRING_DATASOURCE_* and all external Spring
# property overrides. The complete demo payload is absent from the production
# JAR; this dedicated artifact is pinned by application-demo.yml to
# 127.0.0.1/after_school_demo and cannot accidentally inherit a production URL.
exec env -i \
  PATH="${PATH}" \
  SPRING_PROFILES_ACTIVE=demo \
  SERVER_ADDRESS=127.0.0.1 \
  MANAGEMENT_SERVER_ADDRESS=127.0.0.1 \
  MANAGEMENT_SERVER_PORT="${demo_management_port}" \
  DEMO_MYSQL_PORT="${demo_port}" \
  DEMO_DB_USERNAME="${demo_username}" \
  DEMO_DB_PASSWORD="${DEMO_DB_PASSWORD}" \
  "${java_bin}" -jar "${demo_jar}"
