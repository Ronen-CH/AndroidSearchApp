/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.mlkit.md.productsearch

import android.content.Context
import android.util.Base64
import android.util.Log
import com.android.volley.Request
import com.android.volley.RequestQueue
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.md.objectdetection.DetectedObjectInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

const val VISION_API_URL = "https://vision.googleapis.com/v1"
const val VISION_API_KEY = "AIzaSyDT5ucPCekx4oKzGgYnW4wg9yhngzBn7Fg"
const val VISION_API_PROJECT_ID = "visual-search-331409"
const val VISION_API_LOCATION_ID = "us-east1"
const val VISION_API_PRODUCT_SET_ID =  "return_set"//"first-set" //"model-set" //
const val MAX_RESULTS = 5

/** A fake search engine to help simulate the complete work flow.  */
class SearchEngine(context: Context) {

    private val searchRequestQueue: RequestQueue = Volley.newRequestQueue(context)
    private val requestCreationExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val fetchImageRequestQueue: RequestQueue = Volley.newRequestQueue(context)

    fun search(
        detectedObject: DetectedObjectInfo,
        listener: (detectedObject: DetectedObjectInfo, productList: List<Product>) -> Unit
    ) {
        // Crops the object image out of the full image is expensive, so do it off the UI thread.
        Tasks.call<JsonObjectRequest>(requestCreationExecutor, Callable { createRequest(fetchImageRequestQueue, detectedObject, listener) })
            .addOnSuccessListener { productRequest -> searchRequestQueue.add(productRequest.setTag(TAG)) }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to create product search request!", e)
            }
    }

    fun shutdown() {
        searchRequestQueue.cancelAll(TAG)
        requestCreationExecutor.shutdown()
    }

    companion object {
        private const val TAG = "SearchEngine"

        @Throws(Exception::class)
        private fun createRequest(
            requestQueue: RequestQueue,
            searchingObject: DetectedObjectInfo,
            listener: (detectedObject: DetectedObjectInfo, productList: List<Product>) -> Unit
        ): JsonObjectRequest {
            val objectImageData = searchingObject.imageData
                ?: throw Exception("Failed to get object image data!")

            val base64: String = convertBitmapToBase64(objectImageData)
            val body = getBody(base64)


            return JsonObjectRequest(Request.Method.POST, "$VISION_API_URL/images:annotate?key=$VISION_API_KEY", body,
                { response ->
                  val productList = parseResponse(response)
                    val fetchReferenceImageTasks = productList.map { fetchReferenceImage(it, requestQueue) }
                    Tasks.whenAllComplete(fetchReferenceImageTasks)
                        .addOnSuccessListener { listener.invoke(searchingObject, productList) }
                        .addOnFailureListener { listener.invoke(searchingObject, ArrayList<Product>()) }
                },
                { error ->
                    Log.d("SUCCESS", error.toString())
                }
            )
        }

        private fun parseResponse(response: JSONObject): List<Product> {
            val productList = ArrayList<Product>()
            val responses = response.getJSONArray("responses")
            for (i in 0 until responses.length()){
                val results = responses.getJSONObject(i).getJSONObject("productSearchResults").getJSONArray("results")
                for (j in 0 until results.length()){
                    val result = results.getJSONObject(j)
                    val product = result.getJSONObject("product")
                    productList.add(
                        Product(result.getString("image"), "Product title: ${product.getString("displayName")}", "Score: ${result.getDouble("score")}")
                    )
                }
            }

            return productList
        }

        private fun fetchReferenceImage(searchResult: Product, requestQueue: RequestQueue): Task<Product> {
            // Initialization to use the Task API
            val apiSource = TaskCompletionSource<Product>()
            val apiTask = apiSource.task

            // Craft the API request to get details about the reference image of the product
            val stringRequest = object : StringRequest(
                Method.GET,
                "$VISION_API_URL/${searchResult.imageId}?key=$VISION_API_KEY",
                { response ->
                    val responseJson = JSONObject(response)
                    val gcsUri = responseJson.getString("uri")

                    // Convert the GCS URL to its HTTPS representation


                    val httpUri = gcsUri.replace("gs://", "https://storage.googleapis.com/")
                    //val httpUri = gcsUri.replace("gs://", "https://storage.cloud.google.com/")
                    // Save the HTTPS URL to the search result object
                    searchResult.imageUrl = httpUri

                    // Invoke the listener to continue with processing the API response (eg. show on UI)
                    apiSource.setResult(searchResult)
                },
                { error -> apiSource.setException(error) }
            ) {

                override fun getBodyContentType(): String {
                    return "application/json; charset=utf-8"
                }
            }

            requestQueue.add(stringRequest)

            return apiTask
        }

        private fun convertBitmapToBase64(data: ByteArray): String {
            return Base64.encodeToString(data, Base64.DEFAULT)
        }

        private fun getBody(base64: String): JSONObject {
            val body = JSONObject()
            val requests = JSONArray()

            val request = JSONObject();

            val image = JSONObject()
            image.put("content", base64)
            request.put("image", image)

            val features = JSONArray()
            val feature = JSONObject()
            feature.put("type", "PRODUCT_SEARCH")
            feature.put("maxResults", MAX_RESULTS)
            features.put(feature)
            request.put("features", features)

            val context = JSONObject()
            val params = JSONObject()
            params.put(
                "productSet",
                "projects/${VISION_API_PROJECT_ID}/locations/${VISION_API_LOCATION_ID}/productSets/${VISION_API_PRODUCT_SET_ID}"
            )
            val categories = JSONArray()
            categories.put("apparel-v2")
            params.put("productCategories", categories)
            context.put("productSearchParams", params)
            request.put("imageContext", context)

            requests.put(request)
            body.put("requests", requests)

            return body
        }
    }
}
