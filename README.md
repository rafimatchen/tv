# Hisense Google TV: debloat + Glass Home launcher

Device: **Hisense "SmartTV 4K FFM"** (`lushan`, MediaTek, Google TV on Android 12 / SDK 31,
1.8 GB RAM), at `192.168.1.157:5555`. Everything here was done over network ADB and checked
with screenshots pulled from the TV. The backup and screenshots stay on the local machine and
are not in this repo, since they contain device identifiers and the Wi-Fi name.

```
backup/<date>/          package lists, disabled list, getprop, all settings, HOME/inputs state (taken before any change; local only, gitignored)
disabled_by_us.txt      every package this project disabled; restore.sh reads it
scripts/disable.sh      disable packages (logged to disabled_by_us.txt)
scripts/install.sh      build + install Glass Home, make it the home screen
scripts/restore.sh      undo everything
launcher/               Glass Home source (Java, AndroidX RecyclerView)
shot.sh                 ./shot.sh name -> shots/name.png (local only, gitignored)
```

All scripts default to `ANDROID_SERIAL=192.168.1.157:5555`; set it if the TV's IP changes.

## What was disabled (41 packages, `pm disable-user --user 0`, nothing uninstalled)

| Group | Packages |
|---|---|
| Hisense telemetry | `com.vt.tv.evservice` (ACR "what's on screen" tracking + promos), `com.vt.tv.smartservice` (Firebase log reporting), `com.google.android.feedback` |
| Content / ads | `net.vidaatv.hisense.android.tv` (VIDAA Channels, launcher ad clicks), `com.vidaa.oemtabcontentprovider`, `com.vt.tv.customerlauncher` (Hisense rows for the Google TV home), `android.autoinstalls.config.google.gtvpai` (Play auto-installs) |
| Background | `com.vt.storemode`, `com.vt.tv.aiot`, `tv.mopa.ginga` (Brazilian ISDB middleware), `com.vt.tv.dlna`, `com.vt.tv.protector` (cleaner) |
| Preloaded extras | `com.vt.alexa`, `com.hisense.odinbrowser`, `com.google.android.play.games`, `com.vt.hishow`, `com.vt.tv.mediacenter`, `com.vt.tv.singlelisten` (BT Music) |
| Miracast | `com.vt.tv.mobiledeviceconnectiontips` |
| Streaming | YouTube Music, YouTube TV, Google TV (Play Movies), Apple TV, Hulu, Max, Paramount+, discovery+, fubo, NFL, Crave, CBC Gem, TSN, Sportsnet, TuneIn, Tubi, Pluto TV, Haystack, Instagram, Peloton |
| Home screen | `com.google.android.apps.tv.launcherx` (stock Google TV home), `com.google.android.tungsten.setupwraith` (see "Why the setup wizard is disabled") |

**Kept on purpose:** Netflix, YouTube, Prime Video, Disney+, ESPN, MLB, Plex, Spotify; Google
Assistant, Chromecast, AirPlay; system updates; and everything the TV needs: Live TV
(`com.vt.tv.tvcenter`), HDMI/AV/tuner inputs (`com.vt.source.*`, MediaTek TIF), the Inputs menu
(`com.vt.tv.input`), remote, Bluetooth and pairing (`com.mediatek.autopair`,
`com.vt.remoteupgrade`, `com.google.android.tv.remote.service`), the keyboard, Play Store and
Play Services, DRM provisioning (`com.vt.edv.drmp`), Dolby, and the EULA and factory services.

`com.vt.tv.smartsense` still sends some app-usage reports, but it also handles remote keys,
input switching and wake-on-remote, so it stays.

Two packages (`com.mediatek.tis`, `com.mediatek.dtv.tvinput.isdbtuner`) were already disabled
at the factory. restore.sh doesn't touch them.

### Re-enabling an app

One app:

```sh
adb connect 192.168.1.157:5555
adb shell pm enable --user 0 com.hulu.livingroomplus
```

then delete its line from `disabled_by_us.txt`. You can also do it on the TV: Settings → Apps →
See all apps → Show system apps → pick the app → Enable.

Everything: `scripts/restore.sh` re-enables all of `disabled_by_us.txt`, puts the Google TV
home back, removes the remote-button helper and resets Private DNS. Use
`scripts/restore.sh --uninstall-launcher` to remove Glass Home as well.

Disable another app: `scripts/disable.sh <package>`.

**After a firmware update** some apps may come back. Re-apply with:

```sh
scripts/disable.sh $(cat disabled_by_us.txt)
scripts/install.sh --no-build
```

## Ad-blocking DNS

Private DNS is set to AdGuard (`dns.adguard-dns.com`, hostname mode). Ad and tracker domains
resolve to nothing; YouTube playback was checked afterwards. **It does not block YouTube ads**,
because YouTube serves ads from the same servers as the videos.

Undo:

```sh
adb shell settings put global private_dns_mode opportunistic
adb shell settings delete global private_dns_specifier
```

## Glass Home launcher

Apple TV-style home screen. Pitch black, with a greeting, a thin clock and the date in Outfit
(SIL Open Font License, `launcher/app/src/main/assets/fonts/OFL.txt`). Frosted pills for Inputs,
Wi-Fi (shows the network name) and Settings. Four dock apps sit in a lighter band, with the
focused one's name and a glow coloured from its banner. All other apps are in a 4-column grid
below. One glass highlight springs between tiles.

Remote behaviour:
- **Up/Down** move exactly one row. Up from the dock goes to the pills, and Down from the dock
  goes into the grid, which scrolls up.
- **Left/Right** stop at the ends of a row. Nothing falls through from the pills to the tiles.
- **Back** returns to the dock. **Home**, while already on the home screen, also returns to the
  dock.
- **Inputs** opens a dropdown with HDMI 1–4, AV, Live TV and AirPlay (green dot = cable
  connected). It switches through the TV input framework.
- The last focused tile is remembered across app launches and reboots.

It holds about 30 MB and does no network polling. The clock runs off the system minute tick,
the Wi-Fi name off network callbacks, and the app list off package-change broadcasts.

### Build and install

```sh
scripts/install.sh
```

Needs JDK 17 (`brew install openjdk@17`) and the Android SDK (`brew install --cask
android-commandlinetools`, then `sdkmanager "platforms;android-34" "build-tools;34.0.0"`).
The script:
1. builds a debug APK and installs it, falling back to `adb push` + `pm install` if `adb install` hangs;
2. grants location permission (Android shows the Wi-Fi name only to apps that hold it);
3. sets Glass Home as the home screen and disables the stock launcher and setup wizard;
4. enables the remote-button helper.

### Change the dock apps or their order

Without rebuilding (takes effect immediately):

```sh
adb shell am broadcast -n com.rafi.glasshome/.ConfigReceiver -a com.rafi.glasshome.SET_DOCK \
  --es dock "com.plexapp.android,com.espn.score_center,com.google.android.youtube.tv,com.amazon.amazonvideo.livingroom"
# back to the built-in default:
adb shell am broadcast -n com.rafi.glasshome/.ConfigReceiver -a com.rafi.glasshome.SET_DOCK --es dock default
```

To change the built-in default, edit `default_dock` in
`launcher/app/src/main/res/values/config.xml` and run `scripts/install.sh`. Package names of
installed apps:

```sh
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER
```

Greeting name: `... -a com.rafi.glasshome.SET_NAME --es name "Rafi"` (`--esn name` for no name,
`--es name default` for config.xml's `greeting_name`).

### Wallpaper

The background is pitch black by default. To use an image (JPG/PNG/WebP, ideally 1920×1080):

```sh
adb push photo.jpg /sdcard/Android/data/com.rafi.glasshome/files/wallpaper.jpg
adb shell input keyevent KEYCODE_HOME
```

A dark scrim keeps the text readable. Back to black:
`adb shell rm /sdcard/Android/data/com.rafi.glasshome/files/wallpaper.jpg`, then press Home.

### Remote app buttons (YouTube, Prime Video)

On this TV those buttons are routed to the stock Google TV home, which is disabled. Glass Home
has a small accessibility service, "Glass Home remote buttons", that sees key presses and opens
the apps itself. It filters keys only; it can't read the screen. The mapping is
`remote_buttons` in `config.xml` (`190` = YouTube, `295` = Prime Video). The Netflix button
works without it.

To map another button, press it, find its keycode with
`adb logcat -d | grep -E "receive IR keycode|GLOBAL_BUTTON"`, add a `keycode=package` line, and
rebuild.

If those buttons stop working, check that the service is enabled **and bound** for user 0:

```sh
adb shell settings get secure enabled_accessibility_services   # must include com.rafi.glasshome/.RemoteButtonService
adb shell dumpsys accessibility | grep "Bound services"        # must list "Glass Home remote buttons"
```

`adb shell input keyevent 190` is **not** a valid test: injected keys skip accessibility key
filtering. Only the real remote proves it.

### Why the setup wizard is disabled

Google TV's launcher declares HOME priority 2. While it's enabled it always wins, whatever
default you set. With it disabled, the setup wizard's `RecoveryActivity` (priority 1) takes over
and shows a black screen. The shell can't disable a single component of a system app, so the
whole `setupwraith` package is disabled. Settings → System → System update was checked
afterwards and still works.

## If something breaks

These paths were tested on this TV by disabling Glass Home on purpose.

**Black screen when you press Home (Glass Home crashed or is gone), no computer needed**

With Glass Home gone, Home lands on an empty black placeholder screen. The Settings button does
**nothing** on that screen, but the Input button still works:

1. Press **Input** on the remote, choose **TV**.
2. In Live TV, press the **Settings** button on the remote. Native Settings opens.
3. Apps → See all apps, scroll down to the disabled apps, pick **Google TV**, choose **Enable**.
4. Press Home. You're back on the stock Google TV home (it outranks Glass Home once enabled).
   The YouTube and Prime buttons work again too.

**With a computer**

- Turn USB debugging on: Settings → System → Developer options. If Developer options is missing,
  first go to Settings → System → About and press OK on "Android TV OS build" 7 times.
- `scripts/install.sh` reinstalls Glass Home and makes it the home screen again.
- `scripts/restore.sh` puts the TV back to stock.

**YouTube / Prime buttons stopped working** (typically after Glass Home was reinstalled,
disabled or crashed): Settings → Accessibility → Glass Home remote buttons, switch it off and on.
Or run `scripts/install.sh --no-build`.

**Last resort:** Settings → System → About → Factory reset. This wipes everything, including all
disabled-app changes and Glass Home; the TV comes back exactly as it shipped.

## Troubleshooting

- **Can't connect:** turn on USB debugging in Developer options (Settings → System → About →
  press OK on "Android TV OS build" 7 times), run `adb connect 192.168.1.157:5555`, and accept
  "Allow USB debugging?" on the TV. On this TV, network ADB came back by itself after a reboot.
- **macOS "No route to host"** although the port is open: that's macOS Local Network privacy
  blocking `adb`. Use the Python `adb-shell` package in `.venv` instead. It wasn't needed here.
- **`adb install` hangs:** `install.sh` already falls back to `pm install`.
- **Blank screen right after install:** the first (cold) start takes about 1.5 s.
- **Back to stock:** `scripts/restore.sh`.

**When you're done, turn USB debugging off again:** Settings → System → Developer options → USB
debugging. Nothing is plugged into the TV's USB ports, so debugging doesn't cost any USB power
here, but leaving it on means anyone on your network can try to connect to the TV.
