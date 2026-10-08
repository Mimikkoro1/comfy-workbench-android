package com.mie.kreaworkbench.data.api

import com.mie.kreaworkbench.KreaApp
import com.mie.kreaworkbench.R
import com.mie.kreaworkbench.data.settings.SettingsStore
import com.mie.kreaworkbench.ui.locale.str
import com.mie.kreaworkbench.util.mimeForVideo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ByteProgress(
    val done: Long,
    val total: Long,
    val bytesPerSec: Double,
) {
    val fraction: Float
        get() = if (total > 0L) (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) else -1f
}

class Transfer(private val settings: SettingsStore, auth: Interceptor? = null) {
    private val http = fileClient(auth)

    suspend fun upload(file: File, onProgress: (Float) -> Unit): Pair<String, String> {
        var last: Exception? = null
        val backoff = longArrayOf(0L, 1000L, 3000L, 6000L)
        for (i in 0 until 4) {
            if (backoff[i] > 0) delay(backoff[i])
            try {
                return doUpload(file, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (!e.network || i == 3) throw e
                last = e
            } catch (e: IOException) {
                last = e
                if (i == 3) throw friendlyIo()
            }
        }
        throw (last as? ApiException) ?: friendlyIo()
    }

    /**
     * 原图直传的单次尝试，不设 callTimeout、不重试。
     * 满 10 秒只由界面计时弹出选择，到点不取消这条请求；用户选压缩时由调用方取消协程。
     */
    suspend fun uploadOriginal(file: File, onProgress: (Float) -> Unit): Pair<String, String> =
        withContext(Dispatchers.IO) {
            val base = settings.baseUrl()
            val body = ProgressBody(file, mimeFor(file)) { sent, total ->
                if (total > 0) onProgress(sent.toFloat() / total.toFloat())
            }
            executeUpload(http, base, file, body)
        }

    /**
     * 视频直传（round17）：单次尝试、不设 callTimeout、不压缩，网络重试由引擎负责。
     * 字段同图片（image/type/input/subfolder=kwb_mobile/overwrite=false），走同一个 /upload/image。
     */
    suspend fun uploadVideo(file: File, onProgress: (Float) -> Unit): Pair<String, String> =
        withContext(Dispatchers.IO) {
            val base = settings.baseUrl()
            val body = ProgressBody(file, mimeFor(file)) { sent, total ->
                if (total > 0) onProgress(sent.toFloat() / total.toFloat())
            }
            executeUpload(http, base, file, body)
        }

    private suspend fun doUpload(file: File, onProgress: (Float) -> Unit): Pair<String, String> = withContext(Dispatchers.IO) {
        val base = settings.baseUrl()
        val body = ProgressBody(file, "image/jpeg") { sent, total ->
            if (total > 0) onProgress(sent.toFloat() / total.toFloat())
        }
        executeUpload(http, base, file, body)
    }

    private suspend fun executeUpload(client: OkHttpClient, base: String, file: File, body: RequestBody): Pair<String, String> {
        // ComfyUI 直连上传（0.29.0 server.py image_upload）：multipart 字段 image/type/subfolder/overwrite。
        // 手机上传统一归到 input/kwb_mobile/ 子文件夹；节点 10 填「kwb_mobile/<name>」是安全的——
        // LoadImage 定义了 VALIDATE_INPUTS(s, image)（nodes.py:1791，只做 exists_annotated_filepath 文件系统检查），
        // execution.py:1012 对该输入跳过 combo 列表检查，子路径不会被 value_not_in_list 拒掉
        val mp = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("image", file.name, body)
            .addFormDataPart("type", "input")
            .addFormDataPart("subfolder", "kwb_mobile")
            .addFormDataPart("overwrite", "false")
            .build()
        val req = Request.Builder().url("$base/upload/image").post(mp).build()
        return client.callCancellable(req) { resp ->
            val text = resp.body?.string().orEmpty()
            val json = if (text.trimStart().startsWith("{")) JSONObject(text) else JSONObject()
            if (!resp.isSuccessful) {
                // ComfyUI 4xx 常返回纯文本而非 JSON：拿不到 error 字段就用 httpFallback；5xx 仍算网络错误可重试
                if (resp.code == 401) throw authError()
                val net = resp.code >= 500
                throw ApiException(json.optString("error").ifBlank { httpFallback(resp.code) }, net)
            }
            val name = json.optString("name")
            if (name.isBlank()) throw ApiException(KreaApp.instance.str(R.string.err_upload_no_name))
            name to json.optString("subfolder")
        }
    }

    /**
     * 下载到 dest。返回服务器给出的 Content-Type（用于决定扩展名/MIME），拿不到返回 null。
     */
    suspend fun download(urlPath: String, dest: File, onProgress: (ByteProgress) -> Unit): String? {
        val base = settings.baseUrl()
        val url = if (urlPath.startsWith("http")) urlPath else base + urlPath
        var last: Exception? = null
        val backoff = longArrayOf(0L, 1000L, 3000L, 6000L)
        for (i in 0 until 4) {
            if (backoff[i] > 0) delay(backoff[i])
            try {
                return withContext(Dispatchers.IO) { downloadOnce(url, dest, onProgress) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (!e.network || i == 3) throw e
                last = e
            } catch (e: IOException) {
                last = e
                if (i == 3) throw friendlyIo()
            }
        }
        throw (last as? ApiException) ?: friendlyIo()
    }

    private suspend fun downloadOnce(url: String, dest: File, onProgress: (ByteProgress) -> Unit): String? {
        dest.parentFile?.mkdirs()
        val part = File(dest.absolutePath + ".part")
        val existing = if (part.exists()) part.length() else 0L
        val builder = Request.Builder().url(url).get()
        if (existing > 0) builder.header("Range", "bytes=$existing-")
        var contentType: String? = null
        http.callCancellable(builder.build()) { resp ->
            contentType = resp.header("Content-Type")
            when (resp.code) {
                206 -> writeBody(resp.body?.byteStream(), part, append = true, existing, resp, onProgress)
                200 -> {
                    if (part.exists()) part.delete()
                    writeBody(resp.body?.byteStream(), part, append = false, 0L, resp, onProgress)
                }
                416 -> {
                    if (!part.exists() || part.length() <= 0) {
                        throw ApiException(KreaApp.instance.str(R.string.err_download_416), network = true)
                    }
                    onProgress(ByteProgress(part.length(), part.length(), 0.0))
                }
                else -> {
                    if (resp.code == 401) throw authError()
                    throw ApiException(httpFallback(resp.code), network = resp.code >= 500)
                }
            }
        }
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
        return contentType
    }

    private fun writeBody(
        input: java.io.InputStream?,
        part: File,
        append: Boolean,
        existing: Long,
        resp: okhttp3.Response,
        onProgress: (ByteProgress) -> Unit,
    ) {
        val stream = input ?: throw ApiException(KreaApp.instance.str(R.string.err_empty_response), network = true)
        val total = contentTotal(resp, existing, append)
        var read = existing
        var lastTick = System.nanoTime()
        var lastBytes = existing
        var speed = 0.0
        stream.use { inn ->
            FileOutputStream(part, append).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = inn.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    read += n
                    val now = System.nanoTime()
                    val dt = now - lastTick
                    if (dt >= 200_000_000L) {
                        val instant = (read - lastBytes).toDouble() * 1_000_000_000.0 / dt.toDouble()
                        speed = if (speed <= 0.0) instant else speed * 0.65 + instant * 0.35
                        lastTick = now
                        lastBytes = read
                    }
                    onProgress(ByteProgress(read, total, speed))
                }
                out.flush()
            }
        }
        onProgress(ByteProgress(read, if (total > 0) total else read, speed))
    }

    private fun contentTotal(resp: okhttp3.Response, existing: Long, append: Boolean): Long {
        val range = resp.header("Content-Range")
        if (append && range != null && '/' in range) {
            val t = range.substringAfter('/').toLongOrNull()
            if (t != null && t > 0) return t
        }
        val len = resp.body?.contentLength() ?: -1L
        return if (len > 0) existing + len else -1L
    }
}

/**
 * enqueue + 可取消挂起，对齐 Http.awaitText：协程取消时立刻 cancel 底层 Call。
 * 标志在 call.cancel() 之前置位，避免 onFailure 的 IOException 抢先 resume、被外层重试当成网络错误再传一次。
 * 响应头到达后的正文读取仍在调用方协程里；这段时间取消同样 cancel Call，打断阻塞 read。
 */
private suspend fun <T> OkHttpClient.callCancellable(request: Request, block: (Response) -> T): T {
    val call = newCall(request)
    val cancelled = AtomicBoolean(false)
    val response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation {
            cancelled.set(true)
            call.cancel()
        }
        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (cancelled.get() || !cont.isActive) {
                    response.close()
                    return
                }
                cont.resume(response)
            }

            override fun onFailure(call: Call, e: IOException) {
                if (cancelled.get() || !cont.isActive) return
                cont.resumeWithException(e)
            }
        })
    }
    val handle = currentCoroutineContext().job.invokeOnCompletion { cause ->
        if (cause != null) {
            cancelled.set(true)
            call.cancel()
        }
    }
    try {
        return response.use { resp ->
            currentCoroutineContext().ensureActive()
            block(resp)
        }
    } catch (e: IOException) {
        if (cancelled.get() || !currentCoroutineContext().job.isActive) {
            throw CancellationException("cancelled", e)
        }
        throw e
    } finally {
        handle.dispose()
    }
}

private class ProgressBody(
    private val file: File,
    private val mime: String,
    private val onProgress: (Long, Long) -> Unit,
) : RequestBody() {
    override fun contentType() = mime.toMediaType()
    override fun contentLength() = file.length()
    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var sent = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                sink.write(buf, 0, n)
                sent += n
                onProgress(sent, total)
            }
        }
    }
}

private fun mimeFor(file: File): String = when (file.extension.lowercase(Locale.US)) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    // 视频（round17）：VHS 支持的扩展名 + avi；gif 仍是 image/gif（LoadImage 也认）
    "mp4", "webm", "mov", "mkv", "avi" -> mimeForVideo(file.extension)
    else -> "application/octet-stream"
}

fun imageUrl(filename: String, subfolder: String, type: String): String {
    fun e(s: String) = URLEncoder.encode(s, "UTF-8")
    return "/view?filename=${e(filename)}&subfolder=${e(subfolder)}&type=${e(type)}"
}
