# HR Broadcast

A small Wear OS app that broadcasts the watch's heart rate as a standard BLE heart rate monitor
(service 0x180D / characteristic 0x2A37) and keeps sending while the watch screen is off.

Written for the Galaxy Watch, where watch-side heart rate apps often stop sending when the screen turns off
(the BLE connection stays up, but the readings stop until the screen is turned on again).
Any app that can read a standard BLE heart rate monitor can receive it, for example
[HR Relay](https://github.com/Dimlos-jp/HrRelay) (relays the heart rate to MacroDroid), the MacroDroid BLE Tasker Plugin, or nRF Connect.

## Build
Open the folder in Android Studio and run (or Build > Build APK).
The Gradle wrapper jar is not included. If sync fails, run `gradle wrapper` once.

A debug-signed APK is also available under Releases.

## Install
Enable developer options and Wi-Fi debugging on the watch, then:

```
adb connect <watch-ip>:<port>
adb install app-debug.apk
```

## Usage
1. Open the app on the watch and tap Start. Grant the permissions: body sensors, Bluetooth, notifications, and then sensor access **"Allow all the time"**. If you deny the last one, readings may stop when the screen turns off.
2. On the receiving side, connect to the watch (it advertises under the watch's Bluetooth name) and subscribe to the Heart Rate Measurement characteristic.
3. Tap Stop to end broadcasting.

The screen shows the number of samples, the last and the longest interval between samples, and the number of connected / subscribed clients.
To check that it survives screen-off: tap Start, keep the watch on your wrist, let the screen turn off, wait a few minutes, then turn the screen on and read "Max interval". A few seconds means readings kept coming; tens of seconds or more means they stopped.

## How it works
- Reads the wake-up variant of `Sensor.TYPE_HEART_RATE` through `SensorManager`.
- Runs in a foreground service of type `health` and holds a `PARTIAL_WAKE_LOCK`.
- Needs `BODY_SENSORS_BACKGROUND`, so sensor access must be set to "Allow all the time".
- Exposes a `BluetoothGattServer` with the Heart Rate Service and sends a notification for each reading to every subscribed client.

This follows [Samsung's article on continuous heart rate tracking with the screen off](https://developer.samsung.com/galaxy-watch/blog/en/2026/04/23/continuous-heart-rate-tracking-on-galaxy-watch-even-with-the-screen-off).
The Samsung Health Sensor SDK and the Health Platform developer mode are not needed.

## Status

Tested:
- Galaxy Watch Ultra, on one Android phone: readings kept arriving with the screen off for 26+ minutes (longest interval 2 seconds)
- Receiving the heart rate in HR Relay and a MacroDroid macro via the "Intent Received" trigger

Not tested yet:
- Other Galaxy Watch models and other Wear OS watches
- Long-term stability (hours) and battery consumption

This is an early version. Feedback and test reports are welcome.

## Known issues

- The watch must be worn. The sensor reports 0 when it is not, and the app ignores it.
- The heart rate sensor reports on change, so intervals of 1-3 seconds are normal.
- Keeping the sensor active uses battery. Stop the app when you do not need it.
- If the watch is set to pause unused apps or to restrict this app's battery usage, the system may still stop it. Set the app's battery usage to unrestricted if you see problems.
- Do not run another app that broadcasts the same heart rate service on the same watch at the same time. Receivers see both and may connect to the wrong one.

## Notes
- `USE_EXERCISE` in `HrService.kt` enables an experimental mode that also starts a Health Services exercise session. It is off by default. In my testing an exercise session alone did not prevent the screen-off gaps, and Wear OS allows only one exercise session at a time.
- The heart rate is sent in the standard Heart Rate Measurement format (8-bit value).
- Advertising is restarted after a client disconnects.
