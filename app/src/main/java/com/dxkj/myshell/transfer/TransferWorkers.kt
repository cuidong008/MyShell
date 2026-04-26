package com.dxkj.myshell.transfer

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dxkj.myshell.data.db.DbProvider
import com.dxkj.myshell.data.repo.HostRepository
import com.dxkj.myshell.data.repo.KeyRepository
import com.dxkj.myshell.sftp.SftpClientManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object TransferKeys {
    const val TAG = "transfer"
    const val KEY_KIND = "kind" // "upload" | "download"
    const val KEY_HOST_ID = "hostId"
    const val KEY_REMOTE_PATH = "remotePath"
    const val KEY_REMOTE_DIR = "remoteDir"
    const val KEY_FILENAME = "filename"
    const val KEY_URI = "uri"
    const val KEY_PROGRESS_SENT = "sent"
    const val KEY_PROGRESS_TOTAL = "total"
    const val KEY_MESSAGE = "message"
}

class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val hostId = inputData.getLong(TransferKeys.KEY_HOST_ID, -1L)
        val remotePath = inputData.getString(TransferKeys.KEY_REMOTE_PATH) ?: return Result.failure()
        val filename = inputData.getString(TransferKeys.KEY_FILENAME) ?: "download.bin"
        if (hostId <= 0) return Result.failure()

        val db = DbProvider.get(applicationContext)
        val hostRepo = HostRepository(db.hostDao())
        val keyRepo = KeyRepository(db.keyDao())
        val sftp = SftpClientManager(keyRepo = keyRepo)

        val host = withContext(Dispatchers.IO) { hostRepo.getById(hostId) } ?: return Result.failure(
            workDataOf(TransferKeys.KEY_MESSAGE to "主机不存在"),
        )

        val r = withContext(Dispatchers.IO) { sftp.connect(host) }
        if (!r.ok) return Result.retry()

        try {
            setForeground(simpleForeground("下载中：$filename"))
            val msg = sftp.downloadToDownloads(
                remotePath = remotePath,
                filename = filename,
                context = applicationContext,
                resolver = applicationContext.contentResolver,
                onProgress = { sent, total ->
                    setProgressAsync(
                        workDataOf(
                            TransferKeys.KEY_PROGRESS_SENT to sent,
                            TransferKeys.KEY_PROGRESS_TOTAL to total,
                            TransferKeys.KEY_MESSAGE to "下载 ${sent}/${if (total > 0) total else -1}",
                        ),
                    )
                },
            )
            return if (msg.startsWith("下载完成")) {
                Result.success(workDataOf(TransferKeys.KEY_MESSAGE to msg, TransferKeys.KEY_KIND to "download", TransferKeys.KEY_FILENAME to filename))
            } else {
                Result.failure(workDataOf(TransferKeys.KEY_MESSAGE to msg, TransferKeys.KEY_KIND to "download", TransferKeys.KEY_FILENAME to filename))
            }
        } finally {
            sftp.disconnect()
        }
    }

    private fun simpleForeground(title: String): ForegroundInfo {
        // 为了最小可用，这里不创建通知渠道；WorkManager 在多数环境仍可运行。
        // 若厂商 ROM 强杀，可再升级为真正的前台通知。
        val n = androidx.core.app.NotificationCompat.Builder(applicationContext, "transfer")
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
        return ForegroundInfo(1001, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}

class UploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val hostId = inputData.getLong(TransferKeys.KEY_HOST_ID, -1L)
        val remoteDir = inputData.getString(TransferKeys.KEY_REMOTE_DIR) ?: return Result.failure()
        val uriStr = inputData.getString(TransferKeys.KEY_URI) ?: return Result.failure()
        if (hostId <= 0) return Result.failure()
        val uri = Uri.parse(uriStr)

        val db = DbProvider.get(applicationContext)
        val hostRepo = HostRepository(db.hostDao())
        val keyRepo = KeyRepository(db.keyDao())
        val sftp = SftpClientManager(keyRepo = keyRepo)

        val host = withContext(Dispatchers.IO) { hostRepo.getById(hostId) } ?: return Result.failure(
            workDataOf(TransferKeys.KEY_MESSAGE to "主机不存在"),
        )

        val r = withContext(Dispatchers.IO) { sftp.connect(host) }
        if (!r.ok) {
            Log.w("MyShell-Transfer", "upload connect failed: hostId=$hostId msg=${r.message}")
            return Result.retry()
        }

        try {
            setForeground(simpleForeground("上传中…"))
            val msg = runCatching {
                sftp.uploadFromUri(
                    remoteDir = remoteDir,
                    uri = uri,
                    resolver = applicationContext.contentResolver,
                    onProgress = { sent, total ->
                        setProgressAsync(
                            workDataOf(
                                TransferKeys.KEY_PROGRESS_SENT to sent,
                                TransferKeys.KEY_PROGRESS_TOTAL to total,
                                TransferKeys.KEY_MESSAGE to "上传 ${sent}/${if (total > 0) total else -1}",
                            ),
                        )
                    },
                )
            }.getOrElse { t ->
                Log.e("MyShell-Transfer", "upload failed: hostId=$hostId dir=$remoteDir uri=$uriStr", t)
                "上传失败：${t::class.java.simpleName}：${t.message ?: "unknown"}"
            }
            return if (msg.startsWith("上传完成")) {
                Result.success(workDataOf(TransferKeys.KEY_MESSAGE to msg, TransferKeys.KEY_KIND to "upload"))
            } else {
                Log.w("MyShell-Transfer", "upload returned failure: $msg")
                Result.failure(workDataOf(TransferKeys.KEY_MESSAGE to msg, TransferKeys.KEY_KIND to "upload"))
            }
        } finally {
            sftp.disconnect()
        }
    }

    private fun simpleForeground(title: String): ForegroundInfo {
        val n = androidx.core.app.NotificationCompat.Builder(applicationContext, "transfer")
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        return ForegroundInfo(1002, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}

