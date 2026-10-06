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

import io.github.autotweaker.api.types.llm.Usage
import io.github.autotweaker.api.types.llm.UsageCursor
import io.github.autotweaker.api.types.llm.UsageEntry
import io.github.autotweaker.api.types.llm.toCursor
import io.github.autotweaker.core.infrastructure.persist.db.base.DatabaseStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class UsageRepositoryImplTest {
	private val dbUrl = "jdbc:h2:mem:usage_repo_${counter.getAndIncrement()};DB_CLOSE_DELAY=-1"
	
	companion object {
		private val counter = AtomicInteger(0)
	}
	
	private lateinit var repo: UsageRepositoryImpl
	
	private val modelA = UUID.randomUUID()
	private val modelB = UUID.randomUUID()
	private val baseTime = Instant.parse("2026-09-01T00:00:00Z")
	
	@BeforeTest
	fun setUp() {
		val databaseStore = mockk<DatabaseStore>()
		every { databaseStore.connect(any()) } answers { Database.connect(dbUrl, "org.h2.Driver") }
		repo = UsageRepositoryImpl(databaseStore)
	}
	
	private fun entry(
		id: UUID = UUID.randomUUID(),
		modelId: UUID = modelA,
		timestamp: Instant = baseTime,
		usage: Usage = Usage(promptTokens = 1, completionTokens = 2),
	) = UsageEntry(id = id, modelId = modelId, timestamp = timestamp, usage = usage)
	
	@Test
	fun `load by ids round trips every token field`() = runBlocking {
		val saved = entry(
			usage = Usage(promptTokens = 10, completionTokens = 20, reasoningTokens = 3, cacheHitTokens = 4)
		)
		repo.save(saved)
		
		val loaded = repo.load(setOf(saved.id))
		
		assertEquals(1, loaded.size)
		assertEquals(saved, loaded.single())
	}
	
	@Test
	fun `load preserves absent optional tokens as null`() = runBlocking {
		val saved = entry(usage = Usage(promptTokens = 5, completionTokens = 6))
		repo.save(saved)
		
		val loaded = repo.load(setOf(saved.id)).single()
		
		assertNull(loaded.usage.reasoningTokens)
		assertNull(loaded.usage.cacheHitTokens)
	}
	
	@Test
	fun `load by ids skips ids that were never saved`() = runBlocking {
		repo.save(entry())
		
		assertEquals(0, repo.load(setOf(UUID.randomUUID())).size)
	}
	
	@Test
	fun `save upserts the row for an existing id`() = runBlocking {
		val id = UUID.randomUUID()
		repo.save(entry(id = id, usage = Usage(promptTokens = 1, completionTokens = 1)))
		repo.save(entry(id = id, usage = Usage(promptTokens = 7, completionTokens = 8)))
		
		val loaded = repo.load(setOf(id))
		
		assertEquals(1, loaded.size)
		assertEquals(7, loaded.single().usage.promptTokens)
		assertEquals(8, loaded.single().usage.completionTokens)
	}
	
	@Test
	fun `load limit returns newest entries first`() = runBlocking {
		val oldest = entry(timestamp = baseTime)
		val middle = entry(timestamp = baseTime.plus(1.hours))
		val newest = entry(timestamp = baseTime.plus(2.hours))
		repo.save(oldest)
		repo.save(middle)
		repo.save(newest)
		
		val page = repo.load(2, null)
		
		assertEquals(listOf(newest.id, middle.id), page.map { it.id })
	}
	
	@Test
	fun `load with zero limit returns an empty page`() = runBlocking {
		repo.save(entry())
		
		assertEquals(0, repo.load(0, null).size)
	}
	
	@Test
	fun `load pages through the cursor without duplicates or gaps`() = runBlocking {
		val entries = (0 until 7).map { entry(timestamp = baseTime.plus(it.hours)) }
		entries.forEach { repo.save(it) }
		val expected = entries.sortedByDescending { it.timestamp }.map { it.id }
		
		val collected = mutableListOf<UUID>()
		var cursor: UsageCursor? = null
		while (collected.size < entries.size) {
			val page = repo.load(2, cursor)
			check(page.isNotEmpty()) { "游标分页在取完 $expected 之前就返回了空页" }
			collected += page.map { it.id }
			cursor = page.last().toCursor()
		}
		
		assertEquals(expected, collected)
	}
	
	@Test
	fun `load pagination covers entries sharing a timestamp exactly once`() = runBlocking {
		val shared = (0 until 3).map { entry(timestamp = baseTime) }
		shared.forEach { repo.save(it) }
		
		val first = repo.load(2, null)
		val second = repo.load(2, first.last().toCursor())
		
		assertEquals(2, first.size)
		assertEquals(1, second.size)
		assertEquals(shared.map { it.id }.toSet(), (first + second).map { it.id }.toSet())
	}
	
	@Test
	fun `summarize by ids sums every token field`() = runBlocking {
		val a = entry(usage = Usage(promptTokens = 10, completionTokens = 1, reasoningTokens = 5, cacheHitTokens = 2))
		val b = entry(usage = Usage(promptTokens = 20, completionTokens = 2, reasoningTokens = 7, cacheHitTokens = 3))
		repo.save(a)
		repo.save(b)
		
		val summary = repo.summarize(setOf(a.id, b.id))
		
		assertEquals(30, summary?.promptTokens)
		assertEquals(3, summary?.completionTokens)
		assertEquals(12, summary?.reasoningTokens)
		assertEquals(5, summary?.cacheHitTokens)
	}
	
	@Test
	fun `summarize by ids ignores entries outside the id set`() = runBlocking {
		val included = entry(usage = Usage(promptTokens = 10, completionTokens = 0))
		val excluded = entry(usage = Usage(promptTokens = 99, completionTokens = 0))
		repo.save(included)
		repo.save(excluded)
		
		assertEquals(10, repo.summarize(setOf(included.id))?.promptTokens)
	}
	
	@Test
	fun `summarize by ids returns null when nothing matches`() = runBlocking {
		repo.save(entry())
		
		assertNull(repo.summarize(emptySet()))
	}
	
	@Test
	fun `summarize filters by model id`() = runBlocking {
		repo.save(entry(modelId = modelA, usage = Usage(promptTokens = 10, completionTokens = 0)))
		repo.save(entry(modelId = modelB, usage = Usage(promptTokens = 90, completionTokens = 0)))
		
		assertEquals(10, repo.summarize(modelA, null, null)?.promptTokens)
		assertEquals(90, repo.summarize(modelB, null, null)?.promptTokens)
		assertEquals(100, repo.summarize(null, null, null)?.promptTokens)
	}
	
	@Test
	fun `summarize applies an inclusive time range`() = runBlocking {
		repo.save(entry(timestamp = baseTime, usage = Usage(promptTokens = 1, completionTokens = 0)))
		repo.save(entry(timestamp = baseTime.plus(1.hours), usage = Usage(promptTokens = 10, completionTokens = 0)))
		repo.save(entry(timestamp = baseTime.plus(2.hours), usage = Usage(promptTokens = 100, completionTokens = 0)))
		
		val summary = repo.summarize(null, baseTime.plus(1.hours), baseTime.plus(2.hours))
		
		assertEquals(110, summary?.promptTokens)
	}
	
	@Test
	fun `summarize returns null when the time range matches nothing`() = runBlocking {
		repo.save(entry(timestamp = baseTime))
		
		assertNull(repo.summarize(null, baseTime.plus(10.hours), null))
	}
	
	@Test
	fun `summarize keeps optional tokens null when never recorded`() = runBlocking {
		val saved = entry(usage = Usage(promptTokens = 3, completionTokens = 4))
		repo.save(saved)
		
		val summary = repo.summarize(setOf(saved.id))
		
		assertNull(summary?.reasoningTokens)
		assertNull(summary?.cacheHitTokens)
	}
	
	@Test
	fun `summarize sums optional tokens across entries that recorded them`() = runBlocking {
		val withReasoning = entry(usage = Usage(promptTokens = 1, completionTokens = 1, reasoningTokens = 5))
		val withoutReasoning = entry(usage = Usage(promptTokens = 1, completionTokens = 1))
		repo.save(withReasoning)
		repo.save(withoutReasoning)
		
		assertEquals(5, repo.summarize(null, null, null)?.reasoningTokens)
	}
}
