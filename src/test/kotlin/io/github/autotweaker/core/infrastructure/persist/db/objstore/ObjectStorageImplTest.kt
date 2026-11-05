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

package io.github.autotweaker.core.infrastructure.persist.db.objstore

import io.github.autotweaker.api.types.Sha256
import io.github.autotweaker.core.infrastructure.persist.db.base.DatabaseStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ObjectStorageImplTest {
	private val dbUrl = "jdbc:h2:mem:object_storage_${counter.getAndIncrement()};DB_CLOSE_DELAY=-1"
	
	companion object {
		private val counter = AtomicInteger(0)
	}
	
	private lateinit var db: Database
	private lateinit var storage: ObjectStorageImpl
	
	@BeforeTest
	fun setUp() {
		db = Database.connect(dbUrl, "org.h2.Driver")
		val databaseStore = mockk<DatabaseStore>()
		every { databaseStore.connect(any()) } returns db
		storage = ObjectStorageImpl(databaseStore)
	}
	
	private fun rawCount(): Int = transaction(db) { ObjectStoreTable.selectAll().count().toInt() }
	
	@Test
	fun `put returns the sha256 of the stored content`() = runBlocking {
		val content = "hello object storage".toByteArray()
		
		assertEquals(Sha256.hash(content), storage.put(content))
	}
	
	@Test
	fun `round trip preserves every binary byte including non utf8 values`() = runBlocking {
		val content = ByteArray(256) { it.toByte() }
		
		val hash = storage.put(content)
		
		assertContentEquals(content, storage.get(hash))
	}
	
	@Test
	fun `put empty content stores an empty object retrievable by its hash`() = runBlocking {
		val hash = storage.put(ByteArray(0))
		
		assertEquals(Sha256.hash(ByteArray(0)), hash)
		assertEquals(0, storage.get(hash)?.size)
	}
	
	@Test
	fun `put stores a single row for identical content`() = runBlocking {
		val content = "duplicated".toByteArray()
		
		val first = storage.put(content)
		val second = storage.put(content)
		
		assertEquals(first, second)
		assertEquals(1, rawCount())
	}
	
	@Test
	fun `different content is stored and retrieved separately`() = runBlocking {
		val first = "first".toByteArray()
		val second = "second".toByteArray()
		
		val firstHash = storage.put(first)
		val secondHash = storage.put(second)
		
		assertEquals(2, rawCount())
		assertContentEquals(first, storage.get(firstHash))
		assertContentEquals(second, storage.get(secondHash))
	}
	
	@Test
	fun `get returns null for content that was never stored`() = runBlocking {
		assertNull(storage.get(Sha256(ByteArray(32))))
	}
}
