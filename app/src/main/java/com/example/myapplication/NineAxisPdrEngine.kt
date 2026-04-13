package com.example.myapplication

import android.hardware.SensorManager
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class NineAxisSample(
    val ax: Float,
    val ay: Float,
    val az: Float,
    val gx: Float,
    val gy: Float,
    val gz: Float,
    val mx: Float,
    val my: Float,
    val mz: Float,
    val timestampNs: Long
)

data class PdrStepResult(
    val currentLat: Double,
    val currentLon: Double,
    val totalSteps: Int,
    val cadenceSpm: Int,
    val lastStepLengthM: Double,
    val totalDistanceM: Double,
    val headingDeg: Float,
    val eastM: Double,
    val northM: Double
)

data class PdrSnapshot(
    val lat: Double,
    val lon: Double,
    val headingDeg: Float,
    val totalSteps: Int,
    val cadenceSpm: Int,
    val totalDistanceM: Double,
    val eastM: Double,
    val northM: Double
)

class NineAxisPdrEngine {
    private var started = false
    private var startLat = 0.0
    private var startLon = 0.0

    private var eastM = 0.0
    private var northM = 0.0
    private var totalDistanceM = 0.0

    private var totalSteps = 0
    private var cadenceSpm = 0
    private var lastStepLengthM = 0.0

    private var headingRad = 0f
    private var headingBiasRad = 0f
    private var hasHeading = false

    private val gravity = FloatArray(3)
    private var lastSampleNs = 0L
    private var signalMean = 0f
    private var peak = -10f
    private var valley = 10f
    private var prevSignal = 0f
    private var adaptiveNoise = 0.4f
    private var lastStepNs = 0L
    private val stepWindowMs = ArrayDeque<Long>()

    private val rotationMatrix = FloatArray(9)
    private var hasRotationMatrix = false

    private val orientation = FloatArray(3)

    fun reset(startLat: Double, startLon: Double, initialHeadingDeg: Float? = null) {
        this.startLat = startLat
        this.startLon = startLon
        eastM = 0.0
        northM = 0.0
        totalDistanceM = 0.0
        totalSteps = 0
        cadenceSpm = 0
        lastStepLengthM = 0.0

        gravity[0] = 0f
        gravity[1] = 0f
        gravity[2] = 0f
        lastSampleNs = 0L
        signalMean = 0f
        peak = -10f
        valley = 10f
        prevSignal = 0f
        adaptiveNoise = 0.4f
        lastStepNs = 0L
        stepWindowMs.clear()

        headingBiasRad = 0f
        hasHeading = initialHeadingDeg != null
        if (initialHeadingDeg != null) {
            headingRad = Math.toRadians(initialHeadingDeg.toDouble()).toFloat()
        }
        started = true
    }

    fun clear() {
        started = false
    }

    fun isStarted(): Boolean = started

    fun currentHeadingDeg(): Float {
        return normalizeDeg(Math.toDegrees((headingRad + headingBiasRad).toDouble()).toFloat())
    }

    fun onRotationVector(values: FloatArray): Float {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
        hasRotationMatrix = true
        SensorManager.getOrientation(rotationMatrix, orientation)
        val absoluteHeading = wrapRad(orientation[0])
        if (!hasHeading) {
            headingRad = absoluteHeading
            hasHeading = true
        } else {
            headingRad = blendAngle(headingRad, absoluteHeading, 0.18f)
        }
        return currentHeadingDeg()
    }

    fun onNineAxisSample(sample: NineAxisSample): PdrStepResult? {
        if (!started) return null

        if (!hasRotationMatrix) {
            val acc = floatArrayOf(sample.ax, sample.ay, sample.az)
            val mag = floatArrayOf(sample.mx, sample.my, sample.mz)
            if (SensorManager.getRotationMatrix(rotationMatrix, null, acc, mag)) {
                hasRotationMatrix = true
                SensorManager.getOrientation(rotationMatrix, orientation)
                val magneticHeading = wrapRad(orientation[0])
                headingRad = if (!hasHeading) magneticHeading else blendAngle(headingRad, magneticHeading, 0.06f)
                hasHeading = true
            }
        }

        val dt = if (lastSampleNs > 0L) (sample.timestampNs - lastSampleNs) / 1_000_000_000f else 0f
        lastSampleNs = sample.timestampNs
        if (dt <= 0f || dt > 0.5f) return null

        val alpha = 0.90f
        gravity[0] = alpha * gravity[0] + (1f - alpha) * sample.ax
        gravity[1] = alpha * gravity[1] + (1f - alpha) * sample.ay
        gravity[2] = alpha * gravity[2] + (1f - alpha) * sample.az

        val lx = sample.ax - gravity[0]
        val ly = sample.ay - gravity[1]
        val lz = sample.az - gravity[2]
        val worldZ = if (hasRotationMatrix) {
            rotationMatrix[6] * lx + rotationMatrix[7] * ly + rotationMatrix[8] * lz
        } else {
            lz
        }

        signalMean = 0.96f * signalMean + 0.04f * worldZ
        val signal = worldZ - signalMean
        adaptiveNoise = 0.985f * adaptiveNoise + 0.015f * abs(signal)
        val dynamicThreshold = (adaptiveNoise * 2.8f).coerceIn(0.8f, 2.4f)

        peak = max(peak, signal)
        valley = min(valley, signal)

        val crossedUp = prevSignal < dynamicThreshold && signal >= dynamicThreshold
        prevSignal = signal

        val minStepGapNs = 280_000_000L
        if (!crossedUp || sample.timestampNs - lastStepNs < minStepGapNs) {
            return null
        }

        val amplitude = (peak - valley).coerceIn(0.4f, 6.0f)
        val gyroNorm = sqrt(sample.gx * sample.gx + sample.gy * sample.gy + sample.gz * sample.gz)
        if (amplitude < 0.7f && gyroNorm < 0.35f) {
            return null
        }

        lastStepNs = sample.timestampNs
        peak = signal
        valley = signal
        totalSteps += 1

        val nowMs = sample.timestampNs / 1_000_000L
        stepWindowMs.addLast(nowMs)
        while (stepWindowMs.isNotEmpty() && nowMs - stepWindowMs.first() > 10_000L) {
            stepWindowMs.removeFirst()
        }
        cadenceSpm = stepWindowMs.size * 6

        val amplitudeTerm = sqrt(amplitude.toDouble())
        val cadenceTerm = (cadenceSpm - 100).coerceIn(-40, 80) / 100.0
        val dynamicStep = 0.58 + 0.16 * (amplitudeTerm - 1.0) + 0.10 * cadenceTerm
        lastStepLengthM = dynamicStep.coerceIn(0.35, 1.25)
        totalDistanceM += lastStepLengthM

        val usedHeading = headingRad + headingBiasRad
        eastM += lastStepLengthM * sin(usedHeading.toDouble())
        northM += lastStepLengthM * cos(usedHeading.toDouble())

        val (lat, lon) = localToGeo(startLat, startLon, eastM, northM)
        return PdrStepResult(
            currentLat = lat,
            currentLon = lon,
            totalSteps = totalSteps,
            cadenceSpm = cadenceSpm,
            lastStepLengthM = lastStepLengthM,
            totalDistanceM = totalDistanceM,
            headingDeg = normalizeDeg(Math.toDegrees(usedHeading.toDouble()).toFloat()),
            eastM = eastM,
            northM = northM
        )
    }

    fun updateHeadingAssist(gpsBearingDeg: Float, speedMps: Float, accuracyM: Float) {
        if (!started || !hasHeading || speedMps < 0.8f || accuracyM > 20f) return
        val gpsHeading = Math.toRadians(gpsBearingDeg.toDouble()).toFloat()
        val desiredBias = angleDiff(gpsHeading, headingRad)
        val blend = when {
            accuracyM <= 6f -> 0.12f
            accuracyM <= 12f -> 0.08f
            else -> 0.04f
        }
        headingBiasRad = blendAngle(headingBiasRad, desiredBias, blend)
    }

    fun correctWithGps(gpsLat: Double, gpsLon: Double, accuracyM: Float) {
        if (!started || accuracyM > 25f) return
        val (gpsEast, gpsNorth) = geoToLocal(startLat, startLon, gpsLat, gpsLon)
        val alpha = when {
            accuracyM <= 5f -> 0.25
            accuracyM <= 10f -> 0.16
            else -> 0.08
        }
        eastM = eastM * (1.0 - alpha) + gpsEast * alpha
        northM = northM * (1.0 - alpha) + gpsNorth * alpha
    }

    fun snapshot(): PdrSnapshot {
        val (lat, lon) = if (started) localToGeo(startLat, startLon, eastM, northM) else Pair(Double.NaN, Double.NaN)
        return PdrSnapshot(
            lat = lat,
            lon = lon,
            headingDeg = currentHeadingDeg(),
            totalSteps = totalSteps,
            cadenceSpm = cadenceSpm,
            totalDistanceM = totalDistanceM,
            eastM = eastM,
            northM = northM
        )
    }

    private fun geoToLocal(
        baseLat: Double,
        baseLon: Double,
        lat: Double,
        lon: Double
    ): Pair<Double, Double> {
        val dLat = Math.toRadians(lat - baseLat)
        val dLon = Math.toRadians(lon - baseLon)
        val meanLat = Math.toRadians((lat + baseLat) * 0.5)
        val east = EARTH_RADIUS_M * dLon * cos(meanLat)
        val north = EARTH_RADIUS_M * dLat
        return Pair(east, north)
    }

    private fun localToGeo(
        baseLat: Double,
        baseLon: Double,
        east: Double,
        north: Double
    ): Pair<Double, Double> {
        val lat = baseLat + Math.toDegrees(north / EARTH_RADIUS_M)
        val lon = baseLon + Math.toDegrees(east / (EARTH_RADIUS_M * cos(Math.toRadians(baseLat))))
        return Pair(lat, lon)
    }

    private fun blendAngle(current: Float, target: Float, alpha: Float): Float {
        val diff = angleDiff(target, current)
        return wrapRad(current + diff * alpha)
    }

    private fun angleDiff(target: Float, source: Float): Float {
        return wrapRad(target - source)
    }

    private fun wrapRad(rad: Float): Float {
        var r = rad
        while (r > PI) r = (r - (2.0 * PI)).toFloat()
        while (r < -PI) r = (r + (2.0 * PI)).toFloat()
        return r
    }

    private fun normalizeDeg(deg: Float): Float {
        var d = deg % 360f
        if (d < 0f) d += 360f
        return d
    }

    companion object {
        private const val EARTH_RADIUS_M = 6_378_137.0
    }
}
