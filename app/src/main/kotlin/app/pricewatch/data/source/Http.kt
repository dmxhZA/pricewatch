package app.pricewatch.data.source

import app.pricewatch.data.model.DataException
import app.pricewatch.data.model.ErrorKind
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class HttpResponse(val code: Int, val body: String)

/** The only thing the data sources need from the network; tests swap in canned responses. */
fun interface Http {
  /** GET [url] with [query] parameters. Throws [DataException] of kind NETWORK when unreachable. */
  suspend fun get(url: String, query: List<Pair<String, String>>): HttpResponse
}

class OkHttpHttp(private val client: OkHttpClient = defaultClient()) : Http {

  override suspend fun get(url: String, query: List<Pair<String, String>>): HttpResponse {
    val httpUrl =
      url.toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
    val request =
      Request.Builder().url(httpUrl).header("User-Agent", USER_AGENT).header("Accept", "application/json").build()
    val call = client.newCall(request)
    return suspendCancellableCoroutine { continuation ->
      continuation.invokeOnCancellation { call.cancel() }
      call.enqueue(
        object : Callback {
          override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) {
              continuation.resumeWithException(DataException(ErrorKind.NETWORK, e.message, e))
            }
          }

          override fun onResponse(call: Call, response: Response) {
            try {
              val result = response.use { HttpResponse(it.code, it.body.string()) }
              continuation.resume(result)
            } catch (e: IOException) {
              if (continuation.isActive) {
                continuation.resumeWithException(DataException(ErrorKind.NETWORK, e.message, e))
              }
            }
          }
        }
      )
    }
  }

  companion object {
    private const val USER_AGENT = "PriceWatch/1.0 (Android)"

    fun defaultClient(): OkHttpClient =
      OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
  }
}
