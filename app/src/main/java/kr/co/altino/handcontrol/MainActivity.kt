package kr.co.altino.handcontrol

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var tvGesture: TextView
    private lateinit var tvBluetooth: TextView
    private lateinit var tvCommand: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var spinner: Spinner
    private lateinit var switchHandControl: Switch
    private lateinit var bluetooth: AltinoBluetoothManager
    private lateinit var controller: AltinoController
    private var gestureEngine: HandGestureEngine? = null
    private val stabilizer = GestureStabilizer()
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var availableDevices: List<BluetoothDevice> = emptyList()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        continueAfterPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        bluetooth = AltinoBluetoothManager(this) { state ->
            runOnUiThread { tvBluetooth.text = "Bluetooth: $state" }
        }
        controller = AltinoController(bluetooth) { command ->
            runOnUiThread { tvCommand.text = "현재 명령: ${command.label}" }
        }
        controller.start()
        setupUi()
        requestStartupPermissionsAndContinue()
    }

    private fun bindViews() {
        previewView = findViewById(R.id.previewView)
        tvGesture = findViewById(R.id.tvGestureOverlay)
        tvBluetooth = findViewById(R.id.tvBluetoothStatus)
        tvCommand = findViewById(R.id.tvCommand)
        tvSpeed = findViewById(R.id.tvSpeed)
        spinner = findViewById(R.id.spinnerDevices)
        switchHandControl = findViewById(R.id.switchHandControl)
    }

    private fun setupUi() {
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refreshDevices() }
        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            val index = spinner.selectedItemPosition
            if (index in availableDevices.indices) {
                controller.emergencyStop()
                bluetooth.connect(availableDevices[index])
            } else {
                tvBluetooth.text = "Bluetooth: 먼저 기기찾기를 눌러주세요"
            }
        }
        findViewById<Button>(R.id.btnDisconnect).setOnClickListener {
            controller.emergencyStop()
            bluetooth.disconnect()
        }

        val seek = findViewById<SeekBar>(R.id.seekSpeed)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val s = progress.coerceAtLeast(120)
                controller.setSpeed(s)
                tvSpeed.text = s.toString()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        controller.setSpeed(300)

        findViewById<Button>(R.id.btnForward).setOnClickListener { manual(DriveCommand.FORWARD) }
        findViewById<Button>(R.id.btnBackward).setOnClickListener { manual(DriveCommand.BACKWARD) }
        findViewById<Button>(R.id.btnLeft).setOnClickListener { manual(DriveCommand.LEFT) }
        findViewById<Button>(R.id.btnRight).setOnClickListener { manual(DriveCommand.RIGHT) }
        findViewById<Button>(R.id.btnStop).setOnClickListener { controller.emergencyStop() }
        switchHandControl.setOnCheckedChangeListener { _, checked ->
            if (!checked) controller.emergencyStop()
        }
    }

    private fun manual(cmd: DriveCommand) {
        if (switchHandControl.isChecked) switchHandControl.isChecked = false
        controller.setCommand(cmd)
    }

    private fun requestStartupPermissionsAndContinue() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.CAMERA
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.BLUETOOTH_CONNECT
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.BLUETOOTH_SCAN
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.ACCESS_FINE_LOCATION
            }
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            continueAfterPermissions()
        }
    }

    private fun continueAfterPermissions() {
        val cameraOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (cameraOk) startCamera()
        else tvGesture.text = "카메라 권한이 필요합니다"

        if (bluetooth.hasConnectPermission() && bluetooth.hasScanPermission()) {
            refreshDevices()
        } else {
            tvBluetooth.text = "Bluetooth: 주변기기 권한을 허용해주세요"
        }
    }

    private fun refreshDevices() {
        bluetooth.discoverAltinoDevices { devices ->
            runOnUiThread {
                availableDevices = devices
                val names = devices.map { device ->
                    val typeLabel = when (device.type) {
                        BluetoothDevice.DEVICE_TYPE_LE -> "BLE"
                        BluetoothDevice.DEVICE_TYPE_DUAL -> "BLE/Classic"
                        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic"
                        else -> "Bluetooth"
                    }
                    val name = runCatching { device.name }.getOrNull() ?: "ALTINO LITE"
                    "$name  [$typeLabel]"
                }
                spinner.adapter = ArrayAdapter(
                    this,
                    android.R.layout.simple_spinner_dropdown_item,
                    if (names.isEmpty()) listOf("알티노 라이트 검색 중…") else names
                )
            }
        }
    }

    private fun startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (gestureEngine == null) {
            gestureEngine = try {
                HandGestureEngine(this).also { tvGesture.text = "손을 보여주세요" }
            } catch (t: Throwable) {
                tvGesture.text = "손 인식 모델 오류: ${t.message ?: "초기화 실패"}"
                null
            }
        }

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            analysis.setAnalyzer(cameraExecutor) { image ->
                try {
                    gestureEngine?.analyze(image)?.let { handleGesture(it) }
                } catch (t: Throwable) {
                    runOnUiThread {
                        tvGesture.text = "손 인식 오류: ${t.message ?: t.javaClass.simpleName}"
                    }
                    controller.emergencyStop()
                } finally {
                    image.close()
                }
            }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            } catch (_: Throwable) {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleGesture(raw: DriveCommand) {
        val overlay = when (raw) {
            DriveCommand.STOP -> "✊ 정지"
            DriveCommand.FORWARD -> "🖐 전진"
            DriveCommand.BACKWARD -> "☝ 후진"
            DriveCommand.LEFT -> "✌ 좌회전"
            DriveCommand.RIGHT -> "3 손가락 · 우회전"
            DriveCommand.UNKNOWN -> "손을 찾는 중…"
        }
        runOnUiThread { tvGesture.text = overlay }
        if (!switchHandControl.isChecked) return
        stabilizer.update(raw, SystemClock.uptimeMillis())?.let { controller.setCommand(it) }
    }

    override fun onPause() {
        super.onPause()
        controller.emergencyStop()
    }

    override fun onDestroy() {
        controller.stop()
        bluetooth.disconnect()
        gestureEngine?.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
