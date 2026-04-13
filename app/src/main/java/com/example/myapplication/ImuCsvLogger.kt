package com.example.myapplication

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ImuCsvLogger(private val context: Context) {
    private var writer: BufferedWriter? = null
    private val lineBuffer = StringBuilder()
    private var filePath: String = ""

    @Synchronized
    fun startSession(): String {
        stopSession()
        val dir = File(context.filesDir, "pdr_logs")
        if (!dir.exists()) dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "imu_pdr_$ts.csv")
        writer = file.bufferedWriter()
        filePath = file.absolutePath
        writer?.append(
            "timestamp_ns,ax,ay,az,gx,gy,gz,mx,my,mz,heading_deg,steps,cadence_spm,distance_m,east_m,north_m,lat,lon,event\n"
        )
        writer?.flush()
        return filePath
    }

    @Synchronized
    fun logSample(sample: NineAxisSample, snapshot: PdrSnapshot, event: String = "") {
        if (writer == null) return
        lineBuffer.append(sample.timestampNs).append(',')
            .append(sample.ax).append(',')
            .append(sample.ay).append(',')
            .append(sample.az).append(',')
            .append(sample.gx).append(',')
            .append(sample.gy).append(',')
            .append(sample.gz).append(',')
            .append(sample.mx).append(',')
            .append(sample.my).append(',')
            .append(sample.mz).append(',')
            .append(snapshot.headingDeg).append(',')
            .append(snapshot.totalSteps).append(',')
            .append(snapshot.cadenceSpm).append(',')
            .append(snapshot.totalDistanceM).append(',')
            .append(snapshot.eastM).append(',')
            .append(snapshot.northM).append(',')
            .append(snapshot.lat).append(',')
            .append(snapshot.lon).append(',')
            .append(event)
            .append('\n')

        if (lineBuffer.length >= 16 * 1024) {
            flushUnsafe()
        }
    }

    @Synchronized
    fun stopSession() {
        flushUnsafe()
        writer?.close()
        writer = null
    }

    fun latestFilePath(): String = filePath

    @Synchronized
    private fun flushUnsafe() {
        if (lineBuffer.isEmpty()) return
        writer?.append(lineBuffer.toString())
        writer?.flush()
        lineBuffer.clear()
    }
}
