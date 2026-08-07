#!/usr/bin/env bash

set -Eeuo pipefail

usage() {
  cat >&2 <<'EOF'
Usage: select-web-release.sh <release-id> [web-root]

Atomically selects an already installed immutable frontend release.
EOF
  exit 2
}

fail() {
  printf 'select-web-release: %s\n' "$1" >&2
  exit 1
}

[[ "$#" -ge 1 && "$#" -le 2 ]] || usage
release_id="$1"
web_root="${2:-/var/www/after-school-service}"

[[ "${release_id}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$ ]] \
  || fail "release id may contain only letters, digits, dot, underscore and dash"
[[ "${web_root}" == /* && "${web_root}" != "/" ]] \
  || fail "web root must be a specific absolute directory"

release_dir="${web_root}/releases/${release_id}"
[[ -s "${release_dir}/index.html" && -d "${release_dir}/assets" ]] \
  || fail "frontend release is incomplete: ${release_id}"
[[ -z "$(find "${release_dir}" -type l -print -quit)" ]] \
  || fail "frontend release must not contain symbolic links"
grep -Fq "/releases/${release_id}/assets/" "${release_dir}/index.html" \
  || fail "frontend release index uses a different versioned base"

current_link="${web_root}/current"
next_link="${web_root}/.current.${release_id}.$$"
cleanup() {
  [[ ! -L "${next_link}" ]] || unlink "${next_link}"
}
trap cleanup EXIT

if [[ -L "${current_link}" \
    && "$(readlink "${current_link}")" == "releases/${release_id}" ]]; then
  printf 'Frontend release %s is already selected.\n' "${release_id}"
  exit 0
fi
[[ ! -e "${current_link}" || -L "${current_link}" ]] \
  || fail "current exists but is not a symbolic link"

ln -s "releases/${release_id}" "${next_link}"
if mv --help 2>&1 | grep -q -- '--no-target-directory'; then
  mv -Tf -- "${next_link}" "${current_link}"
elif [[ "$(uname -s)" == "Darwin" ]]; then
  mv -hf -- "${next_link}" "${current_link}"
else
  fail "atomic symlink replacement requires GNU mv -T or BSD mv -h"
fi

printf 'Selected frontend release %s.\n' "${release_id}"
