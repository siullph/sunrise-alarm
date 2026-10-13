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
  The screen light opens full screen (over the lock screen), fades from black to a color you
  pick, and raises the screen brightness along with it. Presets: Sunrise (warm orange),
  White and pure Blue, or any color from the Custom picker. Tap the screen to show
  **Stop sunrise**; Back also stops it.
- **Test this light** runs a 30-second sunrise with that alarm's light and color; **Stop** (in
  the app or the notification) switches the light off.
- After the wake-up time the light stays at full brightness for 30 minutes, then turns off.

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
- A night-light / eye-comfort filter scheduled for that time will tint the screen color.

## Install

Every push to `main` builds a debug APK on GitHub Actions and publishes it as the
**Latest debug build** release (also on the `apk` branch). Download
`sunrise-alarm-debug.apk` on your phone and open it; allow installing from that source when asked.

## Build locally

```sh
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. Requires JDK 17 and the Android SDK
(compileSdk 35). Minimum Android version is 8.0 (API 26).
