package local.hrbroadcast

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseTrackedStatus
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Keeps heart rate readings flowing while the screen is off:
 *  1. SensorManager heart rate sensor (wake-up variant) with BODY_SENSORS_BACKGROUND granted
 *  2. Foreground service of type "health" + PARTIAL_WAKE_LOCK
 *  3. Standard Heart Rate Service (0x180D / 0x2A37) exposed through a GATT server
 */
@SuppressLint("MissingPermission")
class HrService : Service() {

    companion object {
        const val ACTION_START = "local.hrbroadcast.START"
        const val ACTION_STOP = "local.hrbroadcast.STOP"
        private const val TAG = "HrService"
        private const val CHANNEL_ID = "hr_broadcast"
        private const val NOTIF_ID = 1

        /** If true, also starts a Health Services exercise session. Off by default (SensorManager only). */
        private const val USE_EXERCISE = false

        private val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private val BODY_SENSOR_LOCATION: UUID = UUID.fromString("00002a38-0000-1000-8000-00805f9b34fb")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()

    private var started = false
    private var stopping = false
    private var wakeLock: PowerManager.WakeLock? = null

    // --- BLE ---
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var hrChar: BluetoothGattCharacteristic? = null
    private val connected = mutableSetOf<BluetoothDevice>()
    private val subscribed = mutableSetOf<BluetoothDevice>()
    private var advertising = false

    // --- Exercise ---
    private val exerciseClient by lazy { HealthServices.getClient(this).exerciseClient }
    private var exerciseStarted = false
    private var lastSampleAt = 0L
    private var lastDataTimeMs = 0L

    // --- SensorManager (primary heart rate source) ---
    private var lastSensorAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            startForegroundNow()
            scope.launch { startAll() }
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ Start

    private fun startForegroundNow() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "HR Broadcast", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Broadcasting heart rate")
            .setContentText("Acting as a BLE heart rate monitor")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setContentIntent(open)
            .build()
        ServiceCompat.startForeground(
            this, NOTIF_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        )
    }

    private suspend fun startAll() {
        try {
            HrState.state.update {
                HrState.Ui(running = true, status = "Starting...")
            }
            acquireWakeLock()
            startGatt() // advertising starts in onServiceAdded
            if (USE_EXERCISE) startExercise()
            startSensor()
            HrState.state.update { it.copy(status = "Broadcasting (waiting for connection)") }
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            HrState.state.update { it.copy(status = "Error: ${e.message}") }
            shutdown(keepStatus = true)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hrbroadcast:svc").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    // ------------------------------------------------------------------ Heart rate via ExerciseClient (optional)

    private val updateCallback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            if (update.exerciseStateInfo.state.isEnded) {
                // Another app took over, or the session was ended
                HrState.state.update { it.copy(status = "Exercise session ended") }
                exerciseStarted = false
                shutdown(keepStatus = true)
                return
            }
            val points = update.latestMetrics.getData(DataType.HEART_RATE_BPM)
            if (points.isEmpty()) return
            // Gap based on measurement timestamps (shows whether samples were measured but delivered late)
            var dataGapMax = 0L
            for (p in points) {
                val t = p.timeDurationFromBoot.toMillis()
                if (lastDataTimeMs != 0L) dataGapMax = maxOf(dataGapMax, t - lastDataTimeMs)
                lastDataTimeMs = t
            }
            val sample = points.last()
            val bpm = sample.value.toInt()
            if (bpm <= 0) return

            val now = SystemClock.elapsedRealtime()
            val gap = if (lastSampleAt == 0L) 0L else now - lastSampleAt
            lastSampleAt = now

            HrState.state.update {
                it.copy(
                    bpm = bpm,
                    samples = it.samples + 1,
                    lastGapMs = gap,
                    maxGapMs = maxOf(it.maxGapMs, gap),
                    dataMaxGapMs = maxOf(it.dataMaxGapMs, dataGapMax),
                )
            }
            notifyHr(bpm)
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}
        override fun onRegistered() {}
        override fun onRegistrationFailed(throwable: Throwable) {
            Log.e(TAG, "registration failed", throwable)
            HrState.state.update { it.copy(status = "Registration failed: ${throwable.message}") }
        }
        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {
            Log.d(TAG, "availability $dataType = $availability")
            HrState.state.update { it.copy(availability = availability.toString()) }
        }
    }

    private suspend fun startExercise() {
        val caps = exerciseClient.getCapabilitiesAsync().await()
        val type = listOf(ExerciseType.WORKOUT, ExerciseType.WALKING, ExerciseType.RUNNING)
            .firstOrNull { t ->
                t in caps.supportedExerciseTypes &&
                    DataType.HEART_RATE_BPM in caps.getExerciseTypeCapabilities(t).supportedDataTypes
            } ?: error("No exercise type supports heart rate")

        val info = exerciseClient.getCurrentExerciseInfoAsync().await()
        if (info.exerciseTrackedStatus == ExerciseTrackedStatus.OTHER_APP_IN_PROGRESS) {
            error("Another app has an exercise in progress. End it first.")
        }
        // End any session left over from a previous crash so we can start cleanly
        // (fails harmlessly if nothing is running)
        runCatching { exerciseClient.endExerciseAsync().await() }

        val config = ExerciseConfig.builder(type)
            .setDataTypes(setOf(DataType.HEART_RATE_BPM))
            .setIsAutoPauseAndResumeEnabled(false)
            .setIsGpsEnabled(false)
            .build()

        exerciseClient.setUpdateCallback(updateCallback)
        exerciseClient.startExerciseAsync(config).await()
        exerciseStarted = true
        Log.d(TAG, "exercise started: $type")
    }

    // ------------------------------------------------------------------ SensorManager

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.values.isEmpty()) return
            val bpm = event.values[0].toInt()
            if (bpm <= 0) return // 0 is reported when the watch is not worn
            val now = SystemClock.elapsedRealtime()
            val gap = if (lastSensorAt == 0L) 0L else now - lastSensorAt
            lastSensorAt = now
            HrState.state.update {
                it.copy(
                    bpm = bpm,
                    samples = it.samples + 1,
                    lastGapMs = gap,
                    maxGapMs = maxOf(it.maxGapMs, gap),
                    sensorSamples = it.sensorSamples + 1,
                    sensorMaxGapMs = maxOf(it.sensorMaxGapMs, gap),
                )
            }
            notifyHr(bpm)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun startSensor() {
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        // Wake-up variant of the heart rate sensor (as in Samsung's article), falling back to the normal one
        val sensor = sm.getDefaultSensor(Sensor.TYPE_HEART_RATE, true)
            ?: sm.getDefaultSensor(Sensor.TYPE_HEART_RATE)
            ?: error("No heart rate sensor")
        lastSensorAt = 0L
        val ok = sm.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_UI)
        if (!ok) error("Could not register the heart rate sensor")
    }

    private fun stopSensor() {
        runCatching {
            (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(sensorListener)
        }
    }

    // ------------------------------------------------------------------ BLE (GATT server)

    private fun startGatt() {
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bm.adapter
        if (adapter == null || !adapter.isEnabled) error("Bluetooth is off")
        advertiser = adapter.bluetoothLeAdvertiser ?: error("BLE advertising is not supported")

        val server = bm.openGattServer(this, gattCallback) ?: error("Cannot open GATT server")
        gattServer = server

        val hr = BluetoothGattCharacteristic(
            HR_MEASUREMENT,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                )
            )
        }
        hrChar = hr

        val location = BluetoothGattCharacteristic(
            BODY_SENSOR_LOCATION,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val service = BluetoothGattService(HR_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(hr)
            addCharacteristic(location)
        }
        server.addService(service)
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS && service.uuid == HR_SERVICE) {
                startAdvertising()
            } else {
                HrState.state.update { it.copy(status = "Service registration failed ($status)") }
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                synchronized(lock) { connected.add(device) }
            } else {
                synchronized(lock) {
                    connected.remove(device)
                    subscribed.remove(device)
                }
                // Some devices stop advertising once connected, so restart on disconnect
                restartAdvertising()
            }
            publishCounts()
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            var ok = false
            if (descriptor.uuid == CCCD && value != null && value.size == 2) {
                val enable = value[0].toInt() == 0x01 && value[1].toInt() == 0x00
                synchronized(lock) { if (enable) subscribed.add(device) else subscribed.remove(device) }
                ok = true
                publishCounts()
            }
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device, requestId,
                    if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                    offset, value
                )
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor
        ) {
            val on = synchronized(lock) { device in subscribed }
            val v = if (on) byteArrayOf(0x01, 0x00) else byteArrayOf(0x00, 0x00)
            respondRead(device, requestId, offset, v)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == BODY_SENSOR_LOCATION) {
                respondRead(device, requestId, offset, byteArrayOf(0x02)) // 2 = Wrist
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, 0, null)
            }
        }
    }

    private fun respondRead(device: BluetoothDevice, requestId: Int, offset: Int, full: ByteArray) {
        if (offset > full.size) {
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
        } else {
            gattServer?.sendResponse(
                device, requestId, BluetoothGatt.GATT_SUCCESS, offset, full.copyOfRange(offset, full.size)
            )
        }
    }

    private val advCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            advertising = true
            Log.d(TAG, "advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) {
                advertising = true
                return
            }
            advertising = false
            Log.e(TAG, "advertising failed: $errorCode")
            HrState.state.update { it.copy(status = "Advertising failed ($errorCode)") }
        }
    }

    private fun startAdvertising() {
        val adv = advertiser ?: return
        if (advertising) return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(HR_SERVICE))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        adv.startAdvertising(settings, data, scanResponse, advCallback)
    }

    private fun restartAdvertising() {
        if (stopping) return
        runCatching { advertiser?.stopAdvertising(advCallback) }
        advertising = false
        startAdvertising()
    }

    private fun publishCounts() {
        val (c, s) = synchronized(lock) { connected.size to subscribed.size }
        HrState.state.update {
            it.copy(
                connected = c,
                subscribed = s,
                status = if (it.running) {
                    if (s > 0) "Broadcasting" else "Broadcasting (waiting for connection)"
                } else it.status
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun notifyHr(bpm: Int) {
        val ch = hrChar ?: return
        val server = gattServer ?: return
        // Flags=0x00: heart rate value is uint8
        val value = byteArrayOf(0x00, bpm.coerceIn(0, 255).toByte())
        val targets = synchronized(lock) { subscribed.toList() }
        for (d in targets) {
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    server.notifyCharacteristicChanged(d, ch, false, value)
                } else {
                    ch.value = value
                    server.notifyCharacteristicChanged(d, ch, false)
                }
            } catch (e: Exception) {
                Log.w(TAG, "notify failed", e)
            }
        }
    }

    // ------------------------------------------------------------------ Stop

    private fun shutdown(keepStatus: Boolean = false) {
        if (stopping) return
        stopping = true
        scope.launch(NonCancellable) {
            if (exerciseStarted) {
                runCatching { exerciseClient.endExerciseAsync().await() }
                exerciseStarted = false
            }
            runCatching { exerciseClient.clearUpdateCallbackAsync(updateCallback).await() }
            stopSensor()
            runCatching { advertiser?.stopAdvertising(advCallback) }
            advertising = false
            runCatching { gattServer?.close() }
            gattServer = null
            synchronized(lock) {
                connected.clear()
                subscribed.clear()
            }
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null

            val keep = HrState.state.value.status
            HrState.state.update {
                it.copy(
                    running = false,
                    connected = 0,
                    subscribed = 0,
                    status = if (keepStatus) keep else "Stopped"
                )
            }
            ServiceCompat.stopForeground(this@HrService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cleanup for abnormal termination (normally shutdown() has already done this)
        stopSensor()
        runCatching { advertiser?.stopAdvertising(advCallback) }
        runCatching { gattServer?.close() }
        wakeLock?.let { if (it.isHeld) it.release() }
        HrState.state.update { it.copy(running = false) }
        scope.cancel()
    }
}
