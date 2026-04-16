package com.example.myapplication

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.preference.PreferenceManager
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.util.Locale

private const val DEFAULT_USER_HEIGHT_M = 1.72

enum class TrackingState { IDLE, TRACKING, PAUSED }
enum class MapMode { HD_VECTOR, SATELLITE }

data class PdrUiState(
    val trackingState: TrackingState,
    val statusText: String,
    val elapsedSeconds: Long,
    val totalSteps: Int,
    val systemStepCount: Int,
    val cadenceSpm: Int,
    val lastStepLengthM: Float,
    val totalDistanceM: Float,
    val headingDeg: Float,
    val gpsAccuracyM: Float,
    val gpsSpeedMps: Float,
    val imuSampleCount: Long,
    val imuRateHz: Float,
    val currentLogFileName: String,
    val pedometerEnabled: Boolean,
    val stepCounterSupported: Boolean,
    val mapMode: MapMode,
    val ahrsMode: AhrsMode,
    val realtimeState: PdrRealtimeState
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val osmConfig = Configuration.getInstance()
        osmConfig.load(
            applicationContext,
            PreferenceManager.getDefaultSharedPreferences(applicationContext)
        )
        osmConfig.userAgentValue = applicationContext.packageName
        val basePath = File(filesDir, "osmdroid")
        if (!basePath.exists()) basePath.mkdirs()
        osmConfig.osmdroidBasePath = basePath
        val tileCache = File(basePath, "tiles")
        if (!tileCache.exists()) tileCache.mkdirs()
        osmConfig.osmdroidTileCache = tileCache
        osmConfig.tileDownloadThreads = 8
        osmConfig.tileDownloadMaxQueueSize = 40
        osmConfig.cacheMapTileCount = 160
        setContent { PdrTheme { PdrRealtimeScreen() } }
    }
}

@Composable
fun PdrRealtimeScreen() {
    val context = LocalContext.current
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val pdrEngine = remember { NineAxisPdrEngine(DEFAULT_USER_HEIGHT_M) }
    val imuCsvLogger = remember { ImuCsvLogger(context) }
    val stepCounterSensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) }
    val hdVectorTileSource = remember { createGaodeTileSource(false) }
    val satelliteTileSource = remember { createGaodeTileSource(true) }

    var trackingState by remember { mutableStateOf(TrackingState.IDLE) }
    var statusText by remember { mutableStateOf("就绪，等待开始实时 IMU 采集") }
    var followMode by remember { mutableStateOf(true) }
    var topExpanded by remember { mutableStateOf(true) }
    var bottomExpanded by remember { mutableStateOf(true) }
    var pedometerEnabled by remember { mutableStateOf(stepCounterSensor != null) }
    var mapMode by remember { mutableStateOf(MapMode.HD_VECTOR) }
    var selectedAhrsMode by remember { mutableStateOf(AhrsMode.NINE_AXIS) }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    var totalSteps by remember { mutableIntStateOf(0) }
    var cadenceSpm by remember { mutableIntStateOf(0) }
    var lastStepLength by remember { mutableFloatStateOf(0f) }
    var totalDistanceM by remember { mutableFloatStateOf(0f) }
    var headingDeg by remember { mutableFloatStateOf(0f) }
    var gpsAccuracyM by remember { mutableFloatStateOf(Float.NaN) }
    var gpsSpeedMps by remember { mutableFloatStateOf(0f) }
    var imuSampleCount by remember { mutableLongStateOf(0L) }
    var imuRateHz by remember { mutableFloatStateOf(0f) }
    var currentLogFile by remember { mutableStateOf("") }
    var waitingStartFix by remember { mutableStateOf(false) }
    var systemStepCount by remember { mutableIntStateOf(0) }
    var stepCounterBase by remember { mutableStateOf<Float?>(null) }
    var realtimeState by remember { mutableStateOf(emptyRealtimeState(selectedAhrsMode)) }
    val pdrPoints = remember { mutableStateListOf<GeoPoint>() }

    fun syncRealtimeState(state: PdrRealtimeState) {
        realtimeState = state
        totalSteps = state.totalSteps
        cadenceSpm = state.cadenceSpm
        lastStepLength = state.lastStepLengthM.toFloat()
        totalDistanceM = state.totalDistanceM.toFloat()
        headingDeg = state.headingDeg
    }

    fun resetSession() {
        elapsedSeconds = 0L
        totalSteps = 0
        cadenceSpm = 0
        lastStepLength = 0f
        totalDistanceM = 0f
        headingDeg = 0f
        gpsAccuracyM = Float.NaN
        gpsSpeedMps = 0f
        imuSampleCount = 0L
        imuRateHz = 0f
        waitingStartFix = true
        systemStepCount = 0
        stepCounterBase = null
        currentLogFile = ""
        pdrPoints.clear()
        pdrEngine.clear()
        realtimeState = emptyRealtimeState(selectedAhrsMode)
    }

    fun stopSession(updateStatus: Boolean = true) {
        imuCsvLogger.stopSession()
        pdrEngine.clear()
        trackingState = TrackingState.IDLE
        waitingStartFix = false
        realtimeState = emptyRealtimeState(selectedAhrsMode)
        if (updateStatus) {
            statusText = "已停止，日志已保存"
        }
    }

    fun clearLocalData() {
        stopSession(false)
        val deleted = clearAppLocalData(context)
        resetSession()
        waitingStartFix = false
        statusText = "已清空本地数据，共删除 $deleted 项"
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            resetSession()
            trackingState = TrackingState.TRACKING
            currentLogFile = imuCsvLogger.startSession()
            statusText = "已开始，等待起点定位"
        } else {
            statusText = "未授予定位置权限"
        }
    }

    fun startSession() {
        if (!hasLocationPermission(context)) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }
        resetSession()
        trackingState = TrackingState.TRACKING
        currentLogFile = imuCsvLogger.startSession()
        statusText = "已开始，等待起点定位"
    }

    LaunchedEffect(trackingState) {
        while (trackingState == TrackingState.TRACKING) {
            delay(1000L)
            elapsedSeconds += 1L
        }
    }

    DisposableEffect(trackingState, selectedAhrsMode) {
        if (trackingState != TrackingState.TRACKING || !hasLocationPermission(context)) {
            onDispose { }
        } else {
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                900L
            )
                .setMinUpdateDistanceMeters(0f)
                .setWaitForAccurateLocation(false)
                .build()
            val magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val location = result.lastLocation ?: return
                    gpsAccuracyM = location.accuracy
                    gpsSpeedMps = location.speed
                    if (trackingState != TrackingState.TRACKING) return

                    if (!pdrEngine.isStarted() && waitingStartFix) {
                        val runtimeAhrsMode =
                            if (selectedAhrsMode == AhrsMode.NINE_AXIS && magneticSensor == null) {
                                AhrsMode.SIX_AXIS
                            } else {
                                selectedAhrsMode
                            }
                        if (runtimeAhrsMode != selectedAhrsMode) {
                            selectedAhrsMode = runtimeAhrsMode
                            statusText = "设备无磁力计，已切换为六轴姿态解算"
                        }
                        pdrEngine.reset(
                            startLat = location.latitude,
                            startLon = location.longitude,
                            initialHeadingDeg = if (location.hasBearing()) location.bearing else null,
                            ahrsMode = runtimeAhrsMode,
                            userHeightM = DEFAULT_USER_HEIGHT_M
                        )
                        pdrPoints.add(wgsToGcj(location.latitude, location.longitude))
                        syncRealtimeState(pdrEngine.snapshot())
                        waitingStartFix = false
                        statusText = "起点已锁定，开始实时航迹推算"
                    } else if (pdrEngine.isStarted()) {
                        pdrEngine.correctWithGps(
                            gpsLat = location.latitude,
                            gpsLon = location.longitude,
                            accuracyM = location.accuracy
                        )
                        if (location.hasBearing()) {
                            pdrEngine.updateHeadingAssist(
                                gpsBearingDeg = location.bearing,
                                speedMps = location.speed,
                                accuracyM = location.accuracy
                            )
                        }
                    }
                }
            }
            if (!hasLocationPermission(context)) {
                statusText = "定位权限不可用，无法启动 GPS 更新"
                trackingState = TrackingState.IDLE
                onDispose { }
            } else {
                try {
                    fusedLocationClient.requestLocationUpdates(
                        locationRequest,
                        callback,
                        Looper.getMainLooper()
                    )
                } catch (_: SecurityException) {
                    statusText = "定位权限校验失败，请重新授权"
                    trackingState = TrackingState.IDLE
                }
                onDispose { fusedLocationClient.removeLocationUpdates(callback) }
            }
        }
    }

    DisposableEffect(trackingState, pedometerEnabled) {
        if (trackingState != TrackingState.TRACKING || !pedometerEnabled || stepCounterSensor == null) {
            onDispose { }
        } else {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val counter = event.values.firstOrNull() ?: return
                    if (stepCounterBase == null) stepCounterBase = counter
                    systemStepCount = (counter - (stepCounterBase ?: counter))
                        .toInt()
                        .coerceAtLeast(0)
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            sensorManager.registerListener(listener, stepCounterSensor, SensorManager.SENSOR_DELAY_UI)
            onDispose { sensorManager.unregisterListener(listener) }
        }
    }

    DisposableEffect(trackingState) {
        if (trackingState != TrackingState.TRACKING) {
            onDispose { }
        } else {
            val accSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            val magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            if (accSensor == null || gyroSensor == null) {
                statusText = "设备缺少基础 IMU 传感器，无法运行 PDR"
                trackingState = TrackingState.IDLE
                onDispose { }
            } else {
                val gyro = FloatArray(3)
                val mag = FloatArray(3)
                var hasGyro = false
                var hasMag = false
                var rateWindowStartMs = System.currentTimeMillis()
                var rateWindowCount = 0
                var lastUiRefreshMs = 0L

                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        if (trackingState != TrackingState.TRACKING) return
                        when (event.sensor.type) {
                            Sensor.TYPE_GYROSCOPE -> {
                                System.arraycopy(event.values, 0, gyro, 0, 3)
                                hasGyro = true
                            }

                            Sensor.TYPE_MAGNETIC_FIELD -> {
                                System.arraycopy(event.values, 0, mag, 0, 3)
                                hasMag = true
                            }

                            Sensor.TYPE_ACCELEROMETER -> {
                                if (!hasGyro || !pdrEngine.isStarted()) return
                                val sample = NineAxisSample(
                                    ax = event.values[0],
                                    ay = event.values[1],
                                    az = event.values[2],
                                    gx = gyro[0],
                                    gy = gyro[1],
                                    gz = gyro[2],
                                    mx = if (hasMag) mag[0] else 0f,
                                    my = if (hasMag) mag[1] else 0f,
                                    mz = if (hasMag) mag[2] else 0f,
                                    timestampNs = event.timestamp
                                )
                                val processResult = pdrEngine.processSample(sample) ?: return
                                imuCsvLogger.logSample(
                                    sample = sample,
                                    snapshot = processResult.state,
                                    event = logEventLabel(processResult)
                                )
                                imuSampleCount += 1L
                                rateWindowCount += 1

                                val nowMs = System.currentTimeMillis()
                                if (nowMs - rateWindowStartMs >= 1000L) {
                                    imuRateHz = rateWindowCount * 1000f /
                                        (nowMs - rateWindowStartMs).coerceAtLeast(1L)
                                    rateWindowCount = 0
                                    rateWindowStartMs = nowMs
                                }

                                if (processResult.step != null) {
                                    syncRealtimeState(processResult.state)
                                    pdrPoints.add(
                                        wgsToGcj(
                                            processResult.step.lat,
                                            processResult.step.lon
                                        )
                                    )
                                    if (processResult.step.isInterrupted) {
                                        statusText = "检测到停走中断，已执行磁航向修正"
                                    }
                                    lastUiRefreshMs = nowMs
                                } else if (nowMs - lastUiRefreshMs >= 150L) {
                                    syncRealtimeState(processResult.state)
                                    lastUiRefreshMs = nowMs
                                }
                            }
                        }
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }

                sensorManager.registerListener(listener, accSensor, SensorManager.SENSOR_DELAY_GAME)
                sensorManager.registerListener(listener, gyroSensor, SensorManager.SENSOR_DELAY_GAME)
                magSensor?.let {
                    sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
                }
                onDispose { sensorManager.unregisterListener(listener) }
            }
        }
    }

    val uiState = PdrUiState(
        trackingState = trackingState,
        statusText = statusText,
        elapsedSeconds = elapsedSeconds,
        totalSteps = totalSteps,
        systemStepCount = systemStepCount,
        cadenceSpm = cadenceSpm,
        lastStepLengthM = lastStepLength,
        totalDistanceM = totalDistanceM,
        headingDeg = headingDeg,
        gpsAccuracyM = gpsAccuracyM,
        gpsSpeedMps = gpsSpeedMps,
        imuSampleCount = imuSampleCount,
        imuRateHz = imuRateHz,
        currentLogFileName = currentLogFile.takeIf { it.isNotBlank() }?.let { File(it).name }.orEmpty(),
        pedometerEnabled = pedometerEnabled,
        stepCounterSupported = stepCounterSensor != null,
        mapMode = mapMode,
        ahrsMode = realtimeState.ahrsMode,
        realtimeState = realtimeState
    )

    PdrScreenContent(
        uiState = uiState,
        topExpanded = topExpanded,
        bottomExpanded = bottomExpanded,
        onToggleTop = { topExpanded = !topExpanded },
        onToggleBottom = { bottomExpanded = !bottomExpanded },
        onMapModeChange = { mapMode = it },
        onAhrsModeChange = {
            if (trackingState == TrackingState.IDLE) {
                selectedAhrsMode = it
                realtimeState = emptyRealtimeState(it)
            }
        },
        onPedometerToggle = { pedometerEnabled = it },
        onPrimaryAction = {
            when (trackingState) {
                TrackingState.IDLE -> startSession()
                TrackingState.TRACKING -> {
                    trackingState = TrackingState.PAUSED
                    statusText = "已暂停，传感器采集已挂起"
                }

                TrackingState.PAUSED -> {
                    trackingState = TrackingState.TRACKING
                    statusText = "已继续，恢复实时航迹推算"
                }
            }
        },
        onStop = { stopSession() },
        onFollow = { followMode = true },
        onClearData = { clearLocalData() },
        mapContent = {
            RuntimeMapContent(
                context = context,
                hdVectorTileSource = hdVectorTileSource,
                satelliteTileSource = satelliteTileSource,
                mapMode = mapMode,
                pdrPoints = pdrPoints,
                headingDeg = headingDeg,
                followMode = followMode,
                onManualPan = { followMode = false }
            )
        }
    )
}

@Composable
fun PdrScreenContent(
    uiState: PdrUiState,
    topExpanded: Boolean,
    bottomExpanded: Boolean,
    onToggleTop: () -> Unit,
    onToggleBottom: () -> Unit,
    onMapModeChange: (MapMode) -> Unit,
    onAhrsModeChange: (AhrsMode) -> Unit,
    onPedometerToggle: (Boolean) -> Unit,
    onPrimaryAction: () -> Unit,
    onStop: () -> Unit,
    onFollow: () -> Unit,
    onClearData: () -> Unit,
    mapContent: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFEEF5FA), Color(0xFFD9E6EF))
                )
            )
    ) {
        mapContent()

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(12.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color.White.copy(alpha = 0.92f),
            shadowElevation = 8.dp
        ) {
            Column(modifier = Modifier.animateContentSize()) {
                PanelHeader(
                    title = "实时 PDR 导航",
                    subtitle = trackingStateLabel(uiState.trackingState),
                    expanded = topExpanded,
                    onToggle = onToggleTop
                )
                AnimatedVisibility(
                    visible = topExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp)) {
                        Text(
                            text = uiState.statusText,
                            color = Color(0xFF44515B),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SummaryPill(
                                label = "采样 ${String.format(Locale.US, "%.1f", uiState.imuRateHz)} Hz",
                                modifier = Modifier.weight(1f),
                                icon = { Icon(Icons.Filled.Sensors, null) }
                            )
                            SummaryPill(
                                label = "定位 ${formatAccuracy(uiState.gpsAccuracyM)}",
                                modifier = Modifier.weight(1f),
                                icon = { Icon(Icons.Filled.Explore, null) }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SummaryPill(
                                label = directionText(uiState.headingDeg),
                                modifier = Modifier.weight(1f),
                                icon = {
                                    Icon(
                                        Icons.Filled.Explore,
                                        null,
                                        modifier = Modifier.rotate(uiState.headingDeg)
                                    )
                                }
                            )
                            SummaryPill(
                                label = correctionLabel(uiState.realtimeState.lastCorrection),
                                modifier = Modifier.weight(1f),
                                icon = { Icon(Icons.Filled.MyLocation, null) }
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "地图模式",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF62727B)
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = uiState.mapMode == MapMode.HD_VECTOR,
                                onClick = { onMapModeChange(MapMode.HD_VECTOR) },
                                label = { Text("高清地图") },
                                leadingIcon = { Icon(Icons.Filled.Map, null) }
                            )
                            FilterChip(
                                selected = uiState.mapMode == MapMode.SATELLITE,
                                onClick = { onMapModeChange(MapMode.SATELLITE) },
                                label = { Text("卫星图") },
                                leadingIcon = { Icon(Icons.Filled.SatelliteAlt, null) }
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "姿态滤波",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF62727B)
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = uiState.ahrsMode == AhrsMode.SIX_AXIS,
                                onClick = { onAhrsModeChange(AhrsMode.SIX_AXIS) },
                                label = { Text("六轴") },
                                enabled = uiState.trackingState == TrackingState.IDLE
                            )
                            FilterChip(
                                selected = uiState.ahrsMode == AhrsMode.NINE_AXIS,
                                onClick = { onAhrsModeChange(AhrsMode.NINE_AXIS) },
                                label = { Text("九轴") },
                                enabled = uiState.trackingState == TrackingState.IDLE
                            )
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            shape = RoundedCornerShape(22.dp),
            color = Color.White.copy(alpha = 0.90f),
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                DirectionBadge(headingDeg = uiState.headingDeg)
                Spacer(Modifier.height(10.dp))
                FloatingActionButton(
                    onClick = onFollow,
                    containerColor = Color(0xFF1565C0),
                    contentColor = Color.White,
                    modifier = Modifier.size(50.dp)
                ) {
                    Icon(Icons.Filled.MyLocation, null)
                }
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
            shape = RoundedCornerShape(28.dp),
            color = Color.White.copy(alpha = 0.95f),
            shadowElevation = 14.dp
        ) {
            Column(modifier = Modifier.animateContentSize()) {
                PanelHeader(
                    title = "航迹工作区",
                    subtitle = "里程 ${String.format(Locale.US, "%.1f", uiState.totalDistanceM)} m  |  PDR ${uiState.totalSteps}",
                    expanded = bottomExpanded,
                    onToggle = onToggleBottom
                )
                AnimatedVisibility(
                    visible = bottomExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            CompactMetric(label = "采集时长", value = formatDuration(uiState.elapsedSeconds))
                            CompactMetric(label = "步频", value = "${uiState.cadenceSpm} spm")
                            CompactMetric(label = "步长", value = String.format(Locale.US, "%.2f m", uiState.lastStepLengthM))
                        }
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = Color(0xFFE3EDF2))
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "系统计步器",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (!uiState.stepCounterSupported) {
                                        "当前设备不支持"
                                    } else if (uiState.pedometerEnabled) {
                                        "已开启，当前 ${uiState.systemStepCount} 步"
                                    } else {
                                        "已关闭，仅保留 PDR 步检"
                                    },
                                    fontSize = 12.sp,
                                    color = Color(0xFF62727B)
                                )
                            }
                            Switch(
                                checked = uiState.pedometerEnabled,
                                onCheckedChange = onPedometerToggle,
                                enabled = uiState.stepCounterSupported
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = onPrimaryAction, modifier = Modifier.weight(1f)) {
                                Icon(
                                    imageVector = when (uiState.trackingState) {
                                        TrackingState.TRACKING -> Icons.Filled.Pause
                                        else -> Icons.Filled.PlayArrow
                                    },
                                    contentDescription = null
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(primaryActionLabel(uiState.trackingState))
                            }
                            OutlinedButton(
                                onClick = onStop,
                                enabled = uiState.trackingState != TrackingState.IDLE,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.Stop, null)
                                Spacer(Modifier.width(8.dp))
                                Text("停止")
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = onFollow, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.MyLocation, null)
                                Spacer(Modifier.width(8.dp))
                                Text("回到当前位置")
                            }
                            TextButton(onClick = onClearData, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.DeleteSweep, null, tint = Color(0xFFD84315))
                                Spacer(Modifier.width(8.dp))
                                Text("清空本地数据", color = Color(0xFFD84315))
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = Color(0xFFE3EDF2))
                        Spacer(Modifier.height(12.dp))
                        SectionTitle("实时输出")
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                MetricLine("姿态模式", ahrsModeLabel(uiState.ahrsMode))
                                MetricLine("滚转角", formatAngle(uiState.realtimeState.rollDeg))
                                MetricLine("俯仰角", formatAngle(uiState.realtimeState.pitchDeg))
                                MetricLine("偏航角", formatAngle(uiState.realtimeState.yawDeg))
                                MetricLine("航向角", directionText(uiState.headingDeg))
                                MetricLine("修正来源", correctionLabel(uiState.realtimeState.lastCorrection))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                MetricLine("步频频率", String.format(Locale.US, "%.2f Hz", uiState.realtimeState.stepFrequencyHz))
                                MetricLine("步间隔", formatSeconds(uiState.realtimeState.lastStepIntervalS))
                                MetricLine("停走检测", if (uiState.realtimeState.isInterrupted) "已触发" else "正常")
                                MetricLine("中断次数", uiState.realtimeState.interruptCount.toString())
                                MetricLine("垂向加速度", String.format(Locale.US, "%.3f", uiState.realtimeState.filteredVerticalAcc))
                                MetricLine("原始加速度模", String.format(Locale.US, "%.3f", uiState.realtimeState.rawAccNorm))
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        SectionTitle("定位与传感器")
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                MetricLine("东向位移", String.format(Locale.US, "%.2f m", uiState.realtimeState.eastM))
                                MetricLine("北向位移", String.format(Locale.US, "%.2f m", uiState.realtimeState.northM))
                                MetricLine("经度", formatCoordinate(uiState.realtimeState.lon))
                                MetricLine("纬度", formatCoordinate(uiState.realtimeState.lat))
                                MetricLine("GPS 速度", String.format(Locale.US, "%.2f m/s", uiState.gpsSpeedMps))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                MetricLine("陀螺仪模", String.format(Locale.US, "%.3f", uiState.realtimeState.gyroNorm))
                                MetricLine("磁场模", String.format(Locale.US, "%.3f", uiState.realtimeState.magNorm))
                                MetricLine("IMU 样本", uiState.imuSampleCount.toString())
                                MetricLine("采样率", String.format(Locale.US, "%.1f Hz", uiState.imuRateHz))
                                MetricLine("GPS 精度", formatAccuracy(uiState.gpsAccuracyM))
                            }
                        }
                        if (uiState.currentLogFileName.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            MetricLine("日志文件", uiState.currentLogFileName)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RuntimeMapContent(
    context: Context,
    hdVectorTileSource: OnlineTileSourceBase,
    satelliteTileSource: OnlineTileSourceBase,
    mapMode: MapMode,
    pdrPoints: List<GeoPoint>,
    headingDeg: Float,
    followMode: Boolean,
    onManualPan: () -> Unit
) {
    AndroidView(
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(hdVectorTileSource)
                setMultiTouchControls(true)
                isTilesScaledToDpi = true
                controller.setZoom(18.5)
                controller.setCenter(GeoPoint(30.5365, 114.3614))
                setOnTouchListener { _, motion ->
                    if (motion.action == MotionEvent.ACTION_DOWN) {
                        onManualPan()
                    }
                    false
                }
            }
        },
        update = { mapView ->
            val targetTileSource = if (mapMode == MapMode.HD_VECTOR) {
                hdVectorTileSource
            } else {
                satelliteTileSource
            }
            if (mapView.tileProvider.tileSource.name() != targetTileSource.name()) {
                mapView.setTileSource(targetTileSource)
            }

            val route = mapView.overlays
                .filterIsInstance<Polyline>()
                .firstOrNull { it.title == "PDR_ROUTE" }
            if (pdrPoints.size >= 2) {
                val line = route ?: Polyline().apply {
                    title = "PDR_ROUTE"
                    outlinePaint.strokeWidth = 14f
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.isAntiAlias = true
                    outlinePaint.color = android.graphics.Color.parseColor("#1266F1")
                    mapView.overlays.add(this)
                }
                line.setPoints(pdrPoints.toList())
            } else if (route != null) {
                mapView.overlays.remove(route)
            }

            val marker = mapView.overlays
                .filterIsInstance<Marker>()
                .firstOrNull { it.title == "PDR_MARKER" }
            if (pdrPoints.isNotEmpty()) {
                val headingMarker = marker ?: Marker(mapView).apply {
                    title = "PDR_MARKER"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    infoWindow = null
                    icon = ContextCompat.getDrawable(context, R.drawable.ic_pdr_heading)
                    mapView.overlays.add(this)
                }
                headingMarker.position = pdrPoints.last()
                headingMarker.rotation = headingDeg
            } else if (marker != null) {
                mapView.overlays.remove(marker)
            }

            if (followMode && pdrPoints.isNotEmpty()) {
                mapView.controller.animateTo(pdrPoints.last())
            }
            mapView.invalidate()
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun PanelHeader(title: String, subtitle: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(Color(0xFF1565C0), CircleShape)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(text = subtitle, fontSize = 13.sp, color = Color(0xFF546E7A))
        }
        IconButton(onClick = onToggle) {
            Icon(
                imageVector = if (expanded) {
                    Icons.Filled.KeyboardArrowUp
                } else {
                    Icons.Filled.KeyboardArrowDown
                },
                contentDescription = null
            )
        }
    }
}

@Composable
fun DirectionBadge(headingDeg: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(70.dp)
                .background(Color(0xFFE3F2FD), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Explore,
                contentDescription = null,
                modifier = Modifier
                    .size(34.dp)
                    .rotate(headingDeg),
                tint = Color(0xFF1252A5)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = directionText(headingDeg),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF17324D)
        )
    }
}

@Composable
fun SummaryPill(
    label: String,
    modifier: Modifier = Modifier,
    icon: @Composable (() -> Unit)? = null
) {
    AssistChip(
        onClick = { },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = icon,
        modifier = modifier
    )
}

@Composable
fun CompactMetric(label: String, value: String) {
    Column {
        Text(text = label, fontSize = 12.sp, color = Color(0xFF6E7A84))
        Spacer(Modifier.height(2.dp))
        Text(text = value, fontWeight = FontWeight.Bold, color = Color(0xFF12344B))
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF17324D)
    )
}

@Composable
fun MetricLine(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(text = label, fontSize = 12.sp, color = Color(0xFF73808A))
        Text(text = value, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2A33))
    }
}

private fun trackingStateLabel(state: TrackingState) = when (state) {
    TrackingState.IDLE -> "待机"
    TrackingState.TRACKING -> "实时推算"
    TrackingState.PAUSED -> "已暂停"
}

private fun primaryActionLabel(state: TrackingState) = when (state) {
    TrackingState.IDLE -> "开始"
    TrackingState.TRACKING -> "暂停"
    TrackingState.PAUSED -> "继续"
}

private fun ahrsModeLabel(mode: AhrsMode) = when (mode) {
    AhrsMode.SIX_AXIS -> "六轴姿态滤波"
    AhrsMode.NINE_AXIS -> "九轴姿态滤波"
}

private fun correctionLabel(source: String) = when (source) {
    "INIT" -> "初始化"
    "GPS" -> "GPS 位置修正"
    "GPS_HEADING" -> "GPS 航向修正"
    "MAG_REINIT" -> "磁场重对准"
    "STEP" -> "步长位置更新"
    else -> "无"
}

private fun logEventLabel(result: PdrProcessResult): String = when {
    result.step?.isInterrupted == true -> "STEP_INTERRUPTED"
    result.step != null -> "STEP"
    result.state.lastCorrection == "GPS" -> "GPS"
    result.state.lastCorrection == "GPS_HEADING" -> "GPS_HEADING"
    result.state.lastCorrection == "MAG_REINIT" -> "MAG_REINIT"
    else -> ""
}

private fun formatAngle(value: Float): String = String.format(Locale.US, "%.1f°", value)

private fun formatSeconds(value: Double): String = if (value <= 0.0) {
    "--"
} else {
    String.format(Locale.US, "%.2f s", value)
}

private fun formatAccuracy(value: Float): String = if (value.isNaN()) {
    "--"
} else {
    String.format(Locale.US, "±%.1f m", value)
}

private fun formatCoordinate(value: Double): String = if (value.isNaN()) {
    "--"
} else {
    String.format(Locale.US, "%.6f", value)
}

private fun directionText(headingDeg: Float): String {
    return "${directionLabel(headingDeg)} ${String.format(Locale.US, "%.0f", normalizeDegrees(headingDeg))}°"
}

private fun directionLabel(headingDeg: Float): String {
    val value = normalizeDegrees(headingDeg)
    return when {
        value < 22.5f || value >= 337.5f -> "北"
        value < 67.5f -> "东北"
        value < 112.5f -> "东"
        value < 157.5f -> "东南"
        value < 202.5f -> "南"
        value < 247.5f -> "西南"
        value < 292.5f -> "西"
        else -> "西北"
    }
}

private fun emptyRealtimeState(mode: AhrsMode = AhrsMode.NINE_AXIS): PdrRealtimeState {
    return PdrRealtimeState(
        lat = Double.NaN,
        lon = Double.NaN,
        eastM = 0.0,
        northM = 0.0,
        headingDeg = 0f,
        rollDeg = 0f,
        pitchDeg = 0f,
        yawDeg = 0f,
        totalSteps = 0,
        cadenceSpm = 0,
        stepFrequencyHz = 0.0,
        lastStepLengthM = 0.0,
        totalDistanceM = 0.0,
        ahrsMode = mode,
        rawAccNorm = 0f,
        filteredVerticalAcc = 0f,
        gyroNorm = 0f,
        magNorm = 0f,
        isInterrupted = false,
        interruptCount = 0,
        lastStepIntervalS = 0.0,
        lastCorrection = "NONE"
    )
}

private fun normalizeDegrees(value: Float): Float {
    var v = value % 360f
    if (v < 0f) v += 360f
    return v
}

private fun hasLocationPermission(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) fine || coarse else fine || coarse
}

private fun formatDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}

private fun wgsToGcj(lat: Double, lon: Double): GeoPoint {
    val gcj = CoordinateTransformUtil.wgs84ToGcj02(lon, lat)
    return GeoPoint(gcj[1], gcj[0])
}

private fun clearAppLocalData(context: Context): Int {
    var deleted = 0
    listOf(File(context.filesDir, "pdr_logs"), File(context.filesDir, "osmdroid/tiles")).forEach { root ->
        if (root.exists()) {
            deleted += deleteTree(root)
            root.mkdirs()
        }
    }
    return deleted
}

private fun deleteTree(file: File): Int {
    if (!file.exists()) return 0
    var count = 0
    if (file.isDirectory) file.listFiles()?.forEach { count += deleteTree(it) }
    if (file.delete()) count += 1
    return count
}

private fun createGaodeTileSource(satellite: Boolean): OnlineTileSourceBase {
    val sourceName = if (satellite) "GaoDeSatellite" else "GaoDeVector"
    val style = if (satellite) 6 else 8
    return object : OnlineTileSourceBase(
        sourceName,
        3,
        19,
        256,
        ".png",
        arrayOf(
            "https://wprd01.is.autonavi.com/appmaptile?",
            "https://wprd02.is.autonavi.com/appmaptile?",
            "https://wprd03.is.autonavi.com/appmaptile?",
            "https://wprd04.is.autonavi.com/appmaptile?"
        )
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val z = MapTileIndex.getZoom(pMapTileIndex)
            val x = MapTileIndex.getX(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)
            return baseUrl + "lang=zh_cn&size=1&scale=2&style=$style&x=$x&y=$y&z=$z"
        }
    }
}

@Composable
fun PdrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF0E4C92),
            onPrimary = Color.White,
            secondary = Color(0xFF147A6B),
            onSecondary = Color.White,
            tertiary = Color(0xFFF08A24),
            surface = Color.White,
            background = Color(0xFFEEF4F8)
        ),
        content = content
    )
}

@Preview(showBackground = true, backgroundColor = 0xFFE9F0F6)
@Composable
fun PdrDashboardPreview() {
    val previewRealtime = PdrRealtimeState(
        lat = 30.536512,
        lon = 114.361422,
        eastM = 18.7,
        northM = 44.3,
        headingDeg = 63f,
        rollDeg = 2.4f,
        pitchDeg = -4.1f,
        yawDeg = 63f,
        totalSteps = 86,
        cadenceSpm = 114,
        stepFrequencyHz = 1.90,
        lastStepLengthM = 0.73,
        totalDistanceM = 62.8,
        ahrsMode = AhrsMode.NINE_AXIS,
        rawAccNorm = 10.12f,
        filteredVerticalAcc = 0.84f,
        gyroNorm = 0.51f,
        magNorm = 42.7f,
        isInterrupted = false,
        interruptCount = 1,
        lastStepIntervalS = 0.54,
        lastCorrection = "STEP"
    )
    val previewState = PdrUiState(
        trackingState = TrackingState.TRACKING,
        statusText = "起点已锁定，正在进行实时航迹推算与传感器输出",
        elapsedSeconds = 372,
        totalSteps = 86,
        systemStepCount = 84,
        cadenceSpm = 114,
        lastStepLengthM = 0.73f,
        totalDistanceM = 62.8f,
        headingDeg = 63f,
        gpsAccuracyM = 5.8f,
        gpsSpeedMps = 1.32f,
        imuSampleCount = 4218,
        imuRateHz = 48.6f,
        currentLogFileName = "imu_pdr_preview.csv",
        pedometerEnabled = true,
        stepCounterSupported = true,
        mapMode = MapMode.HD_VECTOR,
        ahrsMode = AhrsMode.NINE_AXIS,
        realtimeState = previewRealtime
    )

    PdrTheme {
        PdrScreenContent(
            uiState = previewState,
            topExpanded = true,
            bottomExpanded = true,
            onToggleTop = {},
            onToggleBottom = {},
            onMapModeChange = {},
            onAhrsModeChange = {},
            onPedometerToggle = {},
            onPrimaryAction = {},
            onStop = {},
            onFollow = {},
            onClearData = {},
            mapContent = { PreviewMapPlaceholder() }
        )
    }
}

@Composable
private fun PreviewMapPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFFDCE8F0), Color(0xFFC5D5E1))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = Color.White.copy(alpha = 0.60f)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Filled.Map,
                    contentDescription = null,
                    tint = Color(0xFF0E4C92),
                    modifier = Modifier.size(36.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text("Compose 预览地图占位", fontWeight = FontWeight.Bold, color = Color(0xFF17324D))
                Text("运行时将替换为高清地图与航迹图层", fontSize = 12.sp, color = Color(0xFF5C6A74))
            }
        }
    }
}
