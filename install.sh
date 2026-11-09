#!/usr/bin/env bash
# Build FireSky, install it on the TV, and grant what it needs. Safe to re-run.
#   ./install.sh              build, install and grant
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
[[ $TV == *:* ]] || TV=$TV:5555
PKG=com.firesky.launcher

if ! command -v adb >/dev/null; then
  for d in "$ANDROID_HOME" "$ANDROID_SDK_ROOT" "$(prop sdk.dir)" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    [ -n "$d" ] && [ -x "$d/platform-tools/adb" ] && { PATH="$d/platform-tools:$PATH"; break; }
  done
fi
command -v adb >/dev/null || { echo "adb not found. Install Android platform-tools and try again."; exit 1; }

if [ -z "$JAVA_HOME" ]; then
  if [ -x /usr/libexec/java_home ] && /usr/libexec/java_home -v 17 >/dev/null 2>&1; then
    export JAVA_HOME=$(/usr/libexec/java_home -v 17)
  elif [ -d /opt/homebrew/opt/openjdk@17 ]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
  fi
fi

if [ "$1" != "--no-build" ]; then
  ./gradlew assembleRelease -q --console=plain
fi

adb connect "$TV" >/dev/null
if ! adb -s "$TV" get-state >/dev/null 2>&1; then
  echo "Can't reach the TV at $TV. Check ADB debugging is on, then accept the prompt on the TV and run this again."
  exit 1
fi
on() { adb -s "$TV" shell "$@"; }
adb -s "$TV" install -r app/build/outputs/apk/release/app-release.apk

# Which apps you open and when: "Usually now" and "Last used 2 h ago".
on appops set $PKG GET_USAGE_STATS allow
# Kodi's watch history: the Continue card.
on pm grant $PKG android.permission.READ_EXTERNAL_STORAGE
# The earliest sign of a Home press, and Netflix's Continue Watching list.
on pm grant $PKG android.permission.READ_LOGS
# What is paused, and where: the Resume card.
LISTENER=$PKG/$PKG.MediaListener
on cmd notification allow_listener $LISTENER

# Home handling: an accessibility service. Any older entry for this app is replaced.
HOMEKEY=$PKG/.HomeKey
has_homekey() { local v; v=$(on settings get secure enabled_accessibility_services | tr -d '\r'); [[ $v == *"$HOMEKEY"* || $v == *"$PKG/$PKG.HomeKey"* ]]; }
a11y=$(on settings get secure enabled_accessibility_services | tr -d '\r')
[ "$a11y" = null ] && a11y=
a11y=$(printf '%s' "$a11y" | tr ':' '\n' | grep -v "^$PKG/" | paste -sd: - || true)
a11y=${a11y:+$a11y:}$HOMEKEY

# Compile all of it now: a fresh install is left largely uncompiled, and the first Home press after
# one can take over 6 seconds. Without this it only runs slower, so a failure here doesn't stop the install.
on cmd package compile -m speed -f $PKG || true
# The running app keeps its old code until it restarts. A force stop also drops Home handling and the
# media listener, so both go straight back.
on am force-stop $PKG
# Android can drop the service from the list as the app stops, sometimes seconds after it's been put
# back, so it has to still be there on two checks in a row.
on settings put secure enabled_accessibility_services "$a11y"
held=0
for i in 1 2 3 4 5 6 7 8 9 10; do
  sleep 3
  if has_homekey; then
    held=$((held + 1)); [ $held -ge 2 ] && break
  else
    held=0
    on settings put secure enabled_accessibility_services "$a11y"
  fi
done
[ $held -ge 2 ] || echo "Home handling didn't stay on: turn on FireSky under Settings > Accessibility on the TV."
on cmd notification disallow_listener $LISTENER
on cmd notification allow_listener $LISTENER

# Installing takes the home screen away. If it was on screen, Amazon's home is there now, and the
# next Home press would wait 5 seconds for a fresh start, so it opens again here instead.
if on dumpsys window | grep mCurrentFocus | grep -q com.amazon.tv.launcher; then
  on am start -n $PKG/.MainActivity >/dev/null
fi

echo "Installed. Press Home on the remote to see it."
