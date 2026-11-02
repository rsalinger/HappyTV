package com.rysal.happytv.data.entity

/**
 * The three Xtream catalog buckets.
 *
 * Room/DataStore store this as the enum [name] (`LIVE`/`VOD`/`SERIES`) via
 * [com.rysal.happytv.data.Converters]. The wire format is lowercase
 * (`"live"`/`"vod"`/`"series"` and `stream_type`/`type` fields) — DTO mappers in
 * data/network must map case-insensitively, never with a raw `valueOf`.
 */
enum class StreamType {
    LIVE,
    VOD,
    SERIES,
}
