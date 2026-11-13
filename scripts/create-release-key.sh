#!/usr/bin/env bash
# One-time: creates the Android release signing key and hands it to the Release workflow.
#
# - Keystore: ~/.android/mealplanner-release.jks (never in the repo). Back it up: Android only
#   installs an update over the app when it is signed with this same key.
# - ~/.gradle/gradle.properties gets the mealplanner.* signing properties, so local
#   `./gradlew :androidApp:assembleRelease` builds are signed the same way.
# - GitHub secrets ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS (via gh).
set -euo pipefail

keystore="$HOME/.android/mealplanner-release.jks"
alias=mealplanner
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"

if [ -e "$keystore" ]; then
    echo "$keystore already exists; not replacing a signing key." >&2
    exit 1
fi

keytool="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
password="$(openssl rand -hex 24)"
mkdir -p "$(dirname "$keystore")"
"$keytool" -genkeypair -storetype PKCS12 -keystore "$keystore" -alias "$alias" \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -storepass "$password" -keypass "$password" -dname "CN=Mealplanner" >/dev/null
chmod 600 "$keystore"

props="$HOME/.gradle/gradle.properties"
mkdir -p "$(dirname "$props")"
touch "$props"
if ! grep -q '^mealplanner.keystore=' "$props"; then
    cat >> "$props" <<EOF

# Mealplanner release signing (keystore must be backed up)
mealplanner.keystore=$keystore
mealplanner.keystorePassword=$password
mealplanner.keyAlias=$alias
mealplanner.keyPassword=$password
EOF
    chmod 600 "$props"
fi

cd "$repo_dir"
base64 -w0 "$keystore" | gh secret set ANDROID_KEYSTORE_BASE64
printf %s "$password" | gh secret set ANDROID_KEYSTORE_PASSWORD
printf %s "$alias" | gh secret set ANDROID_KEY_ALIAS

echo "Created $keystore and set the GitHub secrets. Back up the keystore and ~/.gradle/gradle.properties."
