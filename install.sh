#!/usr/bin/env bash
# For a build of your own: builds FireSky, then installs and sets it up with setup.sh. Safe to re-run.
#   ./install.sh              build, install and set up
#   ./install.sh --no-build   install the last build
# The TV's address comes from TV=... or from tv= in local.properties.
set -e
cd "$(dirname "$0")"

prop() { [ -f local.properties ] && sed -n "s/^[[:space:]]*${1//./\\.}[[:space:]]*=[[:space:]]*//p" local.properties | tail -1 | tr -d '\r'; }

TV=${TV:-$(prop tv)}
if [ -z "$TV" ]; then
  echo "Which TV? Add its address to local.properties, for example:  tv=192.168.1.50"
  echo "Find it on the TV under Settings > My Fire TV > About > Network."
  exit 1
fi
SDK=$(prop sdk.dir)
if [ -n "$SDK" ] && [ -z "${ANDROID_HOME:-}" ]; then export ANDROID_HOME=$SDK; fi

if [ -z "${JAVA_HOME:-}" ]; then
  if [ -x /usr/libexec/java_home ] && /usr/libexec/java_home -v 17 >/dev/null 2>&1; then
    export JAVA_HOME=$(/usr/libexec/java_home -v 17)
  elif [ -d /opt/homebrew/opt/openjdk@17 ]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
  fi
fi

if [ "${1:-}" != "--no-build" ]; then
  ./gradlew assembleRelease -q --console=plain
fi

exec ./setup.sh "$TV" --apk app/build/outputs/apk/release/app-release.apk
