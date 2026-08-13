package ru.simple.mycalendar.v2.peer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Small privacy-preserving BLE announcement. It contains no task data: only
 * rotating keyed tags that let members of the same family avoid useless
 * connections when their canonical snapshots are already identical.
 */
data class BluetoothBeacon(
    val psm: Int,
    val epoch: Int,
    val familyTag: ByteArray,
    val peerTag: ByteArray,
    val stateTag: ByteArray
) {
    val pairingId: Int
        get() = ByteBuffer.wrap(peerTag).order(ByteOrder.BIG_ENDIAN).int
}

enum class BeaconRelation { FOREIGN_FAMILY, SELF, SAME_STATE, DIFFERENT_STATE }

object BluetoothBeaconCodec {
    const val PAYLOAD_BYTES = 23
    const val EPOCH_MILLIS = 30_000L

    const val VERSION: Byte = 3
    private const val FAMILY_TAG_BYTES = 4
    private const val PEER_TAG_BYTES = 4
    private const val STATE_TAG_BYTES = 8

    fun epoch(nowMillis: Long = System.currentTimeMillis()): Int =
        (Math.floorDiv(nowMillis, EPOCH_MILLIS) and 0x7fff_ffffL).toInt()

    fun create(
        psm: Int,
        epoch: Int,
        deviceId: String,
        snapshotDigest: ByteArray,
        authenticate: (ByteArray) -> ByteArray
    ): BluetoothBeacon = BluetoothBeacon(
        psm = psm,
        epoch = epoch,
        familyTag = tag("family", epoch, ByteArray(0), FAMILY_TAG_BYTES, authenticate),
        peerTag = tag("peer", epoch, deviceId.toByteArray(Charsets.UTF_8), PEER_TAG_BYTES, authenticate),
        stateTag = tag("state", epoch, snapshotDigest, STATE_TAG_BYTES, authenticate)
    )

    fun relation(
        remote: BluetoothBeacon,
        localDeviceId: String,
        localSnapshotDigest: ByteArray,
        authenticate: (ByteArray) -> ByteArray
    ): BeaconRelation {
        val expectedFamily = tag("family", remote.epoch, ByteArray(0), FAMILY_TAG_BYTES, authenticate)
        if (!secureEquals(expectedFamily, remote.familyTag)) return BeaconRelation.FOREIGN_FAMILY

        val expectedPeer = tag(
            "peer",
            remote.epoch,
            localDeviceId.toByteArray(Charsets.UTF_8),
            PEER_TAG_BYTES,
            authenticate
        )
        if (secureEquals(expectedPeer, remote.peerTag)) return BeaconRelation.SELF

        val expectedState = tag("state", remote.epoch, localSnapshotDigest, STATE_TAG_BYTES, authenticate)
        return if (secureEquals(expectedState, remote.stateTag)) {
            BeaconRelation.SAME_STATE
        } else {
            BeaconRelation.DIFFERENT_STATE
        }
    }

    fun encode(value: BluetoothBeacon): ByteArray {
        require(value.psm in 1..0xffff)
        require(value.familyTag.size == FAMILY_TAG_BYTES)
        require(value.peerTag.size == PEER_TAG_BYTES)
        require(value.stateTag.size == STATE_TAG_BYTES)
        return ByteBuffer.allocate(PAYLOAD_BYTES).order(ByteOrder.BIG_ENDIAN)
            .put(VERSION)
            .putShort(value.psm.toShort())
            .putInt(value.epoch)
            .put(value.familyTag)
            .put(value.peerTag)
            .put(value.stateTag)
            .array()
    }

    fun decode(bytes: ByteArray): BluetoothBeacon? {
        if (bytes.size < PAYLOAD_BYTES) return null
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (input.get() != VERSION) return null
        val psm = input.short.toInt() and 0xffff
        if (psm == 0) return null
        return BluetoothBeacon(
            psm = psm,
            epoch = input.int,
            familyTag = ByteArray(FAMILY_TAG_BYTES).also(input::get),
            peerTag = ByteArray(PEER_TAG_BYTES).also(input::get),
            stateTag = ByteArray(STATE_TAG_BYTES).also(input::get)
        )
    }

    private fun tag(
        purpose: String,
        epoch: Int,
        material: ByteArray,
        size: Int,
        authenticate: (ByteArray) -> ByteArray
    ): ByteArray {
        val prefix = "familytasks/bluetooth/beacon-v3/$purpose/".toByteArray(Charsets.UTF_8)
        val message = ByteBuffer.allocate(prefix.size + Int.SIZE_BYTES + material.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(prefix)
            .putInt(epoch)
            .put(material)
            .array()
        val proof = try {
            authenticate(message)
        } finally {
            message.fill(0)
        }
        return try {
            proof.copyOf(size)
        } finally {
            proof.fill(0)
        }
    }

    private fun secureEquals(left: ByteArray, right: ByteArray): Boolean = try {
        MessageDigest.isEqual(left, right)
    } finally {
        left.fill(0)
    }
}

/** Deterministic greedy matching: every phone computes the same disjoint pairs. */
object BluetoothPairingPlanner {
    fun pairs(peerIds: Collection<Int>): List<Pair<Int, Int>> {
        val nodes = peerIds.distinct().sortedWith(Integer::compareUnsigned)
        val edges = buildList {
            nodes.forEachIndexed { leftIndex, left ->
                for (rightIndex in leftIndex + 1 until nodes.size) {
                    val right = nodes[rightIndex]
                    add(Edge(left, right, score(left, right)))
                }
            }
        }.sortedWith { first, second -> compareBytes(first.score, second.score) }

        val used = hashSetOf<Int>()
        return buildList {
            edges.forEach { edge ->
                if (edge.left !in used && edge.right !in used) {
                    used += edge.left
                    used += edge.right
                    add(edge.left to edge.right)
                }
            }
        }
    }

    fun partnerFor(localPeerId: Int, peerIds: Collection<Int>): Int? =
        pairs(peerIds).firstOrNull { it.first == localPeerId || it.second == localPeerId }
            ?.let { if (it.first == localPeerId) it.second else it.first }

    private fun score(left: Int, right: Int): ByteArray {
        val input = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putInt(left)
            .putInt(right)
            .array()
        return MessageDigest.getInstance("SHA-256").digest(input)
    }

    private fun compareBytes(left: ByteArray, right: ByteArray): Int {
        for (index in left.indices) {
            val compared = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
            if (compared != 0) return compared
        }
        return 0
    }

    private data class Edge(val left: Int, val right: Int, val score: ByteArray)
}
