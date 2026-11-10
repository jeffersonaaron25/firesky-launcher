---
name: setup-firesky
description: Install FireSky, a calm home screen for Amazon Fire TV, from a computer on the same network, and help with its settings. Use when someone asks to set up, install, update, configure, fix or remove FireSky on their Fire TV. Checks the TV, walks them through turning on ADB, runs the one-command setup, then shows them the settings on the TV.
---

# Set up FireSky on a Fire TV

FireSky is an Android app that replaces the Fire TV home screen. A one-time step from a computer
installs it and switches on what Fire TV only allows over ADB (taking over the Home button, seeing
what's playing). Everything after that is done on the TV, and FireSky updates itself.

Repository: https://github.com/jeffersonaaron25/firesky-launcher

## Ground rules

- **Talk to the user at each step.** Say what you're about to do and why, and wait for them when
  the TV needs the remote.
- **Ask before anything that shows on the TV**: installing (it briefly closes the home screen),
  pressing keys with `adb shell input`, or changing a TV setting. Don't do it while someone is
  watching something.
- **Never sign in to anything** on the user's behalf, on the TV or on the web.
- **Never handle their TMDB key.** It's added on the TV from their phone (see step 5); there's
  nothing to paste into a terminal.
- **Don't change the code.** Everything a user would want to change is in FireSky's settings on the TV.

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

It needs to be a Mac or Linux computer (on Windows, use WSL) on the same network as the TV, with
`curl` and `unzip`. Nothing else: the setup downloads Android's `adb` tool from Google if it isn't
already installed, and the latest FireSky from the repository's releases.

## 3. Turn on ADB on the TV

Ask the user to do this with the remote:

1. **Settings > My Fire TV > About**, then select the device name (the first item) **7 times**
   until the TV says it's already a developer.
2. Back out to **My Fire TV > Developer options** and turn **ADB debugging** on.
3. **Settings > My Fire TV > About > Network**: tell you the **IP address**.

Suggest giving the TV a fixed address (a DHCP reservation in their router).

## 4. Run the setup

Check the TV is on and nobody's watching, then run it with the TV's address:

```bash
curl -fsSL https://raw.githubusercontent.com/jeffersonaaron25/firesky-launcher/main/setup.sh | bash -s -- <ip>
```

Tell the user to look at the TV: it asks **"Allow USB debugging?"**. They should tick **Always
allow from this computer** and choose **OK**. The script waits up to 90 seconds for that.

It installs FireSky, grants its permissions, compiles it so the first Home press is quick (about a
minute), and makes sure Home handling stays on. Re-running it is always safe.

| It says | Then |
|---|---|
| Can't reach the TV | Wrong address, ADB debugging off, the prompt wasn't accepted, or the computer is on another network (guest networks often isolate devices). |
| The FireSky on the TV can't be updated in place | An older FireSky signed with another key is installed. Explain that replacing it resets what it has learned (habits, dock edits), ask, and if they agree, run again with `--replace` added after the address. |
| Home handling didn't stay on | Run it again. If it still fails, check for another launcher holding the Home button (step 6). |

## 5. Show them the settings

Ask the user to press **Home**: FireSky should appear within about a second. Then:

- **FireSky settings**: hold **OK** on the **Settings** tile (or press **Menu** on it), then
  **FireSky settings**. Units, clock, streaming country, the PlayStation tile, and the version.
- **New and Trending** (optional): in FireSky settings, choose **New and Trending**. A QR code
  appears; they scan it with their phone, and the page explains how to get a free TMDB key and
  saves it to the TV. Don't ask for the key yourself.
- **The dock**: hold **OK** on any tile to move or hide it.
- **Weather**: **Up** to the temperature in the corner, then **OK**.
- **Home** comes back to FireSky, on the tile they left from; pressed again, to the first card.
- After three minutes untouched, only the sky and the clock remain.
- The first day or two it's still learning which apps they open when; "Usually now" gets better
  with use.

To confirm from the computer (use `~/.firesky/platform-tools/adb` if `adb` isn't on the path):

```bash
adb -s <ip>:5555 shell settings get secure enabled_accessibility_services
```

It should include `com.firesky.launcher/.HomeKey`.

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
rise).

## 6. If something misbehaves

- **Another launcher fighting over Home**: if Home sometimes goes elsewhere, look for other Home
  handlers in the accessibility list above or among the installed apps (Projectivy, Wolf Launcher,
  Home on Fire, Button Mapper). Ask before turning anything off.
- **Home stopped working after a Fire OS update**: updates sometimes turn ADB debugging off. Turn
  it back on (step 3) and run the setup again.
- **Each Home press is logged on the TV**:

  ```bash
  adb -s <ip>:5555 shell cat /sdcard/Android/data/com.firesky.launcher/files/home.log
  ```

- Live logs, by area: `adb -s <ip>:5555 logcat -s FireSkyHome FireSkyLive FireSkyTitles FireSkyWatching FireSkyKodi FireSkyHdmi FireSkyUpdate`.

## Later

- **Updates** arrive by themselves: an **Update** card appears on the home screen; select it and
  confirm. To check now: FireSky settings > Version.
- **Remove it:** `adb -s <ip>:5555 uninstall com.firesky.launcher`. The TV goes back to Amazon's home screen.

## Building it yourself

Only for working on FireSky's code. It needs JDK 17 and the Android SDK. Clone the repository,
`cp local.properties.example local.properties`, set `tv=` (and `sdk.dir=` if Android Studio hasn't),
then `./install.sh` builds it and runs the same setup. Builds of your own don't update themselves.
Code map: Home handling in `HomeKey.kt`, the cards in `State.kt`, settings in `SettingsScreen.kt`,
updates in `Updater.kt`, the sky in `Heavens.kt`, `Live.kt` and `Shader.kt`. Tests:
`./gradlew testDebugUnitTest` (no TV needed).
