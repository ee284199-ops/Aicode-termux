package com.aicode.studio.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.aicode.studio.ai.gallery.data.KEY_MODEL_DOWNLOAD_ACCESS_TOKEN
import com.aicode.studio.ai.gallery.data.KEY_MODEL_DOWNLOAD_ERROR_MESSAGE
import com.aicode.studio.ai.gallery.data.KEY_MODEL_DOWNLOAD_FILE_NAME
import com.aicode.studio.ai.gallery.data.KEY_MODEL_DOWNLOAD_MODEL_DIR
import com.aicode.studio.ai.gallery.data.KEY_MODEL_DOWNLOAD_RATE
import com.aicode.studio.ai.gallery.data.KEY_MODEL_DOWNLOAD_RECEIVED_BYTES
import com.aicode.studio.ai.gallery.data.KEY_MODEL_DOWNLOAD_REMAINING_MS
import com.aicode.studio.ai.gallery.data.KEY_MODEL_NAME
import com.aicode.studio.ai.gallery.data.KEY_MODEL_TOTAL_BYTES
import com.aicode.studio.ai.gallery.data.KEY_MODEL_URL
import com.aicode.studio.ai.gallery.data.TMP_FILE_EXT
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "ModelDownloadWorker"
private const val CHANNEL_ID = "model_download_worker_channel"
private var channelCreated = false

/**
 * Gallery 1.0.11의 DownloadWorker 로직을 그대로 이식한 WorkManager 기반 다운로드 워커.
 * CoroutineScope 방식 대비 장점:
 *  - 앱 종료 후에도 다운로드 지속 (포그라운드 서비스)
 *  - Android 12+ 백그라운드 실행 제한 우회
 *  - Resume 다운로드 (Range 헤더)
 *  - 실시간 진행률 (setProgress)
 */
class DownloadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // params.id.hashCode()로 워커마다 고유한 notification ID 생성
    private val notificationId: Int = params.id.hashCode()

    init {
        if (!channelCreated) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "모델 다운로드",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "AI 모델 다운로드 진행 알림" }
            notificationManager.createNotificationChannel(channel)
            channelCreated = true
        }
    }

    override suspend fun doWork(): Result {
        val fileUrl = inputData.getString(KEY_MODEL_URL) ?: return Result.failure()
        val modelName = inputData.getString(KEY_MODEL_NAME) ?: "Model"
        val fileName = inputData.getString(KEY_MODEL_DOWNLOAD_FILE_NAME) ?: return Result.failure()
        val modelDir = inputData.getString(KEY_MODEL_DOWNLOAD_MODEL_DIR) ?: return Result.failure()
        val totalBytes = inputData.getLong(KEY_MODEL_TOTAL_BYTES, 0L)
        val accessToken = inputData.getString(KEY_MODEL_DOWNLOAD_ACCESS_TOKEN)

        return withContext(Dispatchers.IO) {
            try {
                // 즉시 포그라운드 서비스로 등록 (Gallery와 동일)
                setForeground(createForegroundInfo(0, modelName))

                // 출력 디렉토리: externalFilesDir/models/{modelDir}/
                val outputDir = File(
                    applicationContext.getExternalFilesDir("models"),
                    modelDir
                )
                if (!outputDir.exists()) outputDir.mkdirs()

                val outputTmpFile = File(outputDir, "$fileName.$TMP_FILE_EXT")
                val outputFile = File(outputDir, fileName)

                val url = URL(fileUrl)
                val connection = url.openConnection() as HttpURLConnection

                // HuggingFace 인증 토큰
                if (accessToken != null) {
                    Log.d(TAG, "인증 토큰 사용: ${accessToken.take(10)}...")
                    connection.setRequestProperty("Authorization", "Bearer $accessToken")
                }

                // Resume 다운로드 지원 (Gallery DownloadWorker.kt:157-165 참고)
                val existingBytes = outputTmpFile.length()
                if (existingBytes > 0) {
                    Log.d(TAG, "부분 파일 감지 (${existingBytes} bytes), Resume 시도")
                    connection.setRequestProperty("Range", "bytes=$existingBytes-")
                    connection.setRequestProperty("Accept-Encoding", "identity")
                }

                connection.connect()
                Log.d(TAG, "응답 코드: ${connection.responseCode}")

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK &&
                    responseCode != HttpURLConnection.HTTP_PARTIAL) {
                    val msg = when (responseCode) {
                        401 -> "인증 실패(401): HuggingFace 토큰을 입력하고 모델 페이지에서 라이선스에 동의하세요."
                        403 -> "접근 거부(403): 모델 페이지에서 라이선스 동의 후 재시도하세요."
                        404 -> "파일 없음(404): URL 또는 commitHash를 확인하세요."
                        else -> "HTTP 오류: $responseCode"
                    }
                    throw IOException(msg)
                }

                // Content-Range 파싱으로 시작 위치 계산 (Gallery DownloadWorker.kt:173-188 참고)
                var downloadedBytes = 0L
                if (connection.responseCode == HttpURLConnection.HTTP_PARTIAL) {
                    val contentRange = connection.getHeaderField("Content-Range")
                    if (contentRange != null) {
                        val rangeParts = contentRange.substringAfter("bytes ").split("/")
                        val byteRange = rangeParts[0].split("-")
                        val startByte = byteRange[0].toLong()
                        Log.d(TAG, "Content-Range: $contentRange, 시작 위치: $startByte")
                        downloadedBytes += startByte
                    }
                } else {
                    Log.d(TAG, "처음부터 다운로드")
                }

                val inputStream = connection.inputStream
                val outputStream = FileOutputStream(outputTmpFile, true /* append */)

                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var bytesRead: Int
                var lastProgressTs = 0L
                var deltaBytes = 0L
                // 이동 평균 계산용 버퍼 (Gallery와 동일, 최대 5샘플)
                val bytesReadSizeBuffer: MutableList<Long> = mutableListOf()
                val bytesReadLatencyBuffer: MutableList<Long> = mutableListOf()

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    deltaBytes += bytesRead

                    // 200ms마다 진행률 보고 (Gallery DownloadWorker.kt:207-246 동일)
                    val curTs = System.currentTimeMillis()
                    if (curTs - lastProgressTs > 200) {
                        var bytesPerMs = 0f
                        if (lastProgressTs != 0L) {
                            if (bytesReadSizeBuffer.size == 5) bytesReadSizeBuffer.removeAt(0)
                            bytesReadSizeBuffer.add(deltaBytes)
                            if (bytesReadLatencyBuffer.size == 5) bytesReadLatencyBuffer.removeAt(0)
                            bytesReadLatencyBuffer.add(curTs - lastProgressTs)
                            deltaBytes = 0L
                            bytesPerMs =
                                bytesReadSizeBuffer.sum().toFloat() / bytesReadLatencyBuffer.sum()
                        }

                        var remainingMs = 0f
                        if (bytesPerMs > 0f && totalBytes > 0L) {
                            remainingMs = (totalBytes - downloadedBytes) / bytesPerMs
                        }

                        setProgress(
                            Data.Builder()
                                .putLong(KEY_MODEL_DOWNLOAD_RECEIVED_BYTES, downloadedBytes)
                                .putLong(KEY_MODEL_DOWNLOAD_RATE, (bytesPerMs * 1000).toLong())
                                .putLong(KEY_MODEL_DOWNLOAD_REMAINING_MS, remainingMs.toLong())
                                .build()
                        )
                        setForeground(
                            createForegroundInfo(
                                progress = if (totalBytes > 0) (downloadedBytes * 100 / totalBytes).toInt() else 0,
                                modelName = modelName
                            )
                        )
                        Log.d(TAG, "다운로드: $downloadedBytes / $totalBytes bytes")
                        lastProgressTs = curTs
                    }
                }

                outputStream.close()
                inputStream.close()

                // tmp 파일을 최종 파일명으로 rename (Gallery DownloadWorker.kt:252-258 동일)
                if (outputFile.exists()) outputFile.delete()
                outputTmpFile.renameTo(outputFile)
                Log.d(TAG, "다운로드 완료: $fileName")

                Result.success()
            } catch (e: IOException) {
                Log.e(TAG, "다운로드 실패", e)
                Result.failure(
                    Data.Builder()
                        .putString(KEY_MODEL_DOWNLOAD_ERROR_MESSAGE, e.message)
                        .build()
                )
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo(0)

    private fun createForegroundInfo(progress: Int, modelName: String? = null): ForegroundInfo {
        val title = if (modelName != null) "\"$modelName\" 다운로드 중" else "모델 다운로드"
        val content = "진행 중: $progress%"

        val intent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)!!
            .apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, progress, progress == 0)
            .setContentIntent(pendingIntent)
            .build()

        return ForegroundInfo(
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }
}
