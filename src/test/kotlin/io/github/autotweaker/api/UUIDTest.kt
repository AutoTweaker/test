/*
 * AutoTweaker
 * Copyright (C) 2026  WhiteElephant-abc
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.autotweaker.api

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UUIDTest {
	
	// region UUID.toByteArray
	
	@Test
	fun `toByteArray produces exactly 16 bytes`() {
		assertEquals(16, UUID.randomUUID().toByteArray().size)
	}
	
	@Test
	fun `toByteArray writes most significant bits before least significant bits`() {
		val uuid = UUID(0x0102030405060708L, 0x090A0B0C0D0E0F10L)
		assertEquals(
			(1..16).map { it.toByte() },
			uuid.toByteArray().toList()
		)
	}
	
	@Test
	fun `toByteArray keeps high bit bytes unsigned`() {
		val uuid = UUID(-1L, -1L)
		assertEquals(List(16) { 0xFF.toByte() }, uuid.toByteArray().toList())
	}
	
	@Test
	fun `toByteArray encodes the nil uuid as sixteen zero bytes`() {
		assertEquals(List(16) { 0 }, UUID(0L, 0L).toByteArray().map { it.toInt() })
	}
	
	// endregion
	
	// region UUID(bytes)
	
	@Test
	fun `construct from bytes uses big endian layout`() {
		val bytes = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 2)
		assertEquals(UUID.fromString("00000000-0000-0001-0000-000000000002"), UUID(bytes))
	}
	
	@Test
	fun `construct from all ones bytes yields negative bits`() {
		val uuid = UUID(ByteArray(16) { 0xFF.toByte() })
		assertEquals(-1L, uuid.mostSignificantBits)
		assertEquals(-1L, uuid.leastSignificantBits)
	}
	
	@Test
	fun `construct from all zero bytes yields nil uuid`() {
		assertEquals(UUID(0L, 0L), UUID(ByteArray(16)))
	}
	
	@Test
	fun `reject empty byte array`() {
		val ex = assertFailsWith<IllegalArgumentException> { UUID(ByteArray(0)) }
		assertEquals("UUID requires exactly 16 bytes", ex.message)
	}
	
	@Test
	fun `reject byte array shorter than 16 bytes`() {
		assertFailsWith<IllegalArgumentException> { UUID(ByteArray(15)) }
	}
	
	@Test
	fun `reject byte array longer than 16 bytes`() {
		assertFailsWith<IllegalArgumentException> { UUID(ByteArray(17)) }
	}
	
	// endregion
	
	// region round trip
	
	@Test
	fun `random uuid survives byte round trip`() {
		val uuid = UUID.randomUUID()
		assertEquals(uuid, UUID(uuid.toByteArray()))
	}
	
	@Test
	fun `extreme uuids survive byte round trip`() {
		listOf(
			UUID(0L, 0L),
			UUID(-1L, -1L),
			UUID(Long.MIN_VALUE, Long.MAX_VALUE),
			UUID.fromString("00000000-0000-0001-0000-000000000002")
		).forEach { assertEquals(it, UUID(it.toByteArray())) }
	}
	
	@Test
	fun `bytes survive uuid round trip`() {
		val bytes = ByteArray(16) { (it * 17 - 128).toByte() }
		assertEquals(bytes.toList(), UUID(bytes).toByteArray().toList())
	}
	
	// endregion
}
