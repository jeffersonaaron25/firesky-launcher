---
name: setup-firesky
description: Install and configure FireSky, a custom home screen for Amazon Fire TV, from a computer on the same network. Use when someone asks to set up, install, update, configure or remove FireSky on their Fire TV. Walks them through turning on ADB, asks their preferences, helps them get a TMDB key, builds the app and installs it.
---

# Set up FireSky on a Fire TV

FireSky is an Android app that replaces the Fire TV home screen. It is built from source on the
user's computer and installed over the network with ADB. You'll do the computer side; the user
does the few things that need the remote. Ask, don't assume: this is their TV and their living room.

Repository: https://github.com/jeffersonaaron25/firesky-launcher

## Ground rules

- **Talk to the user at each step.** Say what you're about to do and why, one step at a time, and
  wait for them when the TV needs the remote.
- **The TMDB key is a secret.** Never print it back, never put it in a command line or a URL you
  show, never commit it. It goes only in `local.properties`, which git ignores.
- **Ask before anything that shows on the TV**: installing (it briefly closes the home screen),
  pressing keys with `adb shell input`, or changing a TV setting. Don't do it while someone is
  watching something.
- **Never sign in to anything** on the user's behalf, on the TV or on the web. The user creates
  their own TMDB account.
- **Don't change the code** to suit preferences. Everything a user would want to change is a
  setting in `local.properties` (listed in `local.properties.example`).

## 1. Check the TV can run it

Ask the user to open **Settings > My Fire TV > About** on the TV and tell you the device name and
the **Fire OS** version.

| They see | Then |
|---|---|
| Fire OS 7.x or 8.x | Good. Carry on. |
| Fire OS 5.x or 6.x | Not supported (it needs Android 8 or later). Stop and say so. |
| Vega OS, or a Fire TV Stick 4K Select | Not supported: Vega OS can't run Android apps. Stop and say so. |
| Google TV, Android TV, another brand | Not supported yet: the Home button handling is written for Fire OS. Stop and say so. |

Mention that switching HDMI inputs only works on TVs with Fire TV built in, not on sticks.

## 2. Check the computer

The user needs git, JDK 17, the Android SDK (with `adb`), and to be on the same network as the TV.
Check each and offer to install what's missing; ask before installing anything.

```bash
git --version; java -version; adb version
```

- **JDK 17.** macOS: `brew install openjdk@17`. Debian/Ubuntu: `sudo apt install openjdk-17-jdk`.
  The build needs 17 specifically; a newer default JDK is fine as long as 17 is installed
  (`install.sh` finds it on macOS; elsewhere set `JAVA_HOME`).
- **Android SDK.** The simplest route is Android Studio, which installs the SDK and `adb`. Without
  it: macOS `brew install --cask android-commandlinetools android-platform-tools`; then accept the
  licences with `sdkmanager --licenses`, after which Gradle downloads the SDK platform it needs on
  the first build. If the build reports a missing SDK platform or build tools, install exactly what
  it names with `sdkmanager`.
- **Windows:** use WSL. `install.sh` is a bash script.

## 3. Get the code

If you aren't already inside a clone of the repository:

```bash
git clone https://github.com/jeffersonaaron25/firesky-launcher.git
```

```bash
cd firesky-launcher && cp local.properties.example local.properties
```

If Android Studio created a `local.properties` with an `sdk.dir=` line, keep that line. Otherwise
add `sdk.dir=` pointing at the SDK (macOS default: `~/Library/Android/sdk`; with Homebrew's
command-line tools: `/opt/homebrew/share/android-commandlinetools`).

## 4. Turn on ADB on the TV

Ask the user to do this with the remote:

1. **Settings > My Fire TV > About**, then select the device name (the first item) **7 times**
   until the TV says "No need, you are already a developer" or similar.
2. Back out to **My Fire TV > Developer options** and turn **ADB debugging** on. (If there's an
   "Apps from Unknown Sources" option, it isn't needed.)
3. **Settings > My Fire TV > About > Network**: tell you the **IP address**.

Suggest giving the TV a fixed address (a DHCP reservation in their router), so it doesn't change
and break updates later. Then connect:

```bash
adb connect <ip>:5555
```

The TV shows "Allow USB debugging?". Ask them to tick **Always allow from this computer** and
choose **OK**. Then confirm it worked:

```bash
adb -s <ip>:5555 shell getprop ro.build.version.sdk
```

28 is Fire OS 7, 30 is Fire OS 8. If it says `unauthorized`, the prompt wasn't accepted: ask them
to look at the TV and run `adb connect` again. If it can't connect at all, check the address and
that both are on the same network (guest networks often isolate devices).

Write the address into `local.properties` as `tv=<ip>`.

## 5. Ask how they'd like it

Go through these one at a time, in plain words. Offer the default each time; most people take
most of them. Write the answers into `local.properties` as you go.

**The dock.** List what's installed:

```bash
adb -s <ip>:5555 shell pm list packages -3
```

Translate package names into app names for them. Common ones:

| App | Package |
|---|---|
| Netflix | com.netflix.ninja |
| Prime Video | com.amazon.firebat (always installed; not in the list above) |
| YouTube | com.amazon.firetv.youtube |
| Disney+ | com.disney.disneyplus |
| Max | com.hbo.hbonow |
| Hulu | com.hulu.plus |
| Apple TV | com.apple.atve.amazon.appletv |
| AirPlay | com.apple.airplay.amazon.airplay |
| Peacock | com.peacocktv.peacockandroid |
| Paramount+ | com.cbs.ott |
| Plex | com.plexapp.android |
| Kodi | org.xbmc.kodi |
| Spotify | com.spotify.tv.android |

Ask which they want in the dock and in what order; everything else is in All apps. Write
`dock=` as a comma-separated list of package names. Leaving it empty uses a sensible default.
Tell them they can also move and hide dock tiles on the TV itself later (hold OK on a tile).

**Hidden apps.** Anything they never want to see, even in All apps? `hide=`, comma-separated.
Other launchers (Projectivy, Home on Fire) are hidden already.

**PlayStation.** Is a PlayStation plugged into the TV? If yes and they want its tile always in
the dock, ask which HDMI port and set `ps5.port=`. Otherwise leave it empty: the tile appears by
itself when the TV sees a PlayStation on an input.

**Units and clock.** `temperature=celsius|fahrenheit`, `wind=mph|kmh`, `clock=12|24`. Empty
follows the TV's country and its own clock setting, which is right for most people.

**What's new on their apps.** The New and Trending cards need a free TMDB API key. Explain that
it's optional, and if they want it:

1. Sign up at https://www.themoviedb.org/signup and confirm the email.
2. Go to https://www.themoviedb.org/settings/api, choose **Create** (or **Request an API key**),
   pick **Developer**, accept the terms and fill in the short form (personal use is fine; the
   application URL can be the repository's).
3. Copy the **API Key** (the short one, not the long "API Read Access Token").

Let them choose: paste it to you and you'll put it in `local.properties` as `tmdb.key=`, or add it
themselves. Don't repeat it back. Then ask which country their subscriptions are in if it isn't
where the TV thinks it is (`region=`, such as US or GB) and the language for titles (`language=`,
such as en-US).

Show them the finished settings (with the key masked) before building.

## 6. Build and install

Check the TV is on and nobody's watching, then:

```bash
./install.sh
```

The first build takes a few minutes. The script installs the app, grants what it needs over ADB
(usage stats, log reading, the notification listener, storage, and the Home button's
accessibility service), compiles it ahead of time so the first Home press is quick, and restarts
it. Re-running it is always safe.

Things that can go wrong:

| Message | Fix |
|---|---|
| `adb not found` | Install platform-tools or set `ANDROID_HOME`. |
| Can't reach the TV | Wrong address, ADB debugging off, or the prompt on the TV wasn't accepted. |
| Gradle: SDK location not found | Add `sdk.dir=` to `local.properties`. |
| Gradle: missing platform or build tools | `sdkmanager --licenses`, then install what it names. |
| Unsupported class file / Java version | Point `JAVA_HOME` at JDK 17. |
| "Home handling didn't stay on" | Ask the user to open Settings > Accessibility on the TV and turn on **FireSky Home button**. |

## 7. Check it works

Ask the user to press **Home** on the remote: FireSky should appear within about a second. If you
want to confirm from the computer:

```bash
adb -s <ip>:5555 shell dumpsys window | grep mCurrentFocus
```

```bash
adb -s <ip>:5555 shell settings get secure enabled_accessibility_services
```

The first should name the app's package while it's on screen; the second should include its
`HomeKey` service.

**Another launcher fighting over Home?** If Home sometimes goes elsewhere, look for other Home
handlers in that accessibility list or among the installed apps (Projectivy, Wolf Launcher,
Home on Fire, Button Mapper). Ask the user before turning anything off; they can do it themselves
under Settings > Accessibility on the TV.

Then give them the short tour:

- **OK** opens, **hold OK** moves or hides a dock tile or hides a card.
- **Menu (☰)** shows the weather.
- **Home** always comes back here, to the tile they left from; pressed again, to the first card.
- After three minutes untouched, only the sky and the clock remain.
- The first day or two it's still learning which apps they open when; the "Usually now" card
  gets better with use.

**Optional: show off the sky.** With their OK, these preview what the sky can do (run them while
the home screen is showing; the last one puts the real sky back):

```bash
adb -s <ip>:5555 shell am broadcast -a com.firesky.launcher.SKY --ei cloud 0 --el ahead 600
```

```bash
adb -s <ip>:5555 shell am broadcast -a com.firesky.launcher.SKY --ei cloud 0 --ef kp 9
```

```bash
adb -s <ip>:5555 shell am broadcast -a com.firesky.launcher.SKY --ei cloud 0 --ez pass true
```

```bash
adb -s <ip>:5555 shell am broadcast -a com.firesky.launcher.SKY
```

The first shows a clear sky ten hours from now (night, if it's daytime), the second a strong
northern lights storm, the third the space station's next pass (wait about a minute for it to
rise; a pass lasts several minutes). Low passes in the south can be hidden by the hills.

## Later

- **Update:** `git pull && ./install.sh`.
- **Change a setting:** edit `local.properties`, then `./install.sh`.
- **The TV's address changed:** update `tv=` and run `adb connect` again.
- **ADB stopped working after a Fire OS update:** updates sometimes turn ADB debugging off; turn it
  back on as in step 4.
- **Remove it:** `adb uninstall com.firesky.launcher`. The TV goes back to Amazon's home screen.

## If something misbehaves

Each Home press is logged on the TV:

```bash
adb -s <ip>:5555 shell cat /sdcard/Android/data/com.firesky.launcher/files/home.log
```

Live logs, by area: `adb -s <ip>:5555 logcat -s FireSkyHome FireSkyLive FireSkyTitles FireSkyWatching FireSkyKodi FireSkyHdmi`.
Home handling is in `HomeKey.kt`; the cards in `State.kt`; the sky in `Heavens.kt`, `Live.kt` and
`Shader.kt`. Run the tests with `./gradlew testDebugUnitTest`; they don't need the TV.
