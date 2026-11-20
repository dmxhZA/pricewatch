#!/usr/bin/env bash
# The mechanical parts of a PriceWatch release; ../SKILL.md says when to run each one.
#
#   release_apk.sh check  [FOLDER]   is everything a release needs in place? Changes nothing.
#   release_apk.sh test              runs the unit and screen tests
#   release_apk.sh build             builds the release APK at the tagged HEAD and verifies it
#   release_apk.sh upload [FOLDER]   copies that APK into the shared folder
#
# FOLDER defaults to /mnt/from-claude, where the from_claude share is mounted.
set -euo pipefail

DEFAULT_FOLDER=/mnt/from-claude
PACKAGE=app.pricewatch
OUT=app/build/outputs/apk/release
APK=$OUT/app-release.apk
BUILT_FROM=$OUT/built-from-commit

self=$(readlink -f "${BASH_SOURCE[0]}")
cd "$(dirname "$self")"
cd "$(git rev-parse --show-toplevel)"

# This machine's JDK and Android SDK are not on PATH.
export JAVA_HOME="${JAVA_HOME:-$(ls -d "$HOME"/.jdks/jdk-21* 2>/dev/null | sort -V | tail -n 1)}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$JAVA_HOME/bin:$PATH"

fail() {
  echo "error: $*" >&2
  exit 1
}

version_name() { sed -n 's/^ *versionName = "\(.*\)"$/\1/p' app/build.gradle.kts; }
version_code() { sed -n 's/^ *versionCode = \([0-9]*\)$/\1/p' app/build.gradle.kts; }
build_tool() { ls -d "$ANDROID_HOME"/build-tools/*/"$1" 2>/dev/null | sort -V | tail -n 1; }

# Why FOLDER cannot receive the APK; prints nothing if it can.
folder_problem() {
  if [[ ! -d $1 ]]; then
    echo "the shared folder $1 does not exist"
  elif ! mountpoint -q "$1"; then
    echo "nothing is mounted at $1: it is a plain folder on this disk, so a copy would never reach the share"
  elif [[ ! -w $1 ]]; then
    echo "no write access to the shared folder $1"
  fi
}

# SHA-256 of the certificate of the key named in signing/keystore.properties, in lowercase hex.
# The password goes to keytool through a pipe and is never printed.
key_sha256() {
  local props=signing/keystore.properties store alias
  store=$(sed -n 's/^storeFile=//p' "$props")
  alias=$(sed -n 's/^keyAlias=//p' "$props")
  [[ $store == /* ]] || store=signing/$store
  keytool -list -v -keystore "$store" -alias "$alias" \
    -storepass:file <(sed -n 's/^storePassword=//p' "$props") 2>/dev/null |
    sed -n 's/^[[:space:]]*SHA256: //p' | tr -d ':' | tr 'A-F' 'a-f'
}

# The tag on HEAD, which must name the version in the build file.
release_tag() {
  local tag
  tag=$(git describe --exact-match --tags HEAD 2>/dev/null) || fail "HEAD has no tag; tag the release commit first"
  [[ $tag == "v$(version_name)" ]] || fail "tag $tag does not match versionName $(version_name) in app/build.gradle.kts"
  echo "$tag"
}

# The built APK is this app at the build file's version, signed with the local key.
verify() {
  local badging cert key
  badging=$("$(build_tool aapt2)" dump badging "$APK")
  badging=${badging%%$'\n'*}
  [[ $badging == *"name='$PACKAGE'"* && $badging == *"versionCode='$(version_code)'"* &&
    $badging == *"versionName='$(version_name)'"* ]] ||
    fail "the APK says \"$badging\"; expected $PACKAGE $(version_name) ($(version_code))"
  cert=$("$(build_tool apksigner)" verify --print-certs "$APK" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
  key=$(key_sha256)
  [[ -n $key && $cert == "$key" ]] || fail "the APK is signed with certificate ${cert:-(none)}, not your key ${key:-(unreadable)}"
  echo "verified $APK: $PACKAGE $(version_name) ($(version_code)), signing certificate SHA-256 $cert"
}

check() {
  local folder=$1 msg
  local problems=()
  git fetch -q origin
  [[ $(git branch --show-current) == main ]] || problems+=("not on main")
  [[ -z $(git status --porcelain) ]] || problems+=("the working tree has uncommitted or untracked changes")
  [[ $(git rev-parse HEAD) == "$(git rev-parse origin/main)" ]] || problems+=("main differs from origin/main; pull or push first")
  [[ -f signing/keystore.properties ]] ||
    problems+=("no signing key: restore signing/personal.jks and signing/keystore.properties from the backup (see signing/README.md)")
  [[ -x $JAVA_HOME/bin/java ]] || problems+=("no JDK found (JAVA_HOME=$JAVA_HOME)")
  [[ -n $(build_tool aapt2) && -n $(build_tool apksigner) ]] || problems+=("aapt2 or apksigner missing under $ANDROID_HOME/build-tools")
  gh auth status >/dev/null 2>&1 || problems+=("gh is not logged in to GitHub")
  msg=$(folder_problem "$folder")
  [[ -z $msg ]] || problems+=("$msg")
  if ((${#problems[@]})); then
    echo "not ready to release:"
    printf '  - %s\n' "${problems[@]}"
    exit 1
  fi
  echo "ready: main at $(git rev-parse --short HEAD), version $(version_name) ($(version_code)), shared folder $folder"
}

build() {
  local tag
  tag=$(release_tag)
  [[ -z $(git status --porcelain) ]] || fail "uncommitted or untracked changes; the APK must contain exactly $tag"
  [[ -f signing/keystore.properties ]] || fail "no signing key; see signing/README.md"
  rm -rf "$OUT"
  echo "building $tag..."
  ./gradlew assembleRelease --console=plain -q
  [[ -f $APK ]] || fail "the build produced no $APK"
  verify
  git rev-parse HEAD >"$BUILT_FROM"
}

upload() {
  local folder=$1 tag name target sha msg
  tag=$(release_tag)
  [[ -f $APK && -f $BUILT_FROM && $(<"$BUILT_FROM") == "$(git rev-parse HEAD)" ]] || fail "no APK built from $tag; run build first"
  verify
  msg=$(folder_problem "$folder")
  [[ -z $msg ]] || fail "$msg"
  name=pricewatch-$(version_name).apk
  target=$folder/$name
  [[ ! -e $target ]] || fail "$target already exists; not replacing it"
  # Copy under a temporary name, so the share never shows a half-written APK.
  cp "$APK" "$target.part"
  mv "$target.part" "$target"
  sha=$(sha256sum "$APK" | cut -d ' ' -f 1)
  [[ $(sha256sum "$target" | cut -d ' ' -f 1) == "$sha" ]] || fail "$target differs from the built APK"
  echo "$sha  $name" >"$target.sha256"
  echo "uploaded $target"
  echo "sha256 $sha"
}

case "${1:-}" in
  check) check "${2:-$DEFAULT_FOLDER}" ;;
  test) ./gradlew testDebugUnitTest --console=plain -q ;;
  build) build ;;
  upload) upload "${2:-$DEFAULT_FOLDER}" ;;
  *)
    sed -n '2,9p' "$self" | sed 's/^# \{0,1\}//' >&2
    exit 2
    ;;
esac
