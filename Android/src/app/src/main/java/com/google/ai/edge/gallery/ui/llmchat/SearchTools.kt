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

package com.google.ai.edge.gallery.ui.llmchat

import android.util.Log
import com.google.ai.edge.gallery.data.QUERIT_API_KEY_PREF
import com.google.ai.edge.gallery.data.SearchHttpException
import com.google.ai.edge.gallery.data.SearchService
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

private const val TAG = "AGSearchTools"

/**
 * A [ToolSet] that exposes a `web_search` tool backed by the Querit API.
 *
 * Set [apiKeyProvider] before passing this to the model runtime.
 */
class SearchTools : ToolSet {

  /** Called at invocation time to retrieve the current Querit API key. */
  var apiKeyProvider: (() -> String?) = { null }

  /**
   * Performs a web search using the Querit search API and returns a formatted
   * summary of the top results so the model can synthesise an answer.
   *
   * @param query     The search query to submit to Querit.
   * @param maxResults Maximum number of results to retrieve (1–10, default 5).
   */
  @Tool(
    description =
      "Search the web for up-to-date information using the Querit search API. " +
        "Use this when the user asks about current events, facts, or anything that might " +
        "require fresh information not present in your training data. " +
        "Returns a list of relevant web pages with titles, URLs, and snippets.",
  )
  fun webSearch(
    @ToolParam(description = "The search query to look up on the web.") query: String,
    @ToolParam(
      description =
        "Maximum number of search results to return. Must be between 1 and 10. Default is 5."
    )
    maxResults: Int = 5,
  ): Map<String, Any> {
    return runBlocking(Dispatchers.IO) {
      val apiKey = apiKeyProvider()
      if (apiKey.isNullOrBlank()) {
        Log.w(TAG, "Querit API key is not set.")
        return@runBlocking mapOf(
          "error" to "Querit API key is not configured. Please set it in the app Settings.",
          "status" to "failed",
        )
      }

      val clampedMax = maxResults.coerceIn(1, 10)
      Log.i(TAG, "webSearch called: query='$query', maxResults=$clampedMax")

      try {
        val results = SearchService.search(query = query, apiKey = apiKey, maxResults = clampedMax)

        if (results.items.isEmpty()) {
          return@runBlocking mapOf(
            "query" to query,
            "result_count" to 0,
            "status" to "succeeded",
          )
        }

        // Build structured result objects — flat Maps produce clean key:value pairs in the
        // tool_response block, which the model parses more reliably than multi-line strings.
        val structuredResults =
          results.items.mapIndexed { index, item ->
            buildMap<String, Any> {
              put("index", index + 1)
              put("title", item.title)
              put("url", item.url)
              if (item.snippet.isNotBlank()) put("snippet", item.snippet)
              if (item.siteName != null) put("source", item.siteName)
              if (item.pageAge != null) put("published", item.pageAge)
            }
          }

        Log.d(TAG, "webSearch succeeded: ${results.items.size} results")

        mapOf(
          "query" to query,
          "result_count" to results.items.size,
          "results" to structuredResults,
          "status" to "succeeded",
        )
      } catch (e: SearchHttpException) {
        Log.e(TAG, "webSearch HTTP error ${e.httpCode}", e)
        mapOf(
          "error" to e.errorMsg,
          "http_status" to e.httpCode,
          "status" to "failed",
        )
      } catch (e: Exception) {
        Log.e(TAG, "webSearch failed", e)
        mapOf(
          "error" to (e.message ?: "Unknown error during web search"),
          "status" to "failed",
        )
      }
    }
  }
}
