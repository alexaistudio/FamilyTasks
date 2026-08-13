package ru.simple.mycalendar.v2.peer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest

class BluetoothBeaconTest {
    private val familyA: (ByteArray) -> ByteArray = { input -> digest(byteArrayOf(1) + input) }
    private val familyB: (ByteArray) -> ByteArray = { input -> digest(byteArrayOf(2) + input) }

    @Test
    fun beaconRoundTripsWithoutTaskData() {
        val state = digest("task title is never advertised".toByteArray())
        val original = BluetoothBeaconCodec.create(129, 42, "phone-a", state, familyA)
        val encoded = BluetoothBeaconCodec.encode(original)
        val restored = requireNotNull(BluetoothBeaconCodec.decode(encoded))

        assertEquals(BluetoothBeaconCodec.PAYLOAD_BYTES, encoded.size)
        assertEquals(original.psm, restored.psm)
        assertEquals(original.epoch, restored.epoch)
        assertArrayEquals(original.familyTag, restored.familyTag)
        assertArrayEquals(original.peerTag, restored.peerTag)
        assertArrayEquals(original.stateTag, restored.stateTag)
        assertTrue(!encoded.toString(Charsets.UTF_8).contains("task title"))
    }

    @Test
    fun relationRejectsOtherFamilyAndSkipsEqualState() {
        val state = digest("same-state".toByteArray())
        val remote = BluetoothBeaconCodec.create(7, 999, "phone-b", state, familyA)

        assertEquals(
            BeaconRelation.SAME_STATE,
            BluetoothBeaconCodec.relation(remote, "phone-a", state, familyA)
        )
        assertEquals(
            BeaconRelation.DIFFERENT_STATE,
            BluetoothBeaconCodec.relation(remote, "phone-a", digest("other".toByteArray()), familyA)
        )
        assertEquals(
            BeaconRelation.SELF,
            BluetoothBeaconCodec.relation(remote, "phone-b", state, familyA)
        )
        assertEquals(
            BeaconRelation.FOREIGN_FAMILY,
            BluetoothBeaconCodec.relation(remote, "phone-a", state, familyB)
        )
    }

    @Test
    fun plannerCreatesOnlyDisjointPairsForSevenEightAndNinePhones() {
        for (count in 7..9) {
            val ids = (1..count).map { rotatingId(it, 1) }
            val pairs = BluetoothPairingPlanner.pairs(ids)
            val endpoints = pairs.flatMap { listOf(it.first, it.second) }
            assertEquals(count / 2, pairs.size)
            assertEquals(endpoints.size, endpoints.distinct().size)
            assertEquals(pairs, BluetoothPairingPlanner.pairs(ids.reversed()))
        }
    }

    @Test
    fun sevenPhonesConvergeAndNewPhonesEightAndNineJoinLater() {
        val states = (1..7).associateWith { mutableSetOf(it) }.toMutableMap()

        repeat(40) { round ->
            if (round == 3) {
                states[8] = mutableSetOf(8)
                states[9] = mutableSetOf(9)
            }
            val idToPhone = states.keys.associateBy { rotatingId(it, round + 10) }
            BluetoothPairingPlanner.pairs(idToPhone.keys).forEach { (leftId, rightId) ->
                val left = requireNotNull(idToPhone[leftId])
                val right = requireNotNull(idToPhone[rightId])
                val union = (states.getValue(left) + states.getValue(right)).toMutableSet()
                states[left] = union.toMutableSet()
                states[right] = union.toMutableSet()
            }
        }

        assertEquals(9, states.size)
        states.values.forEach { assertEquals((1..9).toSet(), it) }
    }

    private fun rotatingId(phone: Int, round: Int): Int = ByteBuffer.wrap(
        digest("$phone/$round".toByteArray())
    ).int

    private fun digest(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)
}
