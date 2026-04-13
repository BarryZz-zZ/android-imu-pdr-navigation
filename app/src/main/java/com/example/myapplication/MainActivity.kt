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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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

enum class TrackingState { IDLE, TRACKING, PAUSED }
enum class MapMode { HD_VECTOR, SATELLITE }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val osmConfig = Configuration.getInstance()
        osmConfig.load(applicationContext, PreferenceManager.getDefaultSharedPreferences(applicationContext))
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
    val pdrEngine = remember { NineAxisPdrEngine() }
    val imuCsvLogger = remember { ImuCsvLogger(context) }
    val stepCounterSensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) }
    val hdVectorTileSource = remember { createGaodeTileSource(false) }
    val satelliteTileSource = remember { createGaodeTileSource(true) }

    var trackingState by remember { mutableStateOf(TrackingState.IDLE) }
    var statusText by remember { mutableStateOf("\u5c31\u7eea\uff0c\u70b9\u51fb\u5f00\u59cb\u8fdb\u884c\u4e5d\u8f74IMU\u91c7\u96c6") }
    var followMode by remember { mutableStateOf(true) }
    var topExpanded by remember { mutableStateOf(true) }
    var bottomExpanded by remember { mutableStateOf(true) }
    var pedometerEnabled by remember { mutableStateOf(stepCounterSensor != null) }
    var mapMode by remember { mutableStateOf(MapMode.HD_VECTOR) }
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
    val pdrPoints = remember { mutableStateListOf<GeoPoint>() }

    fun resetSession() {
        elapsedSeconds = 0L; totalSteps = 0; cadenceSpm = 0; lastStepLength = 0f; totalDistanceM = 0f
        headingDeg = 0f; gpsAccuracyM = Float.NaN; gpsSpeedMps = 0f; imuSampleCount = 0L; imuRateHz = 0f
        waitingStartFix = true; systemStepCount = 0; stepCounterBase = null; currentLogFile = ""; pdrPoints.clear()
        pdrEngine.clear()
    }

    fun stopSession(updateStatus: Boolean = true) {
        imuCsvLogger.stopSession(); pdrEngine.clear(); trackingState = TrackingState.IDLE; waitingStartFix = false
        if (updateStatus) statusText = "\u5df2\u505c\u6b62\uff0c\u65e5\u5fd7\u5df2\u4fdd\u5b58"
    }

    fun clearLocalData() {
        stopSession(false); val deleted = clearAppLocalData(context); resetSession(); waitingStartFix = false
        statusText = "\u5df2\u6e05\u7a7a\u672c\u5730\u6570\u636e\uff0c\u5171\u5220\u9664 $deleted \u9879"
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) { resetSession(); trackingState = TrackingState.TRACKING; currentLogFile = imuCsvLogger.startSession(); statusText = "\u5df2\u5f00\u59cb\uff0c\u6b63\u5728\u7b49\u5f85GPS\u8d77\u70b9" }
        else statusText = "\u672a\u6388\u4e88\u5b9a\u4f4d\u6743\u9650"
    }

    fun startSession() {
        if (!hasLocationPermission(context)) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        resetSession(); trackingState = TrackingState.TRACKING; currentLogFile = imuCsvLogger.startSession()
        statusText = "\u5df2\u5f00\u59cb\uff0c\u6b63\u5728\u7b49\u5f85GPS\u8d77\u70b9"
    }

    LaunchedEffect(trackingState) { while (trackingState == TrackingState.TRACKING) { delay(1000L); elapsedSeconds += 1L } }

    DisposableEffect(trackingState) {
        if (trackingState != TrackingState.TRACKING || !hasLocationPermission(context)) onDispose { } else {
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 900L).setMinUpdateDistanceMeters(0f).setWaitForAccurateLocation(false).build()
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val location = result.lastLocation ?: return
                    gpsAccuracyM = location.accuracy; gpsSpeedMps = location.speed
                    if (trackingState != TrackingState.TRACKING) return
                    if (!pdrEngine.isStarted() && waitingStartFix && trackingState == TrackingState.TRACKING) {
                        pdrEngine.reset(location.latitude, location.longitude, if (location.hasBearing()) location.bearing else null)
                        pdrPoints.add(wgsToGcj(location.latitude, location.longitude))
                        waitingStartFix = false
                        statusText = "\u8d77\u70b9\u5df2\u9501\u5b9a\uff0c\u5b9e\u65f6PDR\u6b63\u5728\u8fd0\u884c"
                    } else if (pdrEngine.isStarted()) {
                        pdrEngine.correctWithGps(location.latitude, location.longitude, location.accuracy)
                        if (location.hasBearing()) pdrEngine.updateHeadingAssist(location.bearing, location.speed, location.accuracy)
                    }
                }
            }
            fusedLocationClient.requestLocationUpdates(locationRequest, callback, Looper.getMainLooper())
            onDispose { fusedLocationClient.removeLocationUpdates(callback) }
        }
    }

    DisposableEffect(trackingState, pedometerEnabled) {
        if (trackingState != TrackingState.TRACKING || !pedometerEnabled || stepCounterSensor == null) onDispose { } else {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val counter = event.values.firstOrNull() ?: return
                    if (stepCounterBase == null) stepCounterBase = counter
                    systemStepCount = (counter - (stepCounterBase ?: counter)).toInt().coerceAtLeast(0)
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            sensorManager.registerListener(listener, stepCounterSensor, SensorManager.SENSOR_DELAY_UI)
            onDispose { sensorManager.unregisterListener(listener) }
        }
    }

    DisposableEffect(trackingState) {
        if (trackingState != TrackingState.TRACKING) onDispose { } else {
            val accSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            val magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            val rvSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            if (accSensor == null || gyroSensor == null || magSensor == null) {
                statusText = "\u8bbe\u5907\u4e0d\u652f\u6301\u5b8c\u6574\u4e5d\u8f74IMU"; trackingState = TrackingState.IDLE; onDispose { }
            } else {
                val acc = FloatArray(3); val gyro = FloatArray(3); val mag = FloatArray(3)
                var hasAcc = false; var hasGyro = false; var hasMag = false
                var rateWindowStartMs = System.currentTimeMillis(); var rateWindowCount = 0; var lastUiRefreshMs = System.currentTimeMillis()
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        if (trackingState != TrackingState.TRACKING) return
                        when (event.sensor.type) {
                            Sensor.TYPE_ACCELEROMETER -> { System.arraycopy(event.values, 0, acc, 0, 3); hasAcc = true }
                            Sensor.TYPE_GYROSCOPE -> { System.arraycopy(event.values, 0, gyro, 0, 3); hasGyro = true }
                            Sensor.TYPE_MAGNETIC_FIELD -> { System.arraycopy(event.values, 0, mag, 0, 3); hasMag = true }
                            Sensor.TYPE_ROTATION_VECTOR -> { headingDeg = pdrEngine.onRotationVector(event.values); return }
                        }
                        if (!hasAcc || !hasGyro || !hasMag || !pdrEngine.isStarted()) return
                        val sample = NineAxisSample(acc[0], acc[1], acc[2], gyro[0], gyro[1], gyro[2], mag[0], mag[1], mag[2], event.timestamp)
                        val stepResult = pdrEngine.onNineAxisSample(sample)
                        val snapshot = pdrEngine.snapshot()
                        imuCsvLogger.logSample(sample, snapshot, if (stepResult == null) "" else "STEP")
                        imuSampleCount += 1L; rateWindowCount += 1
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - rateWindowStartMs >= 1000L) { imuRateHz = rateWindowCount * 1000f / (nowMs - rateWindowStartMs).coerceAtLeast(1L); rateWindowCount = 0; rateWindowStartMs = nowMs }
                        if (stepResult != null) {
                            totalSteps = stepResult.totalSteps; cadenceSpm = stepResult.cadenceSpm; lastStepLength = stepResult.lastStepLengthM.toFloat()
                            totalDistanceM = stepResult.totalDistanceM.toFloat(); headingDeg = stepResult.headingDeg; pdrPoints.add(wgsToGcj(stepResult.currentLat, stepResult.currentLon))
                        } else if (nowMs - lastUiRefreshMs >= 200L) {
                            totalSteps = snapshot.totalSteps; cadenceSpm = snapshot.cadenceSpm; totalDistanceM = snapshot.totalDistanceM.toFloat(); headingDeg = snapshot.headingDeg; lastUiRefreshMs = nowMs
                        }
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                sensorManager.registerListener(listener, accSensor, SensorManager.SENSOR_DELAY_GAME)
                sensorManager.registerListener(listener, gyroSensor, SensorManager.SENSOR_DELAY_GAME)
                sensorManager.registerListener(listener, magSensor, SensorManager.SENSOR_DELAY_GAME)
                rvSensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
                onDispose { sensorManager.unregisterListener(listener) }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFEDF5FB), Color(0xFFDCE9EE))))) {
        AndroidView(factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(hdVectorTileSource); setMultiTouchControls(true); isTilesScaledToDpi = true
                controller.setZoom(18.5); controller.setCenter(GeoPoint(30.5365, 114.3614))
                setOnTouchListener { _, motion -> if (motion.action == MotionEvent.ACTION_DOWN) followMode = false; false }
            }
        }, update = { mapView ->
            val tileSource = if (mapMode == MapMode.HD_VECTOR) hdVectorTileSource else satelliteTileSource
            if (mapView.tileProvider.tileSource.name() != tileSource.name()) mapView.setTileSource(tileSource)
            val route = mapView.overlays.filterIsInstance<Polyline>().firstOrNull { it.title == "PDR_ROUTE" }
            if (pdrPoints.size >= 2) {
                val line = route ?: Polyline().apply {
                    title = "PDR_ROUTE"; outlinePaint.strokeWidth = 14f; outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.isAntiAlias = true; outlinePaint.color = android.graphics.Color.parseColor("#1266F1"); mapView.overlays.add(this)
                }
                line.setPoints(pdrPoints.toList())
            } else if (route != null) mapView.overlays.remove(route)
            val marker = mapView.overlays.filterIsInstance<Marker>().firstOrNull { it.title == "PDR_MARKER" }
            if (pdrPoints.isNotEmpty()) {
                val headingMarker = marker ?: Marker(mapView).apply {
                    title = "PDR_MARKER"; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); infoWindow = null
                    icon = ContextCompat.getDrawable(context, R.drawable.ic_pdr_heading); mapView.overlays.add(this)
                }
                headingMarker.position = pdrPoints.last(); headingMarker.rotation = headingDeg
            } else if (marker != null) mapView.overlays.remove(marker)
            if (followMode && pdrPoints.isNotEmpty()) mapView.controller.animateTo(pdrPoints.last())
            mapView.invalidate()
        }, modifier = Modifier.fillMaxSize())

        Surface(modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(14.dp), shape = RoundedCornerShape(28.dp), color = Color.White.copy(alpha = 0.9f), shadowElevation = 10.dp) {
            Column(modifier = Modifier.animateContentSize()) {
                PanelHeader("\u5b9e\u65f6PDR\u9762\u677f", "\u72b6\u6001\uff1a${trackingStateLabel(trackingState)}", topExpanded) { topExpanded = !topExpanded }
                AnimatedVisibility(topExpanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp)) {
                        Text(statusText, color = Color(0xFF455A64)); Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(onClick = { }, label = { Text("\u91c7\u6837 ${String.format(Locale.US, "%.1f", imuRateHz)} Hz") }, leadingIcon = { Icon(Icons.Filled.Sensors, null) })
                            AssistChip(onClick = { }, label = { Text("\u7cbe\u5ea6 \u00b1${if (gpsAccuracyM.isNaN()) "--" else String.format(Locale.US, "%.1f", gpsAccuracyM)} m") }, leadingIcon = { Icon(Icons.Filled.Explore, null) })
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = mapMode == MapMode.HD_VECTOR, onClick = { mapMode = MapMode.HD_VECTOR }, label = { Text("\u9ad8\u6e05\u5730\u56fe") }, leadingIcon = { Icon(Icons.Filled.Map, null) })
                            FilterChip(selected = mapMode == MapMode.SATELLITE, onClick = { mapMode = MapMode.SATELLITE }, label = { Text("\u536b\u661f\u56fe") }, leadingIcon = { Icon(Icons.Filled.SatelliteAlt, null) })
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("\u7cfb\u7edf\u8ba1\u6b65\u5668", fontWeight = FontWeight.SemiBold)
                                Text(if (stepCounterSensor == null) "\u5f53\u524d\u8bbe\u5907\u4e0d\u652f\u6301" else if (pedometerEnabled) "\u5df2\u542f\u7528\u663e\u793a\u7cfb\u7edf\u6b65\u6570" else "\u5df2\u5173\u95ed\uff0c\u4ec5\u4fdd\u7559PDR\u6b65\u68c0\u6d4b", fontSize = 12.sp, color = Color(0xFF607D8B))
                            }
                            Switch(checked = pedometerEnabled, onCheckedChange = { pedometerEnabled = it }, enabled = stepCounterSensor != null)
                        }
                        Spacer(Modifier.height(10.dp))
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF4F7)), shape = RoundedCornerShape(18.dp), modifier = Modifier.clickable { clearLocalData() }) {
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.DeleteSweep, null, tint = Color(0xFFD84315)); Spacer(Modifier.width(10.dp))
                                Column {
                                    Text("\u6e05\u7a7a\u672c\u5730\u6570\u636e", fontWeight = FontWeight.Bold, color = Color(0xFFBF360C))
                                    Text("\u5220\u9664\u91c7\u96c6\u65e5\u5fd7\u548c\u79bb\u7ebf\u5730\u56fe\u7f13\u5b58", fontSize = 12.sp, color = Color(0xFF6D4C41))
                                }
                            }
                        }
                    }
                }
            }
        }

        Surface(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 14.dp), shape = RoundedCornerShape(24.dp), color = Color.White.copy(alpha = 0.88f), shadowElevation = 8.dp) {
            Column(modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                DirectionBadge(headingDeg); Spacer(Modifier.height(8.dp))
                FloatingActionButton(onClick = { followMode = true }, containerColor = Color(0xFF1E88E5), contentColor = Color.White, modifier = Modifier.size(50.dp)) { Icon(Icons.Filled.MyLocation, null) }
            }
        }

        Surface(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(14.dp), shape = RoundedCornerShape(30.dp), color = Color.White.copy(alpha = 0.94f), shadowElevation = 14.dp) {
            Column(modifier = Modifier.animateContentSize()) {
                PanelHeader("\u8fd0\u52a8\u4e0e\u5bfc\u822a\u6307\u6807", "\u91cc\u7a0b ${String.format(Locale.US, "%.1f", totalDistanceM)} m  |  PDR $totalSteps", bottomExpanded) { bottomExpanded = !bottomExpanded }
                AnimatedVisibility(bottomExpanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            StatCell("\u91c7\u96c6\u65f6\u957f", formatDuration(elapsedSeconds), Color(0xFF1565C0))
                            StatCell("\u6b65\u9891", "$cadenceSpm spm", Color(0xFF00897B))
                            StatCell("\u822a\u5411", directionText(headingDeg), Color(0xFFEF6C00))
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            StatCell("\u91cc\u7a0b", String.format(Locale.US, "%.1f m", totalDistanceM), Color(0xFF3949AB))
                            StatCell("\u6b65\u957f", String.format(Locale.US, "%.2f m", lastStepLength), Color(0xFF7B1FA2))
                            StatCell("\u7cfb\u7edf\u8ba1\u6b65", if (stepCounterSensor == null) "\u4e0d\u652f\u6301" else if (pedometerEnabled) systemStepCount.toString() else "\u5df2\u5173\u95ed", Color(0xFF2E7D32))
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("\u91c7\u6837\u70b9\uff1a$imuSampleCount   |   GPS\u901f\u5ea6\uff1a${String.format(Locale.US, "%.2f", gpsSpeedMps)} m/s", fontSize = 12.sp, color = Color(0xFF607D8B))
                        if (currentLogFile.isNotBlank()) Text("\u65e5\u5fd7\u6587\u4ef6\uff1a${File(currentLogFile).name}", fontSize = 12.sp, color = Color(0xFF607D8B))
                        Spacer(Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            if (trackingState != TrackingState.IDLE) {
                                FloatingActionButton(onClick = { stopSession() }, containerColor = Color(0xFFD32F2F), contentColor = Color.White) { Icon(Icons.Filled.Stop, null) }
                                Spacer(Modifier.width(18.dp))
                            }
                            FloatingActionButton(onClick = {
                                when (trackingState) {
                                    TrackingState.IDLE -> startSession()
                                    TrackingState.TRACKING -> { trackingState = TrackingState.PAUSED; statusText = "\u5df2\u6682\u505c" }
                                    TrackingState.PAUSED -> { trackingState = TrackingState.TRACKING; statusText = "\u5df2\u7ee7\u7eed\u8ddf\u8e2a" }
                                }
                            }, containerColor = if (trackingState == TrackingState.TRACKING) Color(0xFFFFA000) else Color(0xFF2E7D32), contentColor = Color.White) {
                                Icon(if (trackingState == TrackingState.TRACKING) Icons.Filled.Pause else Icons.Filled.PlayArrow, null)
                            }
                            Spacer(Modifier.width(18.dp))
                            FloatingActionButton(onClick = { followMode = true }, containerColor = Color(0xFF1976D2), contentColor = Color.White) { Icon(Icons.Filled.MyLocation, null) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PanelHeader(title: String, subtitle: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable { onToggle() }.padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(12.dp).background(Color(0xFF1E88E5), CircleShape))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, fontSize = 13.sp, color = Color(0xFF546E7A))
        }
        IconButton(onClick = onToggle) { Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null) }
    }
}

@Composable
fun DirectionBadge(headingDeg: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(72.dp).background(Color(0xFFE3F2FD), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Explore, null, modifier = Modifier.size(34.dp).rotate(headingDeg), tint = Color(0xFF1565C0))
        }
        Spacer(Modifier.height(6.dp))
        Text(directionText(headingDeg), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E3A5F))
    }
}

@Composable
fun StatCell(label: String, value: String, accent: Color) {
    Card(modifier = Modifier.width(108.dp), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FBFD))) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Text(label, fontSize = 12.sp, color = Color(0xFF78909C))
            Spacer(Modifier.height(4.dp))
            Text(value, color = accent, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
        }
    }
}

fun trackingStateLabel(state: TrackingState) = when (state) {
    TrackingState.IDLE -> "\u5f85\u673a"
    TrackingState.TRACKING -> "\u5b9e\u65f6PDR"
    TrackingState.PAUSED -> "\u5df2\u6682\u505c"
}

fun directionText(headingDeg: Float): String = "${directionLabel(headingDeg)} ${String.format(Locale.US, "%.0f", normalizeDegrees(headingDeg))}\u00b0"

fun directionLabel(headingDeg: Float): String {
    val value = normalizeDegrees(headingDeg)
    return when {
        value < 22.5f || value >= 337.5f -> "\u5317"
        value < 67.5f -> "\u4e1c\u5317"
        value < 112.5f -> "\u4e1c"
        value < 157.5f -> "\u4e1c\u5357"
        value < 202.5f -> "\u5357"
        value < 247.5f -> "\u897f\u5357"
        value < 292.5f -> "\u897f"
        else -> "\u897f\u5317"
    }
}

fun normalizeDegrees(value: Float): Float { var v = value % 360f; if (v < 0f) v += 360f; return v }

fun hasLocationPermission(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) fine || coarse else fine || coarse
}

fun formatDuration(totalSeconds: Long): String {
    val h = totalSeconds / 3600; val m = (totalSeconds % 3600) / 60; val s = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}

fun wgsToGcj(lat: Double, lon: Double): GeoPoint {
    val gcj = CoordinateTransformUtil.wgs84ToGcj02(lon, lat)
    return GeoPoint(gcj[1], gcj[0])
}

fun clearAppLocalData(context: Context): Int {
    var deleted = 0
    listOf(File(context.filesDir, "pdr_logs"), File(context.filesDir, "osmdroid/tiles")).forEach { root ->
        if (root.exists()) { deleted += deleteTree(root); root.mkdirs() }
    }
    return deleted
}

fun deleteTree(file: File): Int {
    if (!file.exists()) return 0
    var count = 0
    if (file.isDirectory) file.listFiles()?.forEach { count += deleteTree(it) }
    if (file.delete()) count += 1
    return count
}

fun createGaodeTileSource(satellite: Boolean): OnlineTileSourceBase {
    val sourceName = if (satellite) "GaoDeSatellite" else "GaoDeVector"
    val style = if (satellite) 6 else 8
    return object : OnlineTileSourceBase(sourceName, 3, 19, 256, ".png", arrayOf(
        "https://wprd01.is.autonavi.com/appmaptile?",
        "https://wprd02.is.autonavi.com/appmaptile?",
        "https://wprd03.is.autonavi.com/appmaptile?",
        "https://wprd04.is.autonavi.com/appmaptile?"
    )) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val z = MapTileIndex.getZoom(pMapTileIndex); val x = MapTileIndex.getX(pMapTileIndex); val y = MapTileIndex.getY(pMapTileIndex)
            return baseUrl + "lang=zh_cn&size=1&scale=2&style=$style&x=$x&y=$y&z=$z"
        }
    }
}

@Composable
fun PdrTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF0D47A1), onPrimary = Color.White, secondary = Color(0xFF00897B), onSecondary = Color.White, tertiary = Color(0xFFFFA000), surface = Color.White, background = Color(0xFFEEF4F8)), content = content)
}
