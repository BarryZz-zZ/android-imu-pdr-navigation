package com.example.myapplication

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class AhrsMode {
    SIX_AXIS,
    NINE_AXIS
}

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

data class PdrRealtimeState(
    val lat: Double,
    val lon: Double,
    val eastM: Double,
    val northM: Double,
    val headingDeg: Float,
    val rollDeg: Float,
    val pitchDeg: Float,
    val yawDeg: Float,
    val totalSteps: Int,
    val cadenceSpm: Int,
    val stepFrequencyHz: Double,
    val lastStepLengthM: Double,
    val totalDistanceM: Double,
    val ahrsMode: AhrsMode,
    val rawAccNorm: Float,
    val filteredVerticalAcc: Float,
    val gyroNorm: Float,
    val magNorm: Float,
    val isInterrupted: Boolean,
    val interruptCount: Int,
    val lastStepIntervalS: Double,
    val lastCorrection: String
)

data class PdrStepResult(
    val lat: Double,
    val lon: Double,
    val headingDeg: Float,
    val totalSteps: Int,
    val cadenceSpm: Int,
    val lastStepLengthM: Double,
    val totalDistanceM: Double,
    val isInterrupted: Boolean
)

typealias PdrSnapshot = PdrRealtimeState

data class PdrProcessResult(
    val state: PdrRealtimeState,
    val step: PdrStepResult?
)

class NineAxisPdrEngine(
    private var userHeightM: Double = 1.72
) {
    private var started = false
    private var mode = AhrsMode.NINE_AXIS

    private var startLat = 0.0
    private var startLon = 0.0
    private var eastM = 0.0
    private var northM = 0.0
    private var totalDistanceM = 0.0

    private var totalSteps = 0
    private var cadenceSpm = 0
    private var lastStepLengthM = 0.0
    private var lastStepIntervalS = 0.0
    private var interruptCount = 0
    private var interrupted = false
    private var lastCorrection = "NONE"

    private val ahrs = MahonyAhrs()
    private var lastSampleNs = 0L

    private val smoothAcc = FloatArray(3)
    private val smoothGyro = FloatArray(3)
    private val smoothMag = FloatArray(3)
    private var hasMag = false

    private var prev2Vertical = 0f
    private var prev1Vertical = 0f
    private var prev2TimeNs = 0L
    private var prev1TimeNs = 0L
    private var adaptivePeakThreshold = 0.75f
    private var lastStepNs = 0L
    private val recentStepTimesMs = ArrayDeque<Long>()
    private val recentStepIntervalsS = ArrayDeque<Double>()

    fun reset(
        startLat: Double,
        startLon: Double,
        initialHeadingDeg: Float? = null,
        ahrsMode: AhrsMode = AhrsMode.NINE_AXIS,
        userHeightM: Double = this.userHeightM
    ) {
        this.startLat = startLat
        this.startLon = startLon
        this.userHeightM = userHeightM
        mode = ahrsMode
        eastM = 0.0
        northM = 0.0
        totalDistanceM = 0.0
        totalSteps = 0
        cadenceSpm = 0
        lastStepLengthM = 0.0
        lastStepIntervalS = 0.0
        interruptCount = 0
        interrupted = false
        lastCorrection = "INIT"

        smoothAcc.fill(0f)
        smoothGyro.fill(0f)
        smoothMag.fill(0f)
        hasMag = false
        lastSampleNs = 0L

        prev2Vertical = 0f
        prev1Vertical = 0f
        prev2TimeNs = 0L
        prev1TimeNs = 0L
        adaptivePeakThreshold = 0.75f
        lastStepNs = 0L
        recentStepTimesMs.clear()
        recentStepIntervalsS.clear()

        ahrs.reset()
        initialHeadingDeg?.let { ahrs.setHeadingDegrees(it) }
        started = true
    }

    fun clear() {
        started = false
    }

    fun isStarted(): Boolean = started

    fun currentMode(): AhrsMode = mode

    fun setUserHeightMeters(heightMeters: Double) {
        userHeightM = heightMeters.coerceIn(1.2, 2.2)
    }

    fun processSample(sample: NineAxisSample): PdrProcessResult? {
        if (!started) return null

        val dt = if (lastSampleNs > 0L) {
            (sample.timestampNs - lastSampleNs) / 1_000_000_000.0
        } else {
            0.0
        }
        lastSampleNs = sample.timestampNs
        if (dt <= 0.0 || dt > 0.25) {
            return snapshot(
                rawAccNorm = vectorNorm(sample.ax, sample.ay, sample.az),
                filteredVerticalAcc = prev1Vertical,
                gyroNorm = vectorNorm(sample.gx, sample.gy, sample.gz),
                magNorm = vectorNorm(sample.mx, sample.my, sample.mz)
            ).let { PdrProcessResult(it, null) }
        }

        smoothSensor(smoothAcc, sample.ax, sample.ay, sample.az, 0.20f)
        smoothSensor(smoothGyro, sample.gx, sample.gy, sample.gz, 0.35f)
        smoothSensor(smoothMag, sample.mx, sample.my, sample.mz, 0.18f)
        val magNorm = vectorNorm(smoothMag[0], smoothMag[1], smoothMag[2])
        hasMag = hasMag || magNorm > 5f

        if (mode == AhrsMode.NINE_AXIS && hasMag) {
            ahrs.updateNineAxis(
                gx = smoothGyro[0],
                gy = smoothGyro[1],
                gz = smoothGyro[2],
                ax = smoothAcc[0],
                ay = smoothAcc[1],
                az = smoothAcc[2],
                mx = smoothMag[0],
                my = smoothMag[1],
                mz = smoothMag[2],
                dt = dt
            )
        } else {
            ahrs.updateSixAxis(
                gx = smoothGyro[0],
                gy = smoothGyro[1],
                gz = smoothGyro[2],
                ax = smoothAcc[0],
                ay = smoothAcc[1],
                az = smoothAcc[2],
                dt = dt
            )
        }

        val gravity = ahrs.gravityVector()
        val linearX = smoothAcc[0] - gravity[0] * GRAVITY_EARTH
        val linearY = smoothAcc[1] - gravity[1] * GRAVITY_EARTH
        val linearZ = smoothAcc[2] - gravity[2] * GRAVITY_EARTH
        val verticalAcc = linearX * gravity[0] + linearY * gravity[1] + linearZ * gravity[2]
        adaptivePeakThreshold = (0.96f * adaptivePeakThreshold + 0.04f * kotlin.math.abs(verticalAcc) * 1.8f)
            .coerceIn(0.55f, 1.65f)

        val step = detectAndProcessStep(
            sampleTimeNs = sample.timestampNs,
            verticalAcc = verticalAcc,
            gyroNorm = vectorNorm(smoothGyro[0], smoothGyro[1], smoothGyro[2]),
            magNorm = magNorm
        )

        val state = snapshot(
            rawAccNorm = vectorNorm(sample.ax, sample.ay, sample.az),
            filteredVerticalAcc = verticalAcc,
            gyroNorm = vectorNorm(smoothGyro[0], smoothGyro[1], smoothGyro[2]),
            magNorm = magNorm
        )
        return PdrProcessResult(state, step)
    }

    fun correctWithGps(gpsLat: Double, gpsLon: Double, accuracyM: Float) {
        if (!started || accuracyM > 25f) return
        val (gpsEast, gpsNorth) = geoToLocal(startLat, startLon, gpsLat, gpsLon)
        val alpha = when {
            accuracyM <= 5f -> 0.20
            accuracyM <= 10f -> 0.12
            else -> 0.06
        }
        eastM = eastM * (1.0 - alpha) + gpsEast * alpha
        northM = northM * (1.0 - alpha) + gpsNorth * alpha
        lastCorrection = "GPS"
    }

    fun updateHeadingAssist(gpsBearingDeg: Float, speedMps: Float, accuracyM: Float) {
        if (!started || speedMps < 0.8f || accuracyM > 20f) return
        ahrs.blendHeadingDegrees(gpsBearingDeg, if (accuracyM <= 8f) 0.08f else 0.04f)
        lastCorrection = "GPS_HEADING"
    }

    fun realignHeadingFromMagnetic() {
        if (!hasMag) return
        ahrs.realignYawFromMag(smoothMag[0], smoothMag[1], smoothMag[2], strength = 0.72f)
        lastCorrection = "MAG_REINIT"
    }

    fun snapshot(
        rawAccNorm: Float = 0f,
        filteredVerticalAcc: Float = 0f,
        gyroNorm: Float = 0f,
        magNorm: Float = 0f
    ): PdrRealtimeState {
        val (lat, lon) = if (started) {
            localToGeo(startLat, startLon, eastM, northM)
        } else {
            Pair(Double.NaN, Double.NaN)
        }
        return PdrRealtimeState(
            lat = lat,
            lon = lon,
            eastM = eastM,
            northM = northM,
            headingDeg = normalizeDegrees(ahrs.headingDegrees()),
            rollDeg = ahrs.rollDegrees(),
            pitchDeg = ahrs.pitchDegrees(),
            yawDeg = normalizeDegrees(ahrs.yawDegrees()),
            totalSteps = totalSteps,
            cadenceSpm = cadenceSpm,
            stepFrequencyHz = if (cadenceSpm > 0) cadenceSpm / 60.0 else 0.0,
            lastStepLengthM = lastStepLengthM,
            totalDistanceM = totalDistanceM,
            ahrsMode = mode,
            rawAccNorm = rawAccNorm,
            filteredVerticalAcc = filteredVerticalAcc,
            gyroNorm = gyroNorm,
            magNorm = magNorm,
            isInterrupted = interrupted,
            interruptCount = interruptCount,
            lastStepIntervalS = lastStepIntervalS,
            lastCorrection = lastCorrection
        )
    }

    private fun detectAndProcessStep(
        sampleTimeNs: Long,
        verticalAcc: Float,
        gyroNorm: Float,
        magNorm: Float
    ): PdrStepResult? {
        val detectedPeak = prev1TimeNs > 0L &&
            prev1Vertical > prev2Vertical &&
            prev1Vertical > verticalAcc &&
            prev1Vertical > adaptivePeakThreshold &&
            gyroNorm > 0.18f

        val candidateTimeNs = prev1TimeNs
        val candidatePeak = prev1Vertical

        prev2Vertical = prev1Vertical
        prev1Vertical = verticalAcc
        prev2TimeNs = prev1TimeNs
        prev1TimeNs = sampleTimeNs

        if (!detectedPeak) return null

        val candidateIntervalS = if (lastStepNs > 0L) {
            (candidateTimeNs - lastStepNs) / 1_000_000_000.0
        } else {
            0.0
        }

        if (lastStepNs > 0L && candidateIntervalS < MIN_STEP_INTERVAL_S) return null
        if (lastStepNs > 0L && candidateIntervalS > INTERRUPTION_INTERVAL_S) {
            interrupted = true
            interruptCount += 1
            if (magNorm > 5f) {
                realignHeadingFromMagnetic()
            }
        }

        lastStepIntervalS = candidateIntervalS
        lastStepNs = candidateTimeNs
        totalSteps += 1
        val nowMs = candidateTimeNs / 1_000_000L
        recentStepTimesMs.addLast(nowMs)
        while (recentStepTimesMs.isNotEmpty() && nowMs - recentStepTimesMs.first() > 10_000L) {
            recentStepTimesMs.removeFirst()
        }
        cadenceSpm = recentStepTimesMs.size * 6

        if (candidateIntervalS > 0.0) {
            recentStepIntervalsS.addLast(candidateIntervalS)
            while (recentStepIntervalsS.size > 4) {
                recentStepIntervalsS.removeFirst()
            }
        }

        val smoothedInterval = if (recentStepIntervalsS.isNotEmpty()) {
            recentStepIntervalsS.average()
        } else {
            max(candidateIntervalS, 0.55)
        }
        val stepFrequencyHz = if (smoothedInterval > 0.0) 1.0 / smoothedInterval else 0.0
        val amplitudeTerm = sqrt(max(candidatePeak, 0.45f).toDouble())
        val frequencyTerm = stepFrequencyHz.coerceIn(0.8, 3.2)
        lastStepLengthM = (
            0.22 * userHeightM +
                0.18 * amplitudeTerm +
                0.16 * frequencyTerm
            ).coerceIn(0.35, 1.15)

        totalDistanceM += lastStepLengthM
        val headingRad = Math.toRadians(ahrs.headingDegrees().toDouble())
        eastM += lastStepLengthM * sin(headingRad)
        northM += lastStepLengthM * cos(headingRad)
        val (lat, lon) = localToGeo(startLat, startLon, eastM, northM)
        val stepInterrupted = interrupted
        interrupted = false
        if (lastCorrection == "NONE" || lastCorrection == "GPS_HEADING") {
            lastCorrection = "STEP"
        }

        return PdrStepResult(
            lat = lat,
            lon = lon,
            headingDeg = normalizeDegrees(ahrs.headingDegrees()),
            totalSteps = totalSteps,
            cadenceSpm = cadenceSpm,
            lastStepLengthM = lastStepLengthM,
            totalDistanceM = totalDistanceM,
            isInterrupted = stepInterrupted
        )
    }

    private fun smoothSensor(target: FloatArray, x: Float, y: Float, z: Float, alpha: Float) {
        if (target[0] == 0f && target[1] == 0f && target[2] == 0f) {
            target[0] = x
            target[1] = y
            target[2] = z
        } else {
            target[0] += alpha * (x - target[0])
            target[1] += alpha * (y - target[1])
            target[2] += alpha * (z - target[2])
        }
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

    companion object {
        private const val EARTH_RADIUS_M = 6_378_137.0
        private const val GRAVITY_EARTH = 9.80665f
        private const val MIN_STEP_INTERVAL_S = 0.28
        private const val INTERRUPTION_INTERVAL_S = 1.5
    }
}

private class MahonyAhrs(
    private val kp: Float = 1.2f,
    private val ki: Float = 0.04f
) {
    private var q0 = 1f
    private var q1 = 0f
    private var q2 = 0f
    private var q3 = 0f

    private var exInt = 0f
    private var eyInt = 0f
    private var ezInt = 0f

    fun reset() {
        q0 = 1f
        q1 = 0f
        q2 = 0f
        q3 = 0f
        exInt = 0f
        eyInt = 0f
        ezInt = 0f
    }

    fun setHeadingDegrees(headingDeg: Float) {
        val yaw = Math.toRadians(headingDeg.toDouble()).toFloat()
        val cy = kotlin.math.cos(yaw * 0.5f)
        val sy = kotlin.math.sin(yaw * 0.5f)
        q0 = cy
        q1 = 0f
        q2 = 0f
        q3 = sy
    }

    fun blendHeadingDegrees(targetDeg: Float, alpha: Float) {
        val current = headingDegrees()
        val blended = normalizeDegrees(current + angleDeltaDegrees(targetDeg, current) * alpha)
        setYawKeepingRollPitch(blended)
    }

    fun realignYawFromMag(mx: Float, my: Float, mz: Float, strength: Float) {
        val roll = rollRadians()
        val pitch = pitchRadians()
        val mx2 = mx * kotlin.math.cos(pitch) + mz * kotlin.math.sin(pitch)
        val my2 = mx * kotlin.math.sin(roll) * kotlin.math.sin(pitch) +
            my * kotlin.math.cos(roll) -
            mz * kotlin.math.sin(roll) * kotlin.math.cos(pitch)
        val yawDeg = normalizeDegrees(Math.toDegrees(atan2(-my2.toDouble(), mx2.toDouble())).toFloat())
        val current = headingDegrees()
        val corrected = normalizeDegrees(current + angleDeltaDegrees(yawDeg, current) * strength)
        setYawKeepingRollPitch(corrected)
    }

    fun updateSixAxis(
        gx: Float,
        gy: Float,
        gz: Float,
        ax: Float,
        ay: Float,
        az: Float,
        dt: Double
    ) {
        var axN = ax
        var ayN = ay
        var azN = az
        val norm = vectorNorm(axN, ayN, azN)
        if (norm < 1e-6f) return
        axN /= norm
        ayN /= norm
        azN /= norm

        val vx = 2f * (q1 * q3 - q0 * q2)
        val vy = 2f * (q0 * q1 + q2 * q3)
        val vz = q0 * q0 - q1 * q1 - q2 * q2 + q3 * q3

        val ex = ayN * vz - azN * vy
        val ey = azN * vx - axN * vz
        val ez = axN * vy - ayN * vx

        integrateError(ex, ey, ez, dt)
        updateQuaternion(gx, gy, gz, ex, ey, ez, dt)
    }

    fun updateNineAxis(
        gx: Float,
        gy: Float,
        gz: Float,
        ax: Float,
        ay: Float,
        az: Float,
        mx: Float,
        my: Float,
        mz: Float,
        dt: Double
    ) {
        var axN = ax
        var ayN = ay
        var azN = az
        var mxN = mx
        var myN = my
        var mzN = mz

        val accNorm = vectorNorm(axN, ayN, azN)
        val magNorm = vectorNorm(mxN, myN, mzN)
        if (accNorm < 1e-6f) return
        axN /= accNorm
        ayN /= accNorm
        azN /= accNorm

        if (magNorm < 1e-6f) {
            updateSixAxis(gx, gy, gz, ax, ay, az, dt)
            return
        }
        mxN /= magNorm
        myN /= magNorm
        mzN /= magNorm

        val vx = 2f * (q1 * q3 - q0 * q2)
        val vy = 2f * (q0 * q1 + q2 * q3)
        val vz = q0 * q0 - q1 * q1 - q2 * q2 + q3 * q3

        val hx = 2f * mxN * (0.5f - q2 * q2 - q3 * q3) +
            2f * myN * (q1 * q2 - q0 * q3) +
            2f * mzN * (q1 * q3 + q0 * q2)
        val hy = 2f * mxN * (q1 * q2 + q0 * q3) +
            2f * myN * (0.5f - q1 * q1 - q3 * q3) +
            2f * mzN * (q2 * q3 - q0 * q1)
        val bx = sqrt(hx * hx + hy * hy)
        val bz = 2f * mxN * (q1 * q3 - q0 * q2) +
            2f * myN * (q2 * q3 + q0 * q1) +
            2f * mzN * (0.5f - q1 * q1 - q2 * q2)

        val wx = 2f * bx * (0.5f - q2 * q2 - q3 * q3) + 2f * bz * (q1 * q3 - q0 * q2)
        val wy = 2f * bx * (q1 * q2 - q0 * q3) + 2f * bz * (q0 * q1 + q2 * q3)
        val wz = 2f * bx * (q0 * q2 + q1 * q3) + 2f * bz * (0.5f - q1 * q1 - q2 * q2)

        val ex = (ayN * vz - azN * vy) + (myN * wz - mzN * wy)
        val ey = (azN * vx - axN * vz) + (mzN * wx - mxN * wz)
        val ez = (axN * vy - ayN * vx) + (mxN * wy - myN * wx)

        integrateError(ex, ey, ez, dt)
        updateQuaternion(gx, gy, gz, ex, ey, ez, dt)
    }

    fun gravityVector(): FloatArray {
        return floatArrayOf(
            2f * (q1 * q3 - q0 * q2),
            2f * (q0 * q1 + q2 * q3),
            q0 * q0 - q1 * q1 - q2 * q2 + q3 * q3
        )
    }

    fun rollDegrees(): Float = Math.toDegrees(rollRadians().toDouble()).toFloat()

    fun pitchDegrees(): Float = Math.toDegrees(pitchRadians().toDouble()).toFloat()

    fun yawDegrees(): Float = Math.toDegrees(yawRadians().toDouble()).toFloat()

    fun headingDegrees(): Float = normalizeDegrees(yawDegrees())

    private fun rollRadians(): Float = atan2(
        2f * (q0 * q1 + q2 * q3),
        1f - 2f * (q1 * q1 + q2 * q2)
    )

    private fun pitchRadians(): Float {
        val value = (2f * (q0 * q2 - q3 * q1)).coerceIn(-1f, 1f)
        return asin(value)
    }

    private fun yawRadians(): Float = atan2(
        2f * (q0 * q3 + q1 * q2),
        1f - 2f * (q2 * q2 + q3 * q3)
    )

    private fun integrateError(ex: Float, ey: Float, ez: Float, dt: Double) {
        exInt += ex * ki * dt.toFloat()
        eyInt += ey * ki * dt.toFloat()
        ezInt += ez * ki * dt.toFloat()
    }

    private fun updateQuaternion(
        gx: Float,
        gy: Float,
        gz: Float,
        ex: Float,
        ey: Float,
        ez: Float,
        dt: Double
    ) {
        val gxAdj = gx + kp * ex + exInt
        val gyAdj = gy + kp * ey + eyInt
        val gzAdj = gz + kp * ez + ezInt

        val halfDt = 0.5f * dt.toFloat()
        val qa = q0
        val qb = q1
        val qc = q2
        val qd = q3
        q0 += (-qb * gxAdj - qc * gyAdj - qd * gzAdj) * halfDt
        q1 += (qa * gxAdj + qc * gzAdj - qd * gyAdj) * halfDt
        q2 += (qa * gyAdj - qb * gzAdj + qd * gxAdj) * halfDt
        q3 += (qa * gzAdj + qb * gyAdj - qc * gxAdj) * halfDt

        val norm = sqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3)
        q0 /= norm
        q1 /= norm
        q2 /= norm
        q3 /= norm
    }

    private fun setYawKeepingRollPitch(yawDeg: Float) {
        val roll = rollRadians()
        val pitch = pitchRadians()
        val yaw = Math.toRadians(yawDeg.toDouble()).toFloat()

        val cr = kotlin.math.cos(roll * 0.5f)
        val sr = kotlin.math.sin(roll * 0.5f)
        val cp = kotlin.math.cos(pitch * 0.5f)
        val sp = kotlin.math.sin(pitch * 0.5f)
        val cy = kotlin.math.cos(yaw * 0.5f)
        val sy = kotlin.math.sin(yaw * 0.5f)

        q0 = cr * cp * cy + sr * sp * sy
        q1 = sr * cp * cy - cr * sp * sy
        q2 = cr * sp * cy + sr * cp * sy
        q3 = cr * cp * sy - sr * sp * cy
    }
}

private fun vectorNorm(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)

private fun angleDeltaDegrees(target: Float, source: Float): Float {
    var delta = (target - source) % 360f
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    return delta
}

private fun normalizeDegrees(value: Float): Float {
    var normalized = value % 360f
    if (normalized < 0f) normalized += 360f
    return normalized
}
