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

package io.github.autotweaker.core.infrastructure.persist.db.usage

import io.github.autotweaker.api.types.debug.UsageEntry
import io.github.autotweaker.core.infrastructure.persist.db.base.DatabaseStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class UsageDbApiTest {
	private val dbUrl = "jdbc:h2:mem:usage_api_${counter.getAndIncrement()};DB_CLOSE_DELAY=-1"
	
	companion object {
		private val counter = AtomicInteger(0)
	}
	
	private lateinit var api: UsageDbApi
	
	private val modelId = UUID.randomUUID()
	private val timestamp = Instant.parse("2026-09-01T00:00:00Z")
	
	@BeforeTest
	fun setUp() {
		val databaseStore = mockk<DatabaseStore>()
		val database = Database.connect(dbUrl, "org.h2.Driver")
		every { databaseStore.connect(any()) } answers { database }
		// UsageDbApi 复用已建好的表，测试里需要自己建表
		transaction(database) { SchemaUtils.create(UsageTable) }
		api = UsageDbApi(databaseStore)
	}
	
	private fun entry(
		key: UUID = UUID.randomUUID(),
		promptTokens: Int = 1,
		completionTokens: Int = 2,
		reasoningTokens: Int? = null,
		cacheHitTokens: Int? = null,
	) = UsageEntry(
		key = key,
		modelId = modelId,
		timestamp = timestamp,
		promptTokens = promptTokens,
		completionTokens = completionTokens,
		reasoningTokens = reasoningTokens,
		cacheHitTokens = cacheHitTokens,
	)
	
	@Test
	fun `put then get round trips every column`() = runBlocking {
		val saved = entry(promptTokens = 10, completionTokens = 20, reasoningTokens = 3, cacheHitTokens = 4)
		api.put(saved)
		
		assertEquals(saved, api.get(saved.key))
	}
	
	@Test
	fun `get returns null for an unknown key`() = runBlocking {
		assertNull(api.get(UUID.randomUUID()))
	}
	
	@Test
	fun `put upserts the row for an existing key`() = runBlocking {
		val key = UUID.randomUUID()
		api.put(entry(key = key, promptTokens = 1))
		api.put(entry(key = key, promptTokens = 9))
		
		assertEquals(9, api.get(key)?.promptTokens)
	}
	
	@Test
	fun `list returns the entries in the requested range`() = runBlocking {
		val saved = entry()
		api.put(saved)
		
		assertEquals(listOf(saved), api.list(0u..10u))
	}
	
	@Test
	fun `list returns nothing when the range starts past the last entry`() = runBlocking {
		api.put(entry())
		
		assertEquals(0, api.list(5u..9u).size)
	}
	
	@Test
	fun `delete removes the entry`() = runBlocking {
		val saved = entry()
		api.put(saved)
		
		api.delete(saved.key)
		
		assertNull(api.get(saved.key))
	}
}
