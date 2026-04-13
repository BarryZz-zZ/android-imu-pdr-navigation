package com.example.myapplication

import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

data class ImuPoint(val ax: Float, val ay: Float, val az: Float, val gx: Float, val gy: Float, val gz: Float, val timestamp: Long)
data class LocationPoint(val longitude: Double, val latitude: Double, val gpsTimestamp: Long, val imuBuffer: List<ImuPoint>)

data class TrajectoryUpload(
    @SerializedName("user_id") val user_id: String,
    val points: List<LocationPoint>
)

data class UploadResponse(val status: String, val correction: List<Double>)

// ... 之前的代码 (保留 ImuPoint, LocationPoint, TrajectoryUpload, UploadResponse)

// --- 1. 新增：实时数据请求和响应模型 ---
// 注意：这里我们直接复用了你写好的 ImuPoint！
data class RealtimeTrajectoryRequest(
    val user_id: String,
    val start_lat: Double,
    val start_lon: Double,
    val imu_list: List<ImuPoint>
)

data class RealtimeTrajectoryResponse(
    val status: String,
    val corrected_lat: Double?,
    val corrected_lon: Double?,
    val delta_meters: List<Float>?,
    val msg: String?
)

// --- 2. 修改：在 ApiService 中增加实时接口 ---
interface ApiService {
    // 你的旧接口保留着没关系
    @POST("/api/trajectory/upload")
    suspend fun uploadTrajectory(@Body data: TrajectoryUpload): UploadResponse

    // 🔥 加上这行全新的实时纠偏接口
    @POST("/api/trajectory/realtime")
    suspend fun uploadRealtimeImu(@Body request: RealtimeTrajectoryRequest): RealtimeTrajectoryResponse
}

// RetrofitClient 保持不变！

object RetrofitClient {
    // ⚠️ 记得换成 Windows 本机的 Wi-Fi IP，它会通过 portproxy 转发给 WSL
    private const val BASE_URL = "http://10.138.173.167:8000"
    val apiService: ApiService by lazy {
        Retrofit.Builder().baseUrl(BASE_URL).addConverterFactory(GsonConverterFactory.create()).build().create(ApiService::class.java)
    }
}