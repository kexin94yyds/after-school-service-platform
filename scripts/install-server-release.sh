#!/usr/bin/env bash

set -Eeuo pipefail

usage() {
  cat >&2 <<'EOF'
Usage: install-server-release.sh <jar-file> <release-id> [server-root] [retention-days] [service-name] [health-url]

Installs one immutable backend release, atomically selects it through
select-server-release.sh, and retains the previous release for rollback.
EOF
  exit 2
}

fail() {
  printf 'install-server-release: %s\n' "$1" >&2
  exit 1
}

[[ "$#" -ge 2 && "$#" -le 6 ]] || usage
[[ -f "$1" && ! -L "$1" ]] || fail "JAR must be a regular non-symlink file"

jar_dir="$(cd -- "$(dirname -- "$1")" 2>/dev/null && pwd -P)" \
  || fail "JAR parent directory does not exist"
jar_file="${jar_dir}/$(basename -- "$1")"
release_id="$2"
server_root="${3:-/opt/after-school-service}"
retention_days="${4:-30}"
service_name="${5:-after-school-service.service}"
health_url="${6:-http://127.0.0.1:8081/readyz}"

[[ "${release_id}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$ ]] \
  || fail "release id may contain only letters, digits, dot, underscore and dash"
[[ "${server_root}" == /* && "${server_root}" != "/" ]] \
  || fail "server root must be a specific absolute directory"
[[ "${retention_days}" =~ ^[0-9]+$ && "${retention_days}" -le 3650 ]] \
  || fail "retention days must be between 0 and 3650"
command -v jar >/dev/null 2>&1 || fail "JDK jar command not found"
# The short form works with both Java 21 and the older Apple-provided jar used
# by some deployment workstations.
jar tf "${jar_file}" >/dev/null \
  || fail "JAR archive validation failed"
if [[ "$(basename -- "${jar_file}")" == *-demo.jar ]] \
    || jar tf "${jar_file}" | grep -Eq \
      '^BOOT-INF/classes/(db/demo/|application-demo\.yml$|com/afterschool/platform/demo/DemoTimelineRefresher\.class$)'; then
  fail "production release JAR must not package demo payload"
fi

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
selector="${script_dir}/select-server-release.sh"
[[ -x "${selector}" ]] || fail "backend release selector is not executable"

umask 022
releases_root="${server_root}/releases"
release_dir="${releases_root}/${release_id}"
staging_dir="${releases_root}/.${release_id}.staging.$$"
release_created=false
selected=false

cleanup() {
  if [[ "${selected}" != "true" && -d "${staging_dir}" ]]; then
    rm -rf -- "${staging_dir}"
  fi
  if [[ "${selected}" != "true" && "${release_created}" == "true" \
      && -d "${release_dir}" ]]; then
    rm -rf -- "${release_dir}"
  fi
}
trap cleanup EXIT

install -d -m 0755 "${server_root}" "${releases_root}"
[[ ! -e "${release_dir}" && ! -L "${release_dir}" ]] \
  || fail "release already exists: ${release_id}"
[[ ! -e "${staging_dir}" && ! -L "${staging_dir}" ]] \
  || fail "staging path already exists"

previous_target=""
if [[ -L "${server_root}/current" ]]; then
  previous_target="$(readlink "${server_root}/current")"
  [[ "${previous_target}" =~ ^releases/[A-Za-z0-9][A-Za-z0-9._-]{0,79}$ ]] \
    || fail "current must point to a valid direct release directory"
elif [[ -e "${server_root}/current" ]]; then
  fail "current exists but is not a symbolic link"
fi

install -d -m 0755 "${staging_dir}"
install -m 0444 "${jar_file}" "${staging_dir}/app.jar"
if command -v sha256sum >/dev/null 2>&1; then
  (cd "${staging_dir}" && sha256sum app.jar >app.jar.sha256)
elif command -v shasum >/dev/null 2>&1; then
  (cd "${staging_dir}" && shasum -a 256 app.jar >app.jar.sha256)
else
  fail "sha256sum or shasum is required"
fi
chmod 0444 "${staging_dir}/app.jar.sha256"

mv -- "${staging_dir}" "${release_dir}"
release_created=true
"${selector}" "${release_id}" "${server_root}" "${service_name}" "${health_url}"
selected=true

current_target="$(readlink "${server_root}/current")"
while IFS= read -r -d '' candidate; do
  candidate_name="${candidate##*/}"
  candidate_target="releases/${candidate_name}"
  [[ "${candidate_name}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$ ]] || continue
  [[ "${candidate_target}" != "${current_target}" ]] || continue
  [[ -z "${previous_target}" || "${candidate_target}" != "${previous_target}" ]] || continue
  if [[ -n "$(find "${candidate}" -maxdepth 0 -type d \
      -mtime "+${retention_days}" -print -quit)" ]]; then
    rm -rf -- "${candidate}"
  fi
done < <(find "${releases_root}" -mindepth 1 -maxdepth 1 -type d -print0)

printf 'Installed backend release %s; retained current, previous and recent releases.\n' \
  "${release_id}"
