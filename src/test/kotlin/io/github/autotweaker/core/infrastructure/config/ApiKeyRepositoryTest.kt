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

import io.github.autotweaker.api.types.exception.ApiKeyInUseException
import io.github.autotweaker.api.types.exception.duplicate.DuplicateApiKeyException
import io.github.autotweaker.api.types.exception.notfound.ApiKeyNotFoundException
import io.github.autotweaker.api.types.exception.notfound.SecretNotFoundException
import io.github.autotweaker.core.infrastructure.persist.json.ProviderStore
import io.github.autotweaker.core.test.TestServices
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import java.util.*
import kotlin.reflect.KClass
import kotlin.test.*

class ApiKeyRepositoryTest {
	companion object {
		init {
			TestServices.init()
		}
	}
	
	private val secret = InMemorySecretStore()
	private val entries = mutableMapOf<KClass<*>, JsonElement?>()
	private lateinit var repo: ApiKeyRepository
	
	@BeforeTest
	fun setUp() {
		entries.clear()
		stubJsonStores(entries)
		repo = ApiKeyRepository(secret)
		runBlocking { clearConfigStores() }
	}
	
	@Test
	fun `add stores secret and lists name`() = runTest {
		val id = repo.add("openai", "sk-123")
		
		assertEquals("sk-123", secret.secrets[id])
		assertEquals(mapOf(id to "openai"), repo.list())
	}
	
	@Test
	fun `add duplicate name throws and keeps original`() = runTest {
		val id = repo.add("openai", "sk-123")
		
		assertFailsWith<DuplicateApiKeyException> { repo.add("openai", "sk-456") }
		assertEquals(mapOf(id to "openai"), repo.list())
	}
	
	@Test
	fun `get returns stored secret`() = runTest {
		val id = repo.add("openai", "sk-123")
		
		assertEquals("sk-123", repo.get(id))
	}
	
	@Test
	fun `get unknown id throws SecretNotFound`() = runTest {
		assertFailsWith<SecretNotFoundException> { repo.get(UUID.randomUUID()) }
	}
	
	@Test
	fun `remove by name deletes key and secret`() = runTest {
		val id = repo.add("openai", "sk-123")
		
		assertTrue(repo.remove("openai"))
		assertTrue(repo.list().isEmpty())
		assertFalse(secret.secrets.containsKey(id))
	}
	
	@Test
	fun `remove by unknown name returns false`() = runTest {
		assertFalse(repo.remove("missing"))
	}
	
	@Test
	fun `remove by id deletes key and secret`() = runTest {
		val id = repo.add("openai", "sk-123")
		
		assertTrue(repo.remove(id))
		assertTrue(repo.list().isEmpty())
		assertFalse(secret.secrets.containsKey(id))
	}
	
	@Test
	fun `remove by unknown id returns false`() = runTest {
		assertFalse(repo.remove(UUID.randomUUID()))
	}
	
	@Test
	fun `remove key used by provider throws and keeps key`() = runTest {
		val id = repo.add("openai", "sk-123")
		ProviderStore.set(testProviderData(apiKey = id))
		
		assertFailsWith<ApiKeyInUseException> { repo.remove(id) }
		assertEquals(mapOf(id to "openai"), repo.list())
		assertTrue(secret.secrets.containsKey(id))
	}
	
	@Test
	fun `remove by name for in-use key throws`() = runTest {
		val id = repo.add("openai", "sk-123")
		ProviderStore.set(testProviderData(apiKey = id))
		
		assertFailsWith<ApiKeyInUseException> { repo.remove("openai") }
		assertEquals(mapOf(id to "openai"), repo.list())
	}
	
	@Test
	fun `ensure runs action for existing key`() = runTest {
		val id = repo.add("openai", "sk-123")
		var called = false
		
		repo.ensure(id) { called = true }
		
		assertTrue(called)
	}
	
	@Test
	fun `ensure unknown key throws and skips action`() = runTest {
		var called = false
		
		assertFailsWith<ApiKeyNotFoundException> {
			repo.ensure(UUID.randomUUID()) { called = true }
		}
		assertFalse(called)
	}
}
