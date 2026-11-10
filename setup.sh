#!/usr/bin/env bash
# Puts FireSky on a Fire TV and switches on everything it needs. Safe to run again.
#
#   curl -fsSL https://raw.githubusercontent.com/jeffersonaaron25/firesky-launcher/main/setup.sh | bash
#
# It asks for the TV's address. You can also pass it, and an APK of your own instead of the latest release:
#   bash setup.sh 192.168.1.50
#   bash setup.sh 192.168.1.50 --apk app/build/outputs/apk/release/app-release.apk
# --replace removes a FireSky that can't be updated in place (signed with another key) without asking.
#
# Needs a Mac or Linux computer on the same network as the TV, with ADB debugging on in the TV's
# Developer options. Android's adb tool is downloaded from Google into ~/.firesky if it isn't here.
set -euo pipefail

REPO=jeffersonaaron25/firesky-launcher
PKG=com.firesky.launcher
DIR=${FIRESKY_DIR:-$HOME/.firesky}
TV=; APK=; REPLACE=
while [ $# -gt 0 ]; do
  case $1 in
    --apk) APK=$2; shift 2 ;;
    --tv) TV=$2; shift 2 ;;
    --replace) REPLACE=1; shift ;;
    -h|--help) sed -n '2,11p' "$0"; exit 0 ;;
    *) TV=$1; shift ;;
  esac
done

say() { printf '%s\n' "$*"; }
# Questions go to the terminal itself, so this works when piped from curl.
ask() { local a=; printf '%s' "$1" >/dev/tty; IFS= read -r a </dev/tty || true; printf '%s' "$a"; }
mkdir -p "$DIR"

# 1. adb: one already on this computer, or Google's own, fetched once.
ADB=$(command -v adb || true)
for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" "$DIR"; do
  if [ -z "$ADB" ] && [ -n "$d" ] && [ -x "$d/platform-tools/adb" ]; then ADB=$d/platform-tools/adb; fi
done
if [ -z "$ADB" ]; then
  case $(uname -s) in
    Darwin) os=darwin ;;
    Linux) os=linux ;;
    *) say "This script is for a Mac or Linux."; exit 1 ;;
  esac
  say "Getting Android's adb tool from Google..."
  curl -fsSL -o "$DIR/platform-tools.zip" "https://dl.google.com/android/repository/platform-tools-latest-$os.zip" \
    || { say "Couldn't download adb from Google. Is this computer online?"; exit 1; }
  unzip -qo "$DIR/platform-tools.zip" -d "$DIR"
  rm -f "$DIR/platform-tools.zip"
  ADB=$DIR/platform-tools/adb
fi

# 2. FireSky: the latest release, unless you brought your own.
if [ -z "$APK" ]; then
  say "Downloading the latest FireSky..."
  APK=$DIR/FireSky.apk
  curl -fsSL -o "$APK" "https://github.com/$REPO/releases/latest/download/FireSky.apk" \
    || { say "Couldn't download FireSky from https://github.com/$REPO/releases. Is this computer online?"; exit 1; }
fi
[ -f "$APK" ] || { say "There's no APK at $APK."; exit 1; }

# 3. The TV.
if [ -z "$TV" ]; then
  say ""
  say "On the TV, turn on ADB debugging: Settings > My Fire TV > Developer options > ADB debugging."
  say "No Developer options? Go to Settings > My Fire TV > About and select the TV's name 7 times."
  say "The TV's address is under Settings > My Fire TV > About > Network."
  TV=$(ask "The TV's IP address: ")
fi
TV=${TV// /}
[ -n "$TV" ] || { say "No address given."; exit 1; }
[[ $TV == *:* ]] || TV=$TV:5555

a() { "$ADB" -s "$TV" "$@"; }
on() { a shell "$@"; }

"$ADB" connect "$TV" >/dev/null 2>&1 || true
state=; told=
for _ in $(seq 1 45); do
  state=$(a get-state 2>&1 || true)
  case $state in
    device) break ;;
    *unauthorized*) if [ -z "$told" ]; then say "Look at the TV: choose \"Always allow from this computer\", then OK."; told=1; fi ;;
    *) "$ADB" connect "$TV" >/dev/null 2>&1 || true ;;
  esac
  sleep 2
done
if [ "$state" != device ]; then
  say "Can't reach the TV at $TV. Check the address, that ADB debugging is on, and that this computer is on the same network."
  exit 1
fi

# 4. Install. A copy signed with a different key (an older build of your own) has to go first.
say "Installing FireSky..."
if ! out=$(a install -r "$APK" 2>&1); then
  if [[ $out == *UPDATE_INCOMPATIBLE* || $out == *"signatures do not match"* ]]; then
    if [ -z "$REPLACE" ]; then
      yn=$(ask "The FireSky on the TV can't be updated in place. Remove it and install this one? What it has learned starts over. [y/N] ")
      [[ $yn == [yY]* ]] || exit 1
    fi
    a uninstall "$PKG" >/dev/null
    out=$(a install "$APK" 2>&1) || { say "$out"; exit 1; }
  else
    say "$out"; exit 1
  fi
fi

# 5. What Fire TV only grants over ADB.
say "Switching on what FireSky needs..."
# Which apps you open and when: "Usually now" and "Last used 2 h ago".
on appops set $PKG GET_USAGE_STATS allow
# Kodi's watch history: the Continue card.
on pm grant $PKG android.permission.READ_EXTERNAL_STORAGE
# The earliest sign of a Home press, and Netflix's Continue Watching list.
on pm grant $PKG android.permission.READ_LOGS
# Installing new releases of itself, after you say yes on screen.
on appops set $PKG REQUEST_INSTALL_PACKAGES allow
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
# one can take over 6 seconds. Without this it only runs slower, so a failure here doesn't stop setup.
say "Getting it ready to be quick (about a minute)..."
on cmd package compile -m speed -f $PKG >/dev/null 2>&1 || true
# The running app keeps its old code until it restarts. A force stop also drops Home handling and the
# media listener, so both go straight back.
on am force-stop $PKG
# Android can drop the service from the list as the app stops, sometimes seconds after it's been put
# back, so it has to still be there on two checks in a row.
on settings put secure enabled_accessibility_services "$a11y"
held=0
for _ in 1 2 3 4 5 6 7 8 9 10; do
  sleep 3
  if has_homekey; then
    held=$((held + 1)); [ $held -ge 2 ] && break
  else
    held=0
    on settings put secure enabled_accessibility_services "$a11y"
  fi
done
on cmd notification disallow_listener $LISTENER
on cmd notification allow_listener $LISTENER

# Installing takes the home screen away. If Amazon's home is showing now, FireSky opens over it, so
# the next Home press doesn't wait for a fresh start.
if on dumpsys window | grep mCurrentFocus | grep -q com.amazon.tv.launcher; then
  on am start -n $PKG/.MainActivity >/dev/null
fi

if [ $held -ge 2 ]; then
  say "Done. Press Home on the remote. FireSky's settings are in the Settings tile's options: hold OK on it."
else
  say "FireSky is installed, but Home handling didn't stay on. Run this again, or ask for help on GitHub:"
  say "https://github.com/$REPO/issues"
  exit 1
fi
