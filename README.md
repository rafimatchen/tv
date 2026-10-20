# Glass Home: a debloated Hisense Google TV with an Apple TV-style home screen

Scripts that debloat a Hisense Google TV over ADB, plus **Glass Home**, a small replacement
launcher. Tested on a Hisense "SmartTV 4K FFM" (MediaTek, Google TV on Android 12).

- **Debloat:** 41 packages disabled (Hisense tracking and ads, preloaded extras, unused apps).
  Nothing is uninstalled, and one script undoes it all.
- **Glass Home:** black background, clock and greeting, a 4-app dock, an app grid, an Inputs
  menu and a clock screensaver. Navigation with the remote is predictable, and the launcher uses about 30 MB of RAM.

## Quick start

Requirements: `adb`, JDK 17 and the Android SDK (platform 34). On macOS:

```sh
brew install --cask android-platform-tools android-commandlinetools && brew install openjdk@17
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
sdkmanager "platforms;android-34" "build-tools;34.0.0"
```

On the TV, turn on Developer options (Settings → System → About → press OK on "Android TV OS
build" 7 times), then turn on USB debugging. Then:

```sh
export ANDROID_SERIAL=<tv-ip>:5555
adb connect $ANDROID_SERIAL                       # accept "Allow USB debugging?" on the TV
scripts/install.sh                                # build, install and set Glass Home as home
scripts/disable.sh $(cat disabled_by_us.txt)      # debloat (edit the list first)
```

Turn USB debugging off again when you're done.

## Undo

| To | Run |
|---|---|
| Undo everything | `scripts/restore.sh` (add `--uninstall-launcher` to remove Glass Home too) |
| Re-enable one app | `adb shell pm enable --user 0 <package>`, then remove it from `disabled_by_us.txt` |
| Disable another app | `scripts/disable.sh <package>` |
| Re-apply after a firmware update | `scripts/disable.sh $(cat disabled_by_us.txt) && scripts/install.sh --no-build` |

On the TV itself: Settings → Apps → See all apps → pick a disabled app → Enable.

## Customise

**Dock apps** (takes effect immediately; `--es dock default` resets it):

```sh
adb shell am broadcast -n com.rafi.glasshome/.ConfigReceiver -a com.rafi.glasshome.SET_DOCK \
  --es dock "com.plexapp.android,com.espn.score_center,com.google.android.youtube.tv,com.amazon.amazonvideo.livingroom"
```

To change the permanent default, edit `default_dock` in
`launcher/app/src/main/res/values/config.xml` and run `scripts/install.sh`.

**Greeting name:** `... -a com.rafi.glasshome.SET_NAME --es name "Your name"`. Use `--esn name`
for no name.

**Wallpaper** (black by default):

```sh
adb push photo.jpg /sdcard/Android/data/com.rafi.glasshome/files/wallpaper.jpg   # then press Home
adb shell rm /sdcard/Android/data/com.rafi.glasshome/files/wallpaper.jpg         # back to black
```

**Screensaver:** `install.sh` sets Glass Home's clock screensaver, which moves once a minute to
avoid burn-in. To switch back to Google's Ambient mode, run `adb shell settings put secure
screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop`. Timing, in
milliseconds: `adb shell settings put system screen_off_timeout 600000` (start after 10 min) and
`adb shell settings put secure sleep_timeout 1200000` (sleep after 20 min).

**Remote app buttons:** with the stock launcher disabled, the YouTube and Prime buttons need
Glass Home's "remote buttons" accessibility helper. It sees key presses only, not the screen. The
mapping is `remote_buttons` in `config.xml`. To find another button's keycode, press it and run
`adb logcat -d | grep "IR keycode"`.

## If something breaks

**Black screen on Home, no computer needed.** The Settings button doesn't work on that screen,
but Input does:

1. Press **Input** and choose **TV**.
2. Press **Settings**.
3. Go to Apps → See all apps, pick **Google TV**, then choose **Enable**.
4. Press Home. You're back on the stock home screen.

**YouTube or Prime buttons stopped working** (usually after a reinstall): Settings →
Accessibility → Glass Home remote buttons, switch it off and on, or run
`scripts/install.sh --no-build`. Injected keys (`adb shell input keyevent`) skip this helper, so
test with the real remote.

**Last resort:** Settings → System → About → Factory reset.

<details>
<summary>What gets disabled, and what stays</summary>

| Group | Packages |
|---|---|
| Tracking | `com.vt.tv.evservice` (viewing tracking), `com.vt.tv.smartservice` (log reporting), `com.google.android.feedback` |
| Ads and content | `net.vidaatv.hisense.android.tv`, `com.vidaa.oemtabcontentprovider`, `com.vt.tv.customerlauncher`, `android.autoinstalls.config.google.gtvpai` |
| Background | `com.vt.storemode`, `com.vt.tv.aiot`, `tv.mopa.ginga`, `com.vt.tv.dlna`, `com.vt.tv.protector` |
| Extras | Alexa, Hisense browser, Play Games, HiShow, Media Center, BT Music, Miracast |
| Streaming | 20 apps; see `disabled_by_us.txt` |
| Home screen | `com.google.android.apps.tv.launcherx`, `com.google.android.tungsten.setupwraith` |

Kept on purpose: Live TV, all inputs and the Inputs menu, the remote and Bluetooth, keyboard,
Play Store and Services, DRM, Dolby, system updates, Assistant, Chromecast, AirPlay.
`com.vt.tv.smartsense` still sends some usage reports, but it also handles the remote and input
switching, so it stays.

</details>

<details>
<summary>Notes on how it works</summary>

- **Why the setup wizard is disabled:** the Google TV launcher outranks every other home app
  (HOME priority 2). Once it's disabled, the setup wizard's RecoveryActivity (priority 1) takes
  over with a black screen. ADB can't disable a single screen of a system app, so the whole
  wizard is disabled. System updates still work.
- **Inputs menu:** switches through the TV input framework. AirPlay registers as a tuner on this
  TV, so only the hardware tuner (`/HW` in its input ID) is treated as Live TV.
- **Wi-Fi name:** Android only shows it to apps with location permission, which
  `install.sh` grants.
- **Ad-blocking DNS (optional):** `adb shell settings put global private_dns_specifier
  dns.adguard-dns.com && adb shell settings put global private_dns_mode hostname`. It doesn't
  block YouTube ads. Undo with `private_dns_mode opportunistic`.
- **macOS "No route to host":** macOS Local Network privacy is blocking `adb`. The Python
  `adb-shell` package works as a fallback.

</details>

The Outfit font is under the SIL Open Font License (`launcher/app/src/main/assets/fonts/OFL.txt`).
