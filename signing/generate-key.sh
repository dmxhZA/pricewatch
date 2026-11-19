#!/usr/bin/env bash
# Creates this machine's PriceWatch signing key: signing/personal.jks, and signing/keystore.properties
# with its random password. Git ignores both. See README.md in this folder.
set -euo pipefail
cd "$(dirname "$0")"

if [[ -e personal.jks || -e keystore.properties ]]; then
  echo "A signing key already exists in $PWD; not replacing it." >&2
  echo "Builds signed with a new key cannot update an app installed with the current one." >&2
  exit 1
fi

keytool="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
if ! command -v "$keytool" >/dev/null; then
  echo "keytool not found. Set JAVA_HOME to a JDK or put keytool on PATH." >&2
  exit 1
fi

# Only this user may read the key and its password.
umask 077
# A PKCS12 key store uses one password for the store and the key in it.
PRICEWATCH_KEY_PASSWORD="$(od -An -vtx1 -N24 /dev/urandom | tr -d ' \n')"
export PRICEWATCH_KEY_PASSWORD
"$keytool" -genkeypair -keystore personal.jks -storetype PKCS12 -storepass:env PRICEWATCH_KEY_PASSWORD \
  -alias pricewatch -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=PriceWatch"

cat > keystore.properties <<EOF
# Where the PriceWatch signing key is and its password. Never commit this file or the key.
# Back up both together; see README.md in this folder.
storeFile=personal.jks
storePassword=$PRICEWATCH_KEY_PASSWORD
keyAlias=pricewatch
keyPassword=$PRICEWATCH_KEY_PASSWORD
EOF
echo "Created $PWD/personal.jks and $PWD/keystore.properties. Back up both together."
