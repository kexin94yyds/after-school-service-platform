#!/usr/bin/env bash

set -Eeuo pipefail

usage() {
  cat >&2 <<'EOF'
Usage: install-web-release.sh <dist-dir> <release-id> [web-root] [retention-days]

The Vite build must use --base=/releases/<release-id>/ so every lazy-loaded
asset keeps a versioned URL while the current SPA release is switched atomically.
EOF
  exit 2
}

fail() {
  printf 'install-web-release: %s\n' "$1" >&2
  exit 1
}

[[ "$#" -ge 2 && "$#" -le 4 ]] || usage

dist_dir="$(cd -- "$1" 2>/dev/null && pwd -P)" \
  || fail "dist directory does not exist: $1"
release_id="$2"
web_root="${3:-/var/www/after-school-service}"
retention_days="${4:-7}"

[[ "${release_id}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$ ]] \
  || fail "release id may contain only letters, digits, dot, underscore and dash"
[[ "${web_root}" == /* && "${web_root}" != "/" ]] \
  || fail "web root must be a specific absolute directory"
[[ "${retention_days}" =~ ^[0-9]+$ ]] \
  || fail "retention days must be a non-negative integer"
[[ -s "${dist_dir}/index.html" && -d "${dist_dir}/assets" ]] \
  || fail "dist must contain index.html and assets/"
[[ -z "$(find "${dist_dir}" -type l -print -quit)" ]] \
  || fail "dist must not contain symbolic links"
grep -Fq "/releases/${release_id}/assets/" "${dist_dir}/index.html" \
  || fail "dist was not built with --base=/releases/${release_id}/"

umask 022
releases_root="${web_root}/releases"
release_dir="${releases_root}/${release_id}"
staging_dir="${releases_root}/.${release_id}.staging.$$"
next_link="${web_root}/.current.${release_id}.$$"
published=false
release_created=false

cleanup() {
  if [[ "${published}" != "true" && -d "${staging_dir}" ]]; then
    rm -rf -- "${staging_dir}"
  fi
  if [[ "${published}" != "true" && "${release_created}" == "true" \
      && -d "${release_dir}" ]]; then
    rm -rf -- "${release_dir}"
  fi
  if [[ -L "${next_link}" ]]; then
    rm -f -- "${next_link}"
  fi
}
trap cleanup EXIT

install -d -m 0755 "${web_root}" "${releases_root}"
[[ ! -e "${release_dir}" && ! -L "${release_dir}" ]] \
  || fail "release already exists: ${release_id}"
[[ ! -e "${staging_dir}" && ! -L "${staging_dir}" ]] \
  || fail "staging path already exists"

install -d -m 0755 "${staging_dir}"
rsync -a --delete -- "${dist_dir}/" "${staging_dir}/"
[[ -s "${staging_dir}/index.html" ]] || fail "staged index.html is empty"

mv -- "${staging_dir}" "${release_dir}"
release_created=true
ln -s "releases/${release_id}" "${next_link}"

# GNU mv uses -T; BSD mv uses -h. Both replace the symlink itself with one
# same-filesystem rename instead of following the old current/ directory.
if mv --help 2>&1 | grep -q -- '--no-target-directory'; then
  mv -Tf -- "${next_link}" "${web_root}/current"
elif [[ "$(uname -s)" == "Darwin" ]]; then
  mv -hf -- "${next_link}" "${web_root}/current"
else
  fail "atomic symlink replacement requires GNU mv -T or BSD mv -h"
fi
published=true

# Keep old versioned URLs available for existing tabs during the grace period.
# Every deletion target is a validated direct child of releases/ and never the
# newly published release.
while IFS= read -r -d '' candidate; do
  candidate_name="${candidate##*/}"
  [[ "${candidate_name}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$ ]] || continue
  [[ "${candidate}" != "${release_dir}" ]] || continue
  if [[ -n "$(find "${candidate}" -maxdepth 0 -type d -mtime "+${retention_days}" -print -quit)" ]]; then
    rm -rf -- "${candidate}"
  fi
done < <(find "${releases_root}" -mindepth 1 -maxdepth 1 -type d -print0)

printf 'Published web release %s; retained older releases for %s days.\n' \
  "${release_id}" "${retention_days}"
