# HR Broadcast (Wear OS)

Broadcasts the heart rate from a Galaxy Watch as a standard **BLE heart rate monitor** (Heart Rate Service `0x180D` / Heart Rate Measurement `0x2A37`), and keeps doing so **while the watch screen is off**.

Any app that can read a normal BLE heart rate strap can receive it: for example [HR Relay](https://github.com/Dimlos-jp/HrRelay) (forwards the heart rate to MacroDroid), the MacroDroid BLE Tasker Plugin, or nRF Connect.

## Why

Simple "heart rate over Bluetooth" apps for Wear OS often stop sending as soon as the watch screen turns off: the BLE connection stays up, but the heart rate readings stop until the screen is turned on again. This app keeps the readings coming.

## How it works

The approach follows [Samsung's article on continuous heart rate tracking with the screen off](https://developer.samsung.com/galaxy-watch/blog/en/2026/04/23/continuous-heart-rate-tracking-on-galaxy-watch-even-with-the-screen-off):

- Reads the **wake-up variant** of `Sensor.TYPE_HEART_RATE` through `SensorManager`.
- Runs in a foreground service of type `health`, holding a `PARTIAL_WAKE_LOCK`.
- Needs `BODY_SENSORS_BACKGROUND`: sensor access must be set to **"Allow all the time"**.
- Exposes a `BluetoothGattServer` with the Heart Rate Service and sends a notification for each reading to every subscribed client.

No Samsung Health Sensor SDK and no Health Platform developer mode are needed.

## Tested on

- Galaxy Watch Ultra: kept sending for 26+ minutes with the screen off (longest gap: 2 seconds).

Other Galaxy Watch models and other Wear OS watches are untested. Reports are welcome.

## Build

1. Open the `HrBroadcast` folder in Android Studio.
2. Let Gradle sync (the wrapper is set to Gradle 8.9).
3. Build the `app` module. The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

## Install on the watch

Enable developer options and Wi-Fi debugging on the watch, then:

```
adb connect <watch-ip>:<port>
adb install app-debug.apk
```

## Usage

1. Open the app on the watch and tap **Start**.
2. Grant the permissions: body sensors, Bluetooth, notifications, and then **sensor access "Allow all the time"**. If you deny the last one, readings may stop when the screen turns off.
3. From the receiving side, connect to the watch (it advertises under the watch's Bluetooth name) and subscribe to the Heart Rate Measurement characteristic.
4. Tap **Stop** to end broadcasting.

The screen shows the number of samples, the last and the longest interval between samples, and the number of connected / subscribed clients.

### Checking that it survives screen-off

1. Tap **Start** and confirm the receiver gets heart rate values.
2. Keep the watch on your wrist, let the screen turn off, and wait a few minutes.
3. Turn the screen on and read **Max interval**.
   - A few seconds: readings kept coming.
   - Tens of seconds or more: readings stopped while the screen was off.

Android's heart rate sensor reports on change, so intervals of 1-3 seconds are normal.

## Known limitations

- The watch must be worn: the sensor reports 0 (and the app ignores it) when it is not.
- Keeping the sensor active costs battery. Stop the app when you do not need it.
- If the watch is configured to pause unused apps or to restrict this app's battery usage, the system may still stop it. Set the app's battery usage to unrestricted if you see problems.
- Do not run another app that broadcasts the same heart rate service on the same watch at the same time: receivers will see both and may connect to the wrong one.
- `USE_EXERCISE` in `HrService.kt` enables an experimental mode that also starts a Health Services exercise session. It is off by default. In my testing, an exercise session alone did not prevent the screen-off gaps, and Wear OS allows only one exercise session at a time.

## License

MIT. See `LICENSE`.
