<div align="center">

# FireSky

**A calm, ad-free home screen (launcher) for Amazon Fire TV, with the real night sky behind it.**

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Fire OS 7 | 8](https://img.shields.io/badge/Fire%20OS-7%20%7C%208-FF9900)](#compatibility)
[![Kotlin + Jetpack Compose](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)](app/src/main/java/com/firesky/launcher)
[![Vibe coded with Claude](https://img.shields.io/badge/vibe%20coded%20with-Claude-D97757)](https://claude.com/claude-code)

![FireSky home screen at night, with the real stars behind it](docs/screenshots/home-night.jpg)

</div>

FireSky replaces the Fire TV home screen with a quiet one: your apps, what you were watching, what's new on the services you use, and the weather. Behind it is the real sky over your home: the stars, moon and planets where they actually are, the space station passing overhead, meteor showers, the northern lights during strong storms, and live clouds moving with the wind.

No ads, no sponsored rows, no account. It learns your habits on the TV and sends nothing about you anywhere.

> [!NOTE]
> **Vibe coded with Claude.** FireSky was built in conversation with [Claude Code](https://claude.com/claude-code) and tested on a real Fire TV.

## Contents

- [Features](#features)
- [Screenshots](#screenshots)
- [Compatibility](#compatibility)
- [Installation](#installation)
- [Configuration](#configuration)
- [Usage](#usage)
- [Updating and uninstalling](#updating-and-uninstalling)
- [Privacy](#privacy)
- [How it works](#how-it-works)
- [Troubleshooting](#troubleshooting)
- [Contributing](#contributing)
- [Acknowledgements](#acknowledgements)
- [License](#license)

## Features

- **The real sky** for your location, computed on the TV: stars, moon and planets, space station passes, meteor showers, the northern lights, and live clouds.
- **Continue watching** from Netflix, Kodi or any app that reports playback, with the time it would end.
- **Usually now:** the app you tend to open at this hour, learned on the TV.
- **New on your apps:** new and trending films and shows on the services you have installed, plus new episodes and seasons of what you watch. Optional; needs a free TMDB key.
- **Weather:** now, the next 12 hours and the week, with a heads-up when rain is coming. Select the temperature in the top corner. No API key needed.
- **Your dock, your order.** Pick the apps and their order; it never rearranges itself. Amazon's built-in extras stay out of the way.
- **Instant Home.** The Home button and waking the TV land on FireSky, not Amazon's home screen.
- **PlayStation tile** that shows whether the console is on and switches to its input (TVs with Fire TV built in).
- **Idle mode:** after three minutes untouched, only the sky and the clock remain.

## Screenshots

| | |
|---|---|
| ![Daytime home screen](docs/screenshots/home-day.jpg) | ![A new film on one of your apps](docs/screenshots/new-on-your-apps.jpg) |
| The app you usually open at this hour comes first | New and trending on your services |
| ![The weather panel](docs/screenshots/weather.jpg) | ![All apps](docs/screenshots/all-apps.jpg) |
| Weather, from the temperature in the corner | All apps, without the clutter |
| ![The space station crossing the sky](docs/screenshots/space-station.jpg) | ![The northern lights during a strong storm](docs/screenshots/northern-lights.jpg) |
| The view follows the space station as it passes over | The northern lights (shown during a simulated Kp 9 storm) |

## Compatibility

| Device | Supported |
|---|---|
| Fire TV devices on **Fire OS 7 or 8** (Android 9 or 11) | ✅ Tested on a Fire TV smart TV running Fire OS 7. Sticks and Cubes on these versions should work but haven't been tested yet; reports welcome. |
| Fire OS 5 or 6 | ❌ Needs Android 8 or later |
| Vega OS devices, such as the Fire TV Stick 4K Select | ❌ Can't run Android apps |
| Google TV and other Android TV devices | ❌ Not yet: Home button handling is specific to Fire OS |

Switching HDMI inputs (the PlayStation tile) needs a TV with Fire TV built in.

## Installation

FireSky isn't on the Appstore. You build it on a computer and install it over your home network with ADB.

**You'll need:**

- A Mac, Linux, or Windows (with WSL) computer on the same network as the TV
- [JDK 17](https://adoptium.net/temurin/releases/?version=17)
- The [Android SDK](https://developer.android.com/studio) with `adb` (Android Studio installs both, or use the command-line tools)
- Optional: a free [TMDB API key](https://www.themoviedb.org/settings/api) for the New and Trending cards

### Option 1: with a coding agent

Give your coding agent (such as Claude Code) this prompt:

```text
Set up FireSky on my Fire TV by following https://github.com/jeffersonaaron25/firesky-launcher/blob/main/skills/setup-firesky/SKILL.md
```

It checks your computer, walks you through turning on ADB, asks how you'd like the dock and units, helps you get a TMDB key, then builds and installs FireSky. The full steps are in [skills/setup-firesky/SKILL.md](skills/setup-firesky/SKILL.md).

### Option 2: manually

1. **Turn on ADB on the TV.**
   1. Go to **Settings > My Fire TV > About** and select the device name 7 times, until it says you're a developer.
   2. Go to **Settings > My Fire TV > Developer options** and turn on **ADB debugging**.
   3. Note the TV's IP address under **Settings > My Fire TV > About > Network**. A DHCP reservation in your router keeps it from changing.

2. **Clone the repository and create your settings file.**

   ```bash
   git clone https://github.com/jeffersonaaron25/firesky-launcher.git
   cd firesky-launcher
   cp local.properties.example local.properties
   ```

3. **Edit `local.properties`.** Set `tv=` to the TV's IP address. If Android Studio hasn't already added it, also set `sdk.dir=` to your Android SDK path. Everything else is optional; see [Configuration](#configuration).

4. **Build and install.**

   ```bash
   ./install.sh
   ```

   The first build takes a few minutes. The first time it connects, the TV asks whether to allow USB debugging: choose **Always allow from this computer**. If the script says it can't reach the TV, accept the prompt and run `./install.sh` again.

5. **Press Home** on the remote.

> [!TIP]
> If you use another launcher or Home-button app (Projectivy, Wolf Launcher, Home on Fire, Button Mapper), turn off its Home button handling so they don't compete.

## Configuration

All settings live in `local.properties`, which git ignores, so your key and preferences stay on your computer. After changing a setting, run `./install.sh` again.

| Setting | Description | Default |
|---|---|---|
| `tv` | The TV's IP address | Required |
| `tmdb.key` | TMDB API key; turns on the New and Trending cards | Off |
| `dock` | Dock apps in order, as comma-separated package names | Netflix, Prime Video, YouTube, Disney+, Max, Hulu, Apple TV, Peacock, Paramount+, Plex, Kodi (whichever are installed) |
| `hide` | Apps to hide entirely, as comma-separated package names | None (other launchers are always hidden) |
| `ps5.port` | HDMI port of a PlayStation, for a tile that's always in the dock | The tile appears when a PlayStation is detected |
| `temperature` | `celsius` or `fahrenheit` | From the TV's country |
| `wind` | `mph` or `kmh` | From the TV's country |
| `clock` | `12` or `24` | The TV's own setting |
| `region` | Streaming catalogue country, such as `US` or `GB` | The TV's country |
| `language` | Language for titles, such as `en-US` | The TV's language |

Example:

```properties
tv=192.168.1.50
tmdb.key=your_tmdb_api_key
dock=com.netflix.ninja,com.amazon.firebat,com.amazon.firetv.youtube,com.disney.disneyplus
temperature=celsius
clock=24
```

To list the package names of the apps on your TV:

```bash
adb shell pm list packages -3
```

### Getting a TMDB API key

1. [Create a free TMDB account](https://www.themoviedb.org/signup) and confirm your email.
2. Open [Settings > API](https://www.themoviedb.org/settings/api), choose **Create**, then **Developer**, and fill in the short form. Personal use is fine.
3. Copy the **API Key** (not the longer "API Read Access Token") into `tmdb.key=`.

## Usage

| Button | Action |
|---|---|
| **OK** | Open the selected app or title |
| **Hold OK** | Move or hide a dock tile, or hide a card. On the Settings tile, open Amazon's home screen |
| **Up** to the temperature in the corner, then **OK** | Weather |
| **Menu (☰)** | Fire TV's quick settings, as everywhere else on Fire TV |
| **Home** | Return to FireSky, on the tile you left from. Press again for the first card |
| **Back** | Close All apps or the weather |

The Settings tile opens Fire TV's full settings. Press **Home** to come back.

## Updating and uninstalling

Update:

```bash
git pull && ./install.sh
```

Uninstall (the TV goes back to Amazon's home screen):

```bash
adb uninstall com.firesky.launcher
```

## Privacy

FireSky has no account, no analytics and no server. Everything it learns about your viewing stays on the TV. The TV contacts these services directly:

| Service | Used for |
|---|---|
| [GeoJS](https://www.geojs.io) or [ipwho.is](https://ipwho.is) | Your approximate location from your IP address, once a week. Only the city-level result is kept. |
| [Open-Meteo](https://open-meteo.com) | Weather for that location |
| [TMDB](https://www.themoviedb.org) and [Wikidata](https://www.wikidata.org) | Titles, artwork and links to them in your apps (with your own TMDB key). Nothing about what you watch is sent. |
| [NOAA SWPC](https://www.swpc.noaa.gov) | Northern lights forecast |
| [CelesTrak](https://celestrak.org) | The space station's orbit |

`install.sh` grants these permissions over ADB. None of the data leaves the TV.

| Permission | Why |
|---|---|
| Read system logs | Catch the Home button the moment it's pressed, and read Netflix's Continue Watching list |
| Usage stats | Which apps you open, and when |
| Notification listener | What's paused, for the Continue card |
| Storage | Kodi's watch history |
| Accessibility service | Home button handling |

## How it works

Fire OS doesn't allow replacing its home screen. FireSky uses an accessibility service, as other Fire TV launchers do. To make Home feel instant, it watches the system log for the Home press, covers the screen with a snapshot of itself, and brings itself to the front, avoiding the five-second delay Fire OS normally applies. See [`HomeKey.kt`](app/src/main/java/com/firesky/launcher/HomeKey.kt).

The sky is computed on the TV from published formulas (Meeus for the sun and moon, JPL orbital elements for the planets, SGP4 for the space station) and rendered with OpenGL ES. The unit tests check it against [Astronomy Engine](https://github.com/cosinekitty/astronomy) and [satellite.js](https://github.com/shashwatak/satellite-js).

Built with Kotlin and Jetpack Compose.

## Troubleshooting

<details>
<summary><b>Home goes to Amazon's home screen</b></summary>

Run `./install.sh` again. If it reports that Home handling didn't stay on, go to **Settings > Accessibility** on the TV and turn on **FireSky Home button**.
</details>

<details>
<summary><b>The script can't reach the TV</b></summary>

Check the IP address, that the computer and TV are on the same network (guest networks often isolate devices), and that ADB debugging is still on. Some Fire OS updates turn it off.
</details>

<details>
<summary><b>No New or Trending cards</b></summary>

Check `tmdb.key` in `local.properties`, and that `region` matches the country of your subscriptions. Then run `./install.sh` again.
</details>

<details>
<summary><b>The Netflix card says "Netflix" instead of the show</b></summary>

Netflix shares its Continue Watching list with the TV only from time to time. The card picks up the title once it does.
</details>

<details>
<summary><b>Reading the logs</b></summary>

Each Home press is logged on the TV:

```bash
adb shell cat /sdcard/Android/data/com.firesky.launcher/files/home.log
```

Live logs:

```bash
adb logcat -s FireSkyHome FireSkyLive FireSkyTitles FireSkyWatching FireSkyKodi FireSkyHdmi
```
</details>

## Contributing

Issues and pull requests are welcome. Reports from Fire TV Stick and Cube owners are especially useful.

Run the unit tests (no TV needed) with:

```bash
./gradlew testDebugUnitTest
```

## Acknowledgements

- Film and show data and images from [TMDB](https://www.themoviedb.org), with availability from JustWatch. This product uses the TMDB API but is not endorsed or certified by TMDB.
- Weather from [Open-Meteo](https://open-meteo.com) (CC BY 4.0)
- Star positions and constellation lines from [d3-celestial](https://github.com/ofrohn/d3-celestial) by Olaf Frohn (BSD-3-Clause)
- Space station orbits from [CelesTrak](https://celestrak.org), computed with SGP4 ported from [satellite.js](https://github.com/shashwatak/satellite-js) (MIT)
- Northern lights forecast from [NOAA SWPC](https://www.swpc.noaa.gov)
- Streaming links from [Wikidata](https://www.wikidata.org) (CC0)
- The [Outfit](https://github.com/Outfitio/Outfit-Fonts) typeface (SIL Open Font License 1.1)

## License

[MIT](LICENSE). Third-party licences are in [licenses](licenses).

Amazon, Fire TV and Fire OS are trademarks of Amazon.com, Inc. or its affiliates. FireSky is not affiliated with or endorsed by Amazon.
