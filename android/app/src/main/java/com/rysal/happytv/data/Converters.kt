package com.rysal.happytv.data

import com.rysal.happytv.data.entity.StreamType
import java.time.Instant

/**
 * Room type converters, registered on [HappyTvDatabase].
 *
 * Both conversions are identity-free and null-preserving, so nullable columns
 * round-trip as null instead of crashing on a cast.
 */
class Converters {

    /** Enum → its `name` (`LIVE`/`VOD`/`SERIES`) so the column is greppable SQL. */
    @androidx.room.TypeConverter
    fun fromStreamType(value: StreamType?): String? = value?.name

    @androidx.room.TypeConverter
    fun toStreamType(value: String?): StreamType? = value?.let(StreamType::valueOf)

    /**
     * [Instant] → epoch millis. The column type stays INTEGER so SQL range
     * queries (`startUtcMillis <= ?`) never go through a converter.
     */
    @androidx.room.TypeConverter
    fun fromInstant(value: Instant?): Long? = value?.toEpochMilli()

    @androidx.room.TypeConverter
    fun toInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)
}
