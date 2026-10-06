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

import io.github.autotweaker.api.types.config.EnvType
import io.github.autotweaker.core.domain.tool.impl.bash.Bash
import io.github.autotweaker.core.infrastructure.container.ContainerManager
import io.github.autotweaker.core.test.TestServices
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.reflect.KClass
import kotlin.test.*

class EnvRepositoryTest {
	companion object {
		init {
			TestServices.init()
		}
	}
	
	private val entries = mutableMapOf<KClass<*>, JsonElement?>()
	private lateinit var container: ContainerManager
	private lateinit var repo: EnvRepository
	
	@BeforeTest
	fun setUp() {
		entries.clear()
		stubJsonStores(entries)
		container = mockk(relaxed = true)
		repo = EnvRepository(container)
		runBlocking { Bash.listEnv().forEach { Bash.removeEnv(it) } }
	}
	
	@Test
	fun `list container env delegates to container manager`() = runTest {
		coEvery { container.listEnv() } returns setOf("A", "B")
		
		assertEquals(listOf("A", "B"), repo.list(EnvType.CONTAINER_ENV))
	}
	
	@Test
	fun `container env set get remove delegate to container manager`() = runTest {
		coEvery { container.getEnv("A") } returns "v"
		coEvery { container.removeEnv("A") } returns true
		
		repo.set(EnvType.CONTAINER_ENV, "A", "v")
		assertEquals("v", repo.get(EnvType.CONTAINER_ENV, "A"))
		assertTrue(repo.remove(EnvType.CONTAINER_ENV, "A"))
		
		coVerify { container.setEnv("A", "v") }
		coVerify { container.removeEnv("A") }
	}
	
	@Test
	fun `bash env round trip uses bash store`() = runTest {
		repo.set(EnvType.BASH_ENV, "FOO", "bar")
		
		assertEquals("bar", repo.get(EnvType.BASH_ENV, "FOO"))
		assertEquals(listOf("FOO"), repo.list(EnvType.BASH_ENV))
		assertTrue(repo.remove(EnvType.BASH_ENV, "FOO"))
		assertNull(repo.get(EnvType.BASH_ENV, "FOO"))
		assertTrue(repo.list(EnvType.BASH_ENV).isEmpty())
	}
	
	@Test
	fun `bash env missing key returns null and remove false`() = runTest {
		assertNull(repo.get(EnvType.BASH_ENV, "MISSING"))
		assertFalse(repo.remove(EnvType.BASH_ENV, "MISSING"))
	}
}
