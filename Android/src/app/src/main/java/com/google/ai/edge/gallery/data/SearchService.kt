/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.data

import android.util.Log
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "AGSearchService"
private const val QUERIT_ENDPOINT = "https://api.querit.ai/v1/search"
private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 30_000

/** The Querit API key preference key used in DataStore. */
const val QUERIT_API_KEY_PREF = "querit_api_key"

// ─── Request / Response data classes ─────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class QueritRequest(
  @Json(name = "query") val query: String,
  @Json(name = "count") val count: Int = 5,
)

@JsonClass(generateAdapter = true)
data class QueritResponseItem(
  @Json(name = "title") val title: String = "",
  @Json(name = "url") val url: String = "",
  @Json(name = "description") val description: String? = null,
  @Json(name = "snippet") val snippet: String? = null,
  @Json(name = "site_name") val siteName: String? = null,
  @Json(name = "page_age") val pageAge: String? = null,
)

@JsonClass(generateAdapter = true)
data class QueritResultWrapper(
  @Json(name = "result") val result: List<QueritResponseItem> = emptyList(),
  @Json(name = "took") val took: Long? = null,
)

@JsonClass(generateAdapter = true)
data class QueritResponse(
  @Json(name = "results") val results: QueritResultWrapper = QueritResultWrapper(),
)

// ─── Search result returned to callers ───────────────────────────────────────

data class SearchResult(
  val title: String,
  val url: String,
  val snippet: String,
  val siteName: String?,
  val pageAge: String?,
)

data class SearchResults(
  val query: String,
  val items: List<SearchResult>,
  val serverLatencyMs: Long?,
)

/** Thrown when the Querit API returns a non-200 HTTP status code. */
class SearchHttpException(val httpCode: Int, val errorBody: String) :
  Exception("HTTP $httpCode: $errorBody") {
  /** The human-readable message extracted from the error body, falls back to the raw body. */
  val errorMsg: String = runCatching {
    val obj = org.json.JSONObject(errorBody)
    obj.getString("error_msg")
  }.getOrDefault(errorBody)
}



object SearchService {
  private val moshi: Moshi = Moshi.Builder().build()

  /**
   * Calls the Querit search API and returns structured results.
   *
   * @throws IllegalArgumentException if apiKey is blank
   * @throws Exception on network / parse errors
   */
  @Throws(Exception::class)
  fun search(query: String, apiKey: String, maxResults: Int = 5): SearchResults {
    require(apiKey.isNotBlank()) { "Querit API key is not set." }

    val requestAdapter = moshi.adapter(QueritRequest::class.java)
    val responseAdapter = moshi.adapter(QueritResponse::class.java)

    val requestBody = requestAdapter.toJson(QueritRequest(query = query, count = maxResults))
    Log.i(TAG, "Querit request: $requestBody")

    val connection = URL(QUERIT_ENDPOINT).openConnection() as HttpURLConnection
    try {
      connection.requestMethod = "POST"
      connection.connectTimeout = CONNECT_TIMEOUT_MS
      connection.readTimeout = READ_TIMEOUT_MS
      connection.setRequestProperty("Authorization", "Bearer $apiKey")
      connection.setRequestProperty("Content-Type", "application/json")
      connection.doOutput = true

      OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(requestBody) }

      val code = connection.responseCode
      if (code != 200) {
        val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "no body"
        Log.e(TAG, "Querit HTTP $code: $errorBody")
        throw SearchHttpException(httpCode = code, errorBody = errorBody)
      }

      val responseBody = connection.inputStream.bufferedReader().readText()
      Log.i(TAG, "Querit response (${responseBody.length} bytes): ${responseBody.take(500)}")

      val response = responseAdapter.fromJson(responseBody)
        ?: throw Exception("Failed to parse Querit response")

      val items = response.results.result.take(maxResults).mapIndexed { _, item ->
        SearchResult(
          title = item.title,
          url = item.url,
          snippet = item.description ?: item.snippet ?: "",
          siteName = item.siteName,
          pageAge = item.pageAge,
        )
      }

      return SearchResults(
        query = query,
        items = items,
        serverLatencyMs = response.results.took,
      )
    } finally {
      connection.disconnect()
    }
  }
}
