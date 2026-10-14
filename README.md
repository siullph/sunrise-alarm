# Sunrise Alarm

An Android app that wakes you with light only: the phone's rear flashlight or its screen
starts at its dimmest level and brightens until your wake-up time, like a sunrise. No sound.

## Features

- **Sunrise before your phone alarm.** The app shows the next alarm set in your Clock app.
  Turn on "Sunrise before my phone alarm" and pick a fade duration; the light starts that many
  minutes before the alarm and reaches full brightness when it rings. It follows whichever
  phone alarm is next and re-plans whenever that changes.
- **Sunrise-only alarms.** Create alarms in the app with a wake-up time, a fade duration
  (5 to 60 minutes) and optional repeat days.
- **Flash or screen, per alarm.** Each sunrise uses either the rear flashlight or the screen.
  The screen light fades from black to the alarm's color, updated every display frame along
  a perceptual curve: first the color rises at the lowest backlight, then the backlight rises
  exponentially (which the eye reads as even steps).
- **Colors.** White is built in. Save up to 10 colors of your own, each with a name, from a
  color wheel or a color temperature (Kelvin) slider. The ⋮ menu in the color editor has
  **Fine-tune color** (exact red, green and blue) and **Delete color**. Long-press a color to
  edit it.
- **Peak brightness** (per alarm, screen only). At the end of the fade the light pushes past
  the normal maximum into the display's HDR headroom (Android 14+), toward the brightness the
  phone uses in direct sunlight. How far it goes, and for how long, is up to the phone.
- **Pick up to end it.** The gyroscope (or the accelerometer on phones without one) notices
  the phone being picked up, and the light fades out in under half a second. Tapping the light
  does the same.
- **Overlay over the alarm.** With "display over other apps" allowed, the screen light is drawn
  over everything, including the Clock app's alarm screen. When it fades away, the Clock app's
  dismiss / snooze slider is underneath. Sunrise-only alarms have their own slider: right to
  dismiss, left to snooze (10 minutes; the snoozed alarm comes back dark, without the light).
- **Snoozes never light up.** A phone alarm within an hour of the one a sunrise just ran for
  (a snooze, or a backup alarm) doesn't start another sunrise.
- **Test this light** runs a 30-second sunrise with that alarm's light and color; **Stop light**
  (in the app or the notification) switches the light off.
- After the wake-up time the light stays at full brightness for 30 minutes, then turns off.
- **Night Light and grayscale don't touch the light.** While a screen sunrise is lit, the app
  switches off Night Light (and Samsung's Eye comfort shield) and grayscale, then puts back
  whichever were on once the light goes out, following the Night Light schedule if there is
  one. This needs a one-time permission granted from a computer (the app shows the command):

  ```sh
  adb shell pm grant com.siullph.sunrisealarm android.permission.WRITE_SECURE_SETTINGS
  ```

  With [Shizuku](https://shizuku.rikka.app/) running and allowed, the app can grant this
  itself ("Allow with Shizuku"), with no computer. Without it, the app shifts the color to
  cancel Night Light's tint (the hue comes back, but not full brightness).
- **Bedtime mode grayscale** (Pixel and other Android 15+ phones) isn't a setting: the system
  lowers the display's saturation, which only the adb shell can raise again. With Shizuku
  running and allowed, the app sets full color for the sunrise and puts grayscale back
  afterwards if Bedtime mode is still on. Shizuku can be started with Wireless debugging on the
  phone itself, and has to be started again after each restart.
- Material You: colors follow the wallpaper on Android 12+.

## Limitations

- Android does not let apps read the full list of alarms in the Clock app. The only alarm it
  exposes is the *next* one (`AlarmManager.getNextAlarmClock`), so the linked sunrise always
  attaches to the next upcoming phone alarm.
- Gradual brightening needs Android 13+ and a flashlight that supports brightness levels.
  On other phones the flashlight turns on at the start of the fade.
- The screen light opens through a full-screen notification, so notifications must be allowed.
  On Android 14+ the app also needs "full-screen notifications" allowed (the app prompts).
  If the phone is unlocked and in use when a screen sunrise starts, Android shows a
  notification instead; tap it to open the light.
- Without the adb permission above, Night Light dims the screen color. Without Shizuku,
  Bedtime mode's grayscale drains it. Samsung's own Sleep mode grayscale is covered only if One
  UI applies it through the same display saturation as Android's modes; that's untested.
- Apps can't set the display's sunlight (high-brightness) mode directly. Peak brightness uses
  HDR headroom instead, which reaches the same panel range on many phones but not all, and the
  phone may dim it again as it warms up.
- Android doesn't say whether the next alarm is a snooze, so the app infers it from timing.
- Without "display over other apps", the light lives in the app's own full-screen window, and
  the Clock app's alarm screen covers it at the wake-up time.

## Install

Every push to `main` builds a debug APK on GitHub Actions and publishes it as the
**Latest debug build** release (also on the `apk` branch). Pull request builds are pushed to
an `apk-pr-<number>` branch. Download
`sunrise-alarm-debug.apk` on your phone and open it; allow installing from that source when asked.

## Build locally

```sh
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. Requires JDK 17 and the Android SDK
(compileSdk 35). Minimum Android version is 8.0 (API 26).
