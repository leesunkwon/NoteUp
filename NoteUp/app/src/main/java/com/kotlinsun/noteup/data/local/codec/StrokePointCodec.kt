package com.kotlinsun.noteup.data.local.codec

import com.kotlinsun.noteup.domain.model.StrokePoint
import java.nio.ByteBuffer
import java.nio.ByteOrder

object StrokePointCodec {
    private const val FORMAT_VERSION = 1
    private const val HEADER_SIZE = Int.SIZE_BYTES * 2
    private const val POINT_SIZE = Float.SIZE_BYTES * 3 + Int.SIZE_BYTES

    fun encode(points: List<StrokePoint>): ByteArray {
        require(points.size <= (Int.MAX_VALUE - HEADER_SIZE) / POINT_SIZE) {
            "Too many stroke points"
        }
        val buffer = ByteBuffer
            .allocate(HEADER_SIZE + points.size * POINT_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(FORMAT_VERSION)
        buffer.putInt(points.size)
        points.forEach { point ->
            buffer.putFloat(point.x)
            buffer.putFloat(point.y)
            buffer.putFloat(point.pressure)
            buffer.putInt(point.timeOffsetMillis)
        }
        return buffer.array()
    }

    fun decode(bytes: ByteArray): List<StrokePoint> {
        require(bytes.size >= HEADER_SIZE) { "Invalid stroke header" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.int == FORMAT_VERSION) { "Unsupported stroke format" }
        val pointCount = buffer.int
        require(pointCount >= 0) { "Invalid stroke point count" }
        require(pointCount <= (bytes.size - HEADER_SIZE) / POINT_SIZE) {
            "Invalid stroke point count"
        }
        require(bytes.size == HEADER_SIZE + pointCount * POINT_SIZE) {
            "Invalid stroke payload size"
        }
        return List(pointCount) {
            StrokePoint(
                x = buffer.float,
                y = buffer.float,
                pressure = buffer.float,
                timeOffsetMillis = buffer.int,
            )
        }
    }
}
