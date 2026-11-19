package local.hrbroadcast

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var bpmView: TextView
    private lateinit var statusView: TextView
    private lateinit var statsView: TextView
    private lateinit var button: Button

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { !it }.keys
            if (denied.isEmpty()) {
                startOrAskBackground()
            } else {
                HrState.state.update {
                    it.copy(status = "Permission needed: " + denied.joinToString { p -> p.substringAfterLast('.') })
                }
            }
        }

    // "Allow all the time" sensor access, needed to keep reading while the screen is off (Wear OS 4 / API 33+)
    private val bgPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                HrState.state.update {
                    it.copy(status = "Sensor access is not \"all the time\" (may stop when the screen is off)")
                }
            }
            startHr()
        }

    private fun startOrAskBackground() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS_BACKGROUND)
            != PackageManager.PERMISSION_GRANTED
        ) {
            bgPermLauncher.launch(Manifest.permission.BODY_SENSORS_BACKGROUND)
        } else {
            startHr()
        }
    }

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION,
        )
        if (Build.VERSION.SDK_INT >= 31) {
            list += Manifest.permission.BLUETOOTH_ADVERTISE
            list += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 33) {
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        return list.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        button.setOnClickListener {
            if (HrState.state.value.running) {
                startService(Intent(this, HrService::class.java).setAction(HrService.ACTION_STOP))
            } else {
                val missing = requiredPermissions().filter {
                    ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) startOrAskBackground() else permLauncher.launch(missing.toTypedArray())
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                HrState.state.collect { render(it) }
            }
        }
    }

    private fun startHr() {
        ContextCompat.startForegroundService(
            this, Intent(this, HrService::class.java).setAction(HrService.ACTION_START)
        )
    }

    private fun render(s: HrState.Ui) {
        bpmView.text = if (s.bpm > 0) "${s.bpm}" else "--"
        statusView.text = s.status
        statsView.text = buildString {
            append("Samples: ${s.samples}\n")
            append("Last interval: ${"%.1f".format(s.lastGapMs / 1000.0)} s\n")
            append("Max interval: ${"%.1f".format(s.maxGapMs / 1000.0)} s\n")
            append("Connected ${s.connected} / Subscribed ${s.subscribed}")
        }
        button.text = if (s.running) "Stop" else "Start"
        // Intentionally does not keep the screen on, so screen-off behavior can be tested.
    }

    private fun buildUi() {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // Generous padding so content is not clipped on round screens
            setPadding(px(28), px(36), px(28), px(36))
        }

        bpmView = TextView(this).apply {
            textSize = 44f
            gravity = Gravity.CENTER
        }
        statusView = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
        }
        statsView = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, px(8), 0, px(8))
        }
        button = Button(this).apply { text = "Start" }

        col.addView(bpmView)
        col.addView(statusView)
        col.addView(statsView)
        col.addView(
            button,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val scroll = ScrollView(this).apply { addView(col) }
        setContentView(scroll)
        render(HrState.state.value)
    }
}
