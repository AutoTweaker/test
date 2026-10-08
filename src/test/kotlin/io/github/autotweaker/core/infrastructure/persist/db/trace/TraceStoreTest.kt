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

package io.github.autotweaker.core.infrastructure.persist.db.trace

import io.github.autotweaker.api.now
import io.github.autotweaker.core.infrastructure.persist.db.base.DatabaseStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class TraceStoreTest {
	private val dbUrl = "jdbc:h2:mem:trace_store_${counter.getAndIncrement()};DB_CLOSE_DELAY=-1"
	
	private lateinit var db: Database
	private lateinit var store: TraceStore
	
	companion object {
		private val counter = AtomicInteger(0)
	}
	
	@BeforeTest
	fun setUp() {
		db = Database.connect(dbUrl, "org.h2.Driver")
		transaction(db) { SchemaUtils.create(TraceTable) }
		val databaseStore = mockk<DatabaseStore>()
		every { databaseStore.connect(any()) } returns db
		store = TraceStore(databaseStore)
	}
	
	private fun instant(epochSecond: Long) = Instant.fromEpochSeconds(epochSecond)
	
	private fun insertRaw(origin: String, namespace: String, timestamp: Instant, content: String) {
		transaction(db) {
			TraceTable.insert {
				it[TraceTable.origin] = origin
				it[TraceTable.namespace] = namespace
				it[TraceTable.timestamp] = timestamp
				it[TraceTable.content] = content
			}
		}
	}
	
	private fun rawCount(): Int = transaction(db) { TraceTable.selectAll().count().toInt() }
	
	private fun rawTimestamps(): List<Instant> = transaction(db) {
		TraceTable.selectAll().map { it[TraceTable.timestamp] }.sorted()
	}
	
	@Test
	fun `insert stores a row that select reads back by its timestamp`() = runBlocking {
		store.insert("origin", "namespace", "payload")
		
		val timestamp = transaction(db) { TraceTable.selectAll().single()[TraceTable.timestamp] }
		assertEquals("payload", store.select("origin", "namespace", timestamp))
	}
	
	@Test
	fun `select returns null when no row matches`() = runBlocking {
		insertRaw("origin", "namespace", instant(10), "payload")
		
		assertNull(store.select("origin", "namespace", instant(11)))
	}
	
	@Test
	fun `selectOrigins returns each origin once`() = runBlocking {
		insertRaw("alpha", "one", instant(1), "a")
		insertRaw("alpha", "two", instant(2), "b")
		insertRaw("beta", "one", instant(3), "c")
		
		assertEquals(listOf("alpha", "beta"), store.selectOrigins().sorted())
	}
	
	@Test
	fun `selectNamespaces returns only the namespaces of the requested origin`() = runBlocking {
		insertRaw("alpha", "one", instant(1), "a")
		insertRaw("alpha", "two", instant(2), "b")
		insertRaw("beta", "three", instant(3), "c")
		
		assertEquals(setOf("one", "two"), store.selectNamespaces("alpha").toSet())
	}
	
	@Test
	fun `count only counts the requested origin and namespace`() = runBlocking {
		insertRaw("alpha", "one", instant(1), "a")
		insertRaw("alpha", "one", instant(2), "b")
		insertRaw("alpha", "two", instant(3), "c")
		insertRaw("beta", "one", instant(4), "d")
		
		assertEquals(2L, store.count("alpha", "one"))
	}
	
	@Test
	fun `selectEntries returns the requested slice in ascending order`() = runBlocking {
		(0L..4L).forEach { insertRaw("alpha", "one", instant(it), "c$it") }
		insertRaw("alpha", "two", instant(0), "other")
		
		assertEquals(
			listOf(instant(1), instant(2), instant(3)),
			store.selectEntries("alpha", "one", 1u..3u)
		)
		assertEquals(listOf(instant(0)), store.selectEntries("alpha", "one", 0u..0u))
	}
	
	@Test
	fun `selectEntries returns nothing when the range starts past the last entry`() = runBlocking {
		(0L..2L).forEach { insertRaw("alpha", "one", instant(it), "c$it") }
		
		assertEquals(emptyList(), store.selectEntries("alpha", "one", 5u..9u))
	}
	
	@Test
	fun `delete removes the matching row and reports true`() = runBlocking {
		insertRaw("alpha", "one", instant(1), "a")
		
		assertTrue(store.delete("alpha", "one", instant(1)))
		assertEquals(0, rawCount())
	}
	
	@Test
	fun `delete reports false when nothing matches`() = runBlocking {
		assertFalse(store.delete("alpha", "one", instant(1)))
	}
	
	@Test
	fun `deleteByAge removes entries older than the cutoff`() = runBlocking {
		val base = instant(now().epochSeconds)
		insertRaw("alpha", "one", base - 10.days, "old")
		insertRaw("alpha", "one", base - 1.days, "recent")
		
		assertEquals(1L, store.deleteByAge(5.days))
		assertEquals(listOf(base - 1.days), store.selectEntries("alpha", "one", 0u..10u))
	}
	
	@Test
	fun `trimPerNamespace keeps only the newest entries of a namespace`() = runBlocking {
		(0L..4L).forEach { insertRaw("alpha", "one", instant(it), "c$it") }
		(0L..1L).forEach { insertRaw("alpha", "two", instant(it), "c$it") }
		
		assertEquals(2L, store.trimPerNamespace(3))
		assertEquals(
			listOf(instant(2), instant(3), instant(4)),
			store.selectEntries("alpha", "one", 0u..10u)
		)
		assertEquals(listOf(instant(0), instant(1)), store.selectEntries("alpha", "two", 0u..10u))
	}
	
	@Test
	fun `trimPerNamespace leaves a namespace at the limit untouched`() = runBlocking {
		(0L..2L).forEach { insertRaw("alpha", "one", instant(it), "c$it") }
		
		assertEquals(0L, store.trimPerNamespace(3))
		assertEquals(3, rawCount())
	}
	
	@Test
	fun `trimGlobal deletes only the oldest overflow entries`() = runBlocking {
		(0L..4L).forEach { insertRaw("alpha", "one", instant(it), "c$it") }
		
		assertEquals(3L, store.trimGlobal(2))
		assertEquals(listOf(instant(3), instant(4)), rawTimestamps())
	}
	
	@Test
	fun `trimGlobal deletes nothing when the total is within the limit`() = runBlocking {
		(0L..1L).forEach { insertRaw("alpha", "one", instant(it), "c$it") }
		
		assertEquals(0L, store.trimGlobal(5))
		assertEquals(2, rawCount())
	}
	
	@Test
	fun `deleteOldestBatch deletes the oldest batch`() = runBlocking {
		(0L..4L).forEach { insertRaw("alpha", "one", instant(it), "c$it") }
		
		assertEquals(2L, store.deleteOldestBatch(2))
		assertEquals(listOf(instant(2), instant(3), instant(4)), rawTimestamps())
	}
	
	@Test
	fun `deleteOldestBatch deletes nothing when the batch exceeds the entry count`() = runBlocking {
		(0L..1L).forEach { insertRaw("alpha", "one", instant(it), "c$it") }
		
		assertEquals(0L, store.deleteOldestBatch(5))
		assertEquals(2, rawCount())
	}
}
