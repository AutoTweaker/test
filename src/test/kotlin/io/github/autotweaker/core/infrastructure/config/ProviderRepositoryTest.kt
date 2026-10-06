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

import io.github.autotweaker.api.types.Url.Companion.toUrl
import io.github.autotweaker.api.types.exception.DefaultModelDeletionException
import io.github.autotweaker.api.types.exception.UnknownProviderTypeException
import io.github.autotweaker.api.types.exception.duplicate.DuplicateProviderNameException
import io.github.autotweaker.api.types.exception.notfound.ApiKeyNotFoundException
import io.github.autotweaker.core.infrastructure.persist.json.ModelResolverImpl
import io.github.autotweaker.core.test.TestServices
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import java.util.*
import kotlin.reflect.KClass
import kotlin.test.*

class ProviderRepositoryTest {
	companion object {
		init {
			TestServices.init()
		}
	}
	
	private val secret = InMemorySecretStore()
	private val entries = mutableMapOf<KClass<*>, JsonElement?>()
	private lateinit var apiKeys: ApiKeyRepository
	private lateinit var resolver: ModelResolverImpl
	private lateinit var models: ModelConfigRepository
	private lateinit var providers: ProviderRepository
	
	@BeforeTest
	fun setUp() {
		entries.clear()
		stubJsonStores(entries)
		apiKeys = ApiKeyRepository(secret)
		resolver = ModelResolverImpl(secret)
		models = ModelConfigRepository(resolver)
		providers = ProviderRepository(apiKeys, resolver, models)
		runBlocking {
			clearConfigStores()
			resolver.setDefaultModel(null)
		}
	}
	
	@Test
	fun `set stores provider when api key exists`() = runTest {
		val provider = testProviderData(apiKey = apiKeys.add("key", "sk"))
		
		providers.set(provider)
		
		assertEquals(provider, providers.get(provider.id))
		assertEquals(listOf(provider), providers.list())
	}
	
	@Test
	fun `set duplicate display name throws and does not store`() = runTest {
		val first = testProviderData(apiKey = apiKeys.add("a", "sk-a"), displayName = "same")
		providers.set(first)
		val second = testProviderData(apiKey = apiKeys.add("b", "sk-b"), displayName = "same")
		
		assertFailsWith<DuplicateProviderNameException> { providers.set(second) }
		assertNull(providers.get(second.id))
		assertEquals(listOf(first), providers.list())
	}
	
	@Test
	fun `set same id updates provider`() = runTest {
		val keyId = apiKeys.add("key", "sk")
		val id = UUID.randomUUID()
		providers.set(testProviderData(id = id, apiKey = keyId, baseUrl = "https://old.test/v1".toUrl()))
		val updated = testProviderData(id = id, apiKey = keyId, baseUrl = "https://new.test/v1".toUrl())
		
		providers.set(updated)
		
		assertEquals(updated, providers.get(id))
		assertEquals(1, providers.list().size)
	}
	
	@Test
	fun `set unknown provider type throws and does not store`() = runTest {
		val provider = testProviderData(apiKey = apiKeys.add("key", "sk"), providerType = "unknown")
		
		assertFailsWith<UnknownProviderTypeException> { providers.set(provider) }
		assertNull(providers.get(provider.id))
	}
	
	@Test
	fun `set missing api key throws and does not store`() = runTest {
		val provider = testProviderData(apiKey = UUID.randomUUID())
		
		assertFailsWith<ApiKeyNotFoundException> { providers.set(provider) }
		assertNull(providers.get(provider.id))
	}
	
	@Test
	fun `getMeta returns info of known provider type`() {
		assertEquals("deepseek", providers.getMeta("deepseek").name)
	}
	
	@Test
	fun `listAvailable contains built-in provider types`() {
		assertTrue(providers.listAvailable().containsAll(setOf("deepseek", "mimo")))
	}
	
	@Test
	fun `remove deletes provider and its models`() = runTest {
		val provider = testProviderData(apiKey = apiKeys.add("key", "sk"))
		providers.set(provider)
		val model = testModelData(providerId = provider.id)
		models.set(model)
		
		assertTrue(providers.remove(provider.id))
		assertNull(providers.get(provider.id))
		assertNull(models.get(model.id))
	}
	
	@Test
	fun `remove throws when provider holds default model`() = runTest {
		val provider = testProviderData(apiKey = apiKeys.add("key", "sk"))
		providers.set(provider)
		val model = testModelData(providerId = provider.id)
		models.set(model)
		resolver.setDefaultModel(model.id)
		
		assertFailsWith<DefaultModelDeletionException> { providers.remove(provider.id) }
		assertNotNull(providers.get(provider.id))
		assertNotNull(models.get(model.id))
	}
	
	@Test
	fun `remove provider succeeds when default model belongs to another provider`() = runTest {
		val removed = testProviderData(apiKey = apiKeys.add("a", "sk-a"))
		providers.set(removed)
		models.set(testModelData(providerId = removed.id))
		val kept = testProviderData(apiKey = apiKeys.add("b", "sk-b"))
		providers.set(kept)
		val defaultModel = testModelData(providerId = kept.id)
		models.set(defaultModel)
		resolver.setDefaultModel(defaultModel.id)
		
		assertTrue(providers.remove(removed.id))
		assertNull(providers.get(removed.id))
		assertEquals(defaultModel.id, resolver.getDefaultModel())
		assertNotNull(models.get(defaultModel.id))
	}
	
	@Test
	fun `remove unknown provider returns false`() = runTest {
		assertFalse(providers.remove(UUID.randomUUID()))
	}
}
