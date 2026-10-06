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

package io.github.autotweaker.core.infrastructure.config

import io.github.autotweaker.api.types.exception.DefaultModelDeletionException
import io.github.autotweaker.api.types.exception.duplicate.DuplicateModelNameException
import io.github.autotweaker.api.types.exception.notfound.ProviderNotFoundException
import io.github.autotweaker.core.infrastructure.persist.json.ModelResolverImpl
import io.github.autotweaker.core.test.TestServices
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import java.util.*
import kotlin.reflect.KClass
import kotlin.test.*

class ModelConfigRepositoryTest {
	companion object {
		init {
			TestServices.init()
		}
	}
	
	private val secret = InMemorySecretStore()
	private val entries = mutableMapOf<KClass<*>, JsonElement?>()
	private lateinit var apiKeys: ApiKeyRepository
	private lateinit var resolver: ModelResolverImpl
	private lateinit var repo: ModelConfigRepository
	
	@BeforeTest
	fun setUp() {
		entries.clear()
		stubJsonStores(entries)
		apiKeys = ApiKeyRepository(secret)
		resolver = ModelResolverImpl(secret)
		repo = ModelConfigRepository(resolver)
		runBlocking {
			clearConfigStores()
			resolver.setDefaultModel(null)
		}
	}
	
	private suspend fun addProvider(): UUID {
		val provider = testProviderData(apiKey = apiKeys.add("key-${UUID.randomUUID()}", "sk"))
		ProviderRepository(apiKeys, resolver, repo).set(provider)
		return provider.id
	}
	
	@Test
	fun `set stores model when provider exists`() = runTest {
		val providerId = addProvider()
		val model = testModelData(providerId = providerId)
		
		repo.set(model)
		
		assertEquals(model, repo.get(model.id))
		assertEquals(listOf(model), repo.list())
	}
	
	@Test
	fun `set missing provider throws and does not store`() = runTest {
		val model = testModelData(providerId = UUID.randomUUID())
		
		assertFailsWith<ProviderNotFoundException> { repo.set(model) }
		assertNull(repo.get(model.id))
	}
	
	@Test
	fun `set duplicate name in same provider throws`() = runTest {
		val providerId = addProvider()
		val first = testModelData(providerId = providerId, displayName = "same")
		repo.set(first)
		val second = testModelData(providerId = providerId, displayName = "same")
		
		assertFailsWith<DuplicateModelNameException> { repo.set(second) }
		assertEquals(listOf(first), repo.list())
	}
	
	@Test
	fun `set same name in other provider is allowed`() = runTest {
		val first = testModelData(providerId = addProvider(), displayName = "same")
		repo.set(first)
		val second = testModelData(providerId = addProvider(), displayName = "same")
		
		repo.set(second)
		
		assertEquals(setOf(first, second), repo.list().toSet())
	}
	
	@Test
	fun `set same id updates model`() = runTest {
		val providerId = addProvider()
		val id = UUID.randomUUID()
		repo.set(testModelData(id = id, providerId = providerId, displayName = "old"))
		val updated = testModelData(id = id, providerId = providerId, displayName = "new")
		
		repo.set(updated)
		
		assertEquals(updated, repo.get(id))
		assertEquals(1, repo.list().size)
	}
	
	@Test
	fun `get unknown model returns null`() = runTest {
		assertNull(repo.get(UUID.randomUUID()))
	}
	
	@Test
	fun `remove deletes existing model`() = runTest {
		val model = testModelData(providerId = addProvider())
		repo.set(model)
		
		assertTrue(repo.remove(model.id))
		assertNull(repo.get(model.id))
	}
	
	@Test
	fun `remove default model throws and keeps model`() = runTest {
		val model = testModelData(providerId = addProvider())
		repo.set(model)
		resolver.setDefaultModel(model.id)
		
		assertFailsWith<DefaultModelDeletionException> { repo.remove(model.id) }
		assertEquals(model, repo.get(model.id))
	}
	
	@Test
	fun `remove unknown model returns false`() = runTest {
		assertFalse(repo.remove(UUID.randomUUID()))
	}
}
