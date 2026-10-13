# Sunrise Alarm

An Android app that wakes you with light only: the phone's rear flashlight starts at its
dimmest level and brightens until your wake-up time, like a sunrise. No sound.

## Features

- **Sunrise before your phone alarm.** The app shows the next alarm set in your Clock app.
  Turn on "Sunrise before my phone alarm" and pick a fade duration; the light starts that many
  minutes before the alarm and reaches full brightness when it rings. It follows whichever
  phone alarm is next and re-plans whenever that changes.
- **Sunrise-only alarms.** Create alarms in the app with a wake-up time, a fade duration
  (5 to 60 minutes) and optional repeat days.
- **Test** runs a 30-second sunrise so you can see it work; **Stop** (in the app or the
  notification) switches the light off.
- After the wake-up time the light stays at full brightness for 30 minutes, then turns off.

## Limitations

- Android does not let apps read the full list of alarms in the Clock app. The only alarm it
  exposes is the *next* one (`AlarmManager.getNextAlarmClock`), so the linked sunrise always
  attaches to the next upcoming phone alarm.
- Gradual brightening needs Android 13+ and a flashlight that supports brightness levels.
  On other phones the flashlight turns on at the start of the fade.

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
