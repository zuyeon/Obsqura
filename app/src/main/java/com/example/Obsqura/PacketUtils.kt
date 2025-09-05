package com.example.Obsqura

import kotlin.math.ceil

// 패킷 하나를 표현하는 데이터 클래스
data class Packet(
    val type: Byte,
    val msgId: Byte,
    val index: Byte,
    val total: Byte,
    val payload: ByteArray // length = 16
)

/**
 *  [rawData]를 16바이트씩 잘라, packet head(4byte)+payload(16byte) 형태로 만든다.
 */
fun splitDataIntoPackets(
    rawData: ByteArray,
    type: Byte,
    msgId: Byte
): List<ByteArray> {
    val payloadSize = 16
    val totalPackets = ceil(rawData.size / payloadSize.toDouble()).toInt()

    val packetList = mutableListOf<ByteArray>()

    for (i in 0 until totalPackets) {
        val start = i * payloadSize
        val end = minOf(start + payloadSize, rawData.size)
        val chunkSize = end - start
        val chunk = rawData.sliceArray(start until end)

        // payload 16byte 고정 (남는 부분은 0 padding)
        val payload = ByteArray(payloadSize)
        chunk.copyInto(payload, 0, 0, chunkSize)

        // 4바이트 헤더 + 16바이트 payload = 20바이트
        val packet = ByteArray(20)
        packet[0] = type
        packet[1] = msgId
        packet[2] = i.toByte() // index
        packet[3] = totalPackets.toByte() // total
        payload.copyInto(packet, 4, 0, payloadSize)

        packetList.add(packet)
    }

    return packetList
}

/**
 *  [packets]를 index 순서대로 재조립하여 원본 rawData 복원
 */
fun reassemblePackets(
    packets: List<ByteArray>
): ByteArray {
    if (packets.isEmpty()) return ByteArray(0)

    // index 기준 정렬
    val sorted = packets.sortedBy { it[2].toInt() and 0xFF }
    val total = sorted[0][3].toInt() and 0xFF

    if (sorted.size != total) {
        println("Packet count mismatch. size=${sorted.size}, total=$total")
        return ByteArray(0)
    }

    val output = mutableListOf<Byte>()
    for (p in sorted) {
        val payload = p.sliceArray(4 until 20)
        output.addAll(payload.toList())
    }

    // 0-padding 제거 (optional)
    // while (output.isNotEmpty() && output.last() == 0.toByte()) {
    //     output.removeAt(output.size - 1)
    // }

    return output.toByteArray()
}
