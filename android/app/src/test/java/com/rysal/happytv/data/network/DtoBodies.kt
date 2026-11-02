package com.rysal.happytv.data.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody

/** Test helper: a list endpoint's response body (#130 moved the three stream lists to raw bodies). */
inline fun <reified T> dtoBody(rows: List<T>): ResponseBody =
    Json.encodeToString(rows).toResponseBody()
