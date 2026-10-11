package com.research.sensormax

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** SensorMax-owned BLE/GATT payload conversion operations recovered from reference behavior. */
enum class BleGattConversion(val defaultWidthBytes: Int?) {
    INT8(1),
    INT16_LITTLE_ENDIAN(2),
    INT16_BIG_ENDIAN(2),
    UINT16_LITTLE_ENDIAN(2),
    UINT24_LITTLE_ENDIAN(3),
    FLOAT32_LITTLE_ENDIAN(4),
    SINGLE_BYTE(1),
    BYTE_ARRAY(null),
    STRING(null),
    HEXADECIMAL(null),
    FORMATTED_STRING(null)
}

enum class BleGattTransferMode {
    NOTIFICATION,
    READ
}

data class BleGattChannelDefinition(
    val id: String,
    val characteristicUuid: String,
    val conversion: BleGattConversion,
    val offset: Int = 0,
    val length: Int? = null,
    val repeating: Int = 1,
    val unit: String = ""
) {
    init {
        require(id.isNotBlank()) { "channel id must not be blank" }
        require(characteristicUuid.isNotBlank()) { "characteristicUuid must not be blank" }
        require(offset >= 0) { "offset must be >= 0" }
        require(length == null || length > 0) { "length must be > 0 when specified" }
        require(repeating > 0) { "repeating must be > 0" }
        conversion.defaultWidthBytes?.let { width ->
            require(length == null || length == width) {
                "length $length does not match $conversion width $width"
            }
        }
    }
}

data class BleGattProfile(
    val id: String,
    val displayName: String,
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    val serviceUuid: String? = null,
    val transferMode: BleGattTransferMode = BleGattTransferMode.NOTIFICATION,
    val mtu: Int? = null,
    val channels: List<BleGattChannelDefinition>
) {
    init {
        require(id.isNotBlank()) { "profile id must not be blank" }
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        deviceAddress?.let {
            require(it.matches(Regex("(?i)([0-9A-F]{2}:){5}[0-9A-F]{2}"))) {
                "deviceAddress must be a Bluetooth MAC address when specified"
            }
        }
        deviceName?.let { require(it.isNotBlank()) { "deviceName must not be blank when specified" } }
        serviceUuid?.let { java.util.UUID.fromString(it) }
        mtu?.let { require(it >= 23) { "BLE MTU must be >= 23 when specified" } }
        require(channels.isNotEmpty()) { "BLE profile must define at least one channel" }
    }

    fun channelsFor(characteristicUuid: String): List<BleGattChannelDefinition> =
        channels.filter { it.characteristicUuid.equals(characteristicUuid, ignoreCase = true) }
}

/** Pure decoder; Bluetooth callbacks can remain Android-specific while decoding stays deterministic/testable. */
object BleGattPayloadDecoder {
    fun decode(payload: ByteArray, channel: BleGattChannelDefinition): FloatArray {
        val width = channel.conversion.defaultWidthBytes
            ?: throw IllegalArgumentException("${channel.conversion} is not a numeric conversion")
        val itemWidth = channel.length ?: width
        require(itemWidth == width) { "Numeric BLE channel width must equal conversion width" }

        val count = channel.repeating
        val values = FloatArray(count)
        for (index in 0 until count) {
            val start = channel.offset + index * itemWidth
            require(start + itemWidth <= payload.size) {
                "BLE payload too short for ${channel.id}: need ${start + itemWidth} bytes, got ${payload.size}"
            }
            values[index] = decodeOne(payload, start, channel.conversion)
        }
        return values
    }

    fun decodeCharacteristic(
        payload: ByteArray,
        characteristicUuid: String,
        profile: BleGattProfile
    ): Map<String, FloatArray> = profile.channelsFor(characteristicUuid).associate { channel ->
        channel.id to decode(payload, channel)
    }

    private fun decodeOne(payload: ByteArray, offset: Int, conversion: BleGattConversion): Float =
        when (conversion) {
            BleGattConversion.INT8 -> payload[offset].toInt().toByte().toFloat()
            BleGattConversion.SINGLE_BYTE -> (payload[offset].toInt() and 0xFF).toFloat()
            BleGattConversion.INT16_LITTLE_ENDIAN ->
                ByteBuffer.wrap(payload, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toFloat()
            BleGattConversion.INT16_BIG_ENDIAN ->
                ByteBuffer.wrap(payload, offset, 2).order(ByteOrder.BIG_ENDIAN).short.toFloat()
            BleGattConversion.UINT16_LITTLE_ENDIAN ->
                (ByteBuffer.wrap(payload, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF).toFloat()
            BleGattConversion.UINT24_LITTLE_ENDIAN ->
                ((payload[offset].toInt() and 0xFF) or
                        ((payload[offset + 1].toInt() and 0xFF) shl 8) or
                        ((payload[offset + 2].toInt() and 0xFF) shl 16)).toFloat()
            BleGattConversion.FLOAT32_LITTLE_ENDIAN ->
                ByteBuffer.wrap(payload, offset, 4).order(ByteOrder.LITTLE_ENDIAN).float
            else -> throw IllegalArgumentException("${conversion.name} is not a numeric conversion")
        }

    fun describe(channel: BleGattChannelDefinition): String = buildString {
        append(channel.id)
        append(" @ ")
        append(channel.characteristicUuid.uppercase(Locale.US))
        append(" offset=")
        append(channel.offset)
        append(" conversion=")
        append(channel.conversion)
        if (channel.repeating != 1) {
            append(" repeating=")
            append(channel.repeating)
        }
    }
}
