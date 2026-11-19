package local.hrbroadcast

import kotlinx.coroutines.flow.MutableStateFlow

/** State shared from the service to the UI (same process). */
object HrState {
    data class Ui(
        val running: Boolean = false,
        val status: String = "Stopped",
        val bpm: Int = 0,
        val connected: Int = 0,
        val subscribed: Int = 0,
        val samples: Int = 0,
        /** Longest interval (ms) between heart rate samples since start. Used to judge the screen-off test. */
        val maxGapMs: Long = 0,
        val lastGapMs: Long = 0,
        /** Longest interval based on measurement timestamps (only used in exercise mode). */
        val dataMaxGapMs: Long = 0,
        /** Sample count and longest interval of the SensorManager path. */
        val sensorSamples: Int = 0,
        val sensorMaxGapMs: Long = 0,
        val availability: String = "-",
    )

    val state = MutableStateFlow(Ui())
}
