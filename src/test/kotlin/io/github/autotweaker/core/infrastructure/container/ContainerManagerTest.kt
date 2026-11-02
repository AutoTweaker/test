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

package io.github.autotweaker.core.infrastructure.container

import io.github.autotweaker.api.store.JsonStore
import io.github.autotweaker.api.types.shell.ShellEvent
import io.github.autotweaker.api.types.shell.ShellResult
import io.github.autotweaker.core.test.TestServices
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import java.nio.file.Path
import kotlin.reflect.KClass
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ContainerManagerTest {
	companion object {
		init {
			TestServices.init()
		}
	}
	
	private val entries = mutableMapOf<KClass<*>, JsonElement?>()
	
	@BeforeTest
	fun setUp() {
		entries.clear()
		TestServices.secretMap.clear()
		TestServices.removedSecrets.clear()
		every { TestServices.jsonStore.namespace(any()) } answers {
			val key = firstArg<KClass<*>>()
			mockk<JsonStore>().also {
				every { it.get() } answers { entries[key] }
				every { it.set(any()) } answers { entries[key] = firstArg<JsonElement>() }
			}
		}
	}
	
	private class ExecRequest(
		val containerId: String,
		val command: List<String>,
		val workDir: Path?,
		val env: Map<String, String>,
		val timeout: Duration,
	)
	
	private class FakeContainerService(
		private val access: Boolean = true,
		private val containerId: String = "container-1",
		private val failedPulls: Int = 0,
	) : ContainerService {
		val pulls = mutableListOf<String>()
		var startCalls = 0
		var startedImage: String? = null
		val stopped = mutableListOf<String>()
		var shutdownCalls = 0
		var stopFailure: Throwable? = null
		val execRequests = mutableListOf<ExecRequest>()
		
		override suspend fun pull(image: String) {
			pulls.add(image)
			if (pulls.size <= failedPulls) throw IllegalStateException("pull failed")
		}
		
		override suspend fun start(image: String): String {
			startCalls++
			startedImage = image
			return containerId
		}
		
		override suspend fun stop(containerId: String) {
			stopFailure?.let { throw it }
			stopped.add(containerId)
		}
		
		override fun shutdown() {
			shutdownCalls++
		}
		
		override fun checkAccess(): Boolean = access
		
		override fun exec(
			containerId: String,
			command: List<String>,
			workDir: Path?,
			env: Map<String, String>,
			timeout: Duration,
		): Flow<ShellEvent> {
			execRequests.add(ExecRequest(containerId, command, workDir, env, timeout))
			return flowOf(ShellEvent.Exit(ShellResult(0, false, Duration.ZERO)))
		}
	}
	
	private fun newManager(service: ContainerService) =
		ContainerManager(TestServices.secretStore, service)
	
	@Test
	fun `exec without container access reports denial and never touches service`() = runTest {
		val service = FakeContainerService(access = false)
		val manager = newManager(service)
		manager.init()
		
		assertFalse(manager.isRunning)
		val events = manager.exec("ls", Path.of("/tmp"), emptyMap(), 10.seconds).toList()
		
		assertEquals(2, events.size)
		val stderr = assertIs<ShellEvent.Stderr>(events[0])
		assertEquals(ContainerSettings.AccessDeniedMessage().default.value + "\n", stderr.text)
		val exit = assertIs<ShellEvent.Exit>(events[1])
		assertEquals(ShellResult(exitCode = -1, timeout = false, duration = Duration.ZERO), exit.result)
		assertEquals(0, service.startCalls)
		assertTrue(service.execRequests.isEmpty())
	}
	
	@Test
	fun `exec starts container once and forwards command workdir and timeout`() = runTest {
		val service = FakeContainerService()
		val manager = newManager(service)
		manager.init()
		val workDir = Path.of("/workspace/project")
		
		manager.exec("echo hi", workDir, emptyMap(), 30.seconds).toList()
		manager.exec("echo again", workDir, emptyMap(), 5.seconds).toList()
		
		assertEquals(1, service.startCalls)
		assertEquals(ContainerSettings.DockerImage().default.value, service.startedImage)
		assertEquals(2, service.execRequests.size)
		val first = service.execRequests.first()
		assertEquals("container-1", first.containerId)
		assertEquals(listOf("bash", "-lc", "echo hi"), first.command)
		assertEquals(workDir, first.workDir)
		assertEquals(30.seconds, first.timeout)
		assertTrue(manager.isRunning)
	}
	
	@Test
	fun `exec merges stored env and lets call env override it`() = runTest {
		val service = FakeContainerService()
		val manager = newManager(service)
		manager.init()
		manager.setEnv("STORED", "from-store")
		manager.setEnv("OVERRIDDEN", "original")
		
		manager.exec(
			"env",
			Path.of("/workspace"),
			mapOf("OVERRIDDEN" to "patched", "EXTRA" to "1"),
			1.seconds
		).toList()
		
		assertEquals(
			mapOf("STORED" to "from-store", "OVERRIDDEN" to "patched", "EXTRA" to "1"),
			service.execRequests.single().env
		)
	}
	
	@Test
	fun `exec skips stored env whose secret is gone`() = runTest {
		val service = FakeContainerService()
		val manager = newManager(service)
		manager.init()
		manager.setEnv("BROKEN", "lost")
		manager.setEnv("OK", "kept")
		val brokenUuid = TestServices.secretMap.entries.single { it.value == "lost" }.key
		TestServices.secretMap.remove(brokenUuid)
		
		manager.exec("env", Path.of("/workspace"), emptyMap(), 1.seconds).toList()
		
		assertEquals(mapOf("OK" to "kept"), service.execRequests.single().env)
	}
	
	@Test
	fun `container start retries a failed image pull once`() = runTest {
		val service = FakeContainerService(failedPulls = 1)
		val manager = newManager(service)
		manager.init()
		
		manager.exec("ls", Path.of("/workspace"), emptyMap(), 5.seconds).toList()
		
		assertEquals(2, service.pulls.size)
		assertEquals(1, service.startCalls)
		assertTrue(manager.isRunning)
	}
	
	@Test
	fun `exec fails and leaves container stopped when pull keeps failing`() = runTest {
		val service = FakeContainerService(failedPulls = 2)
		val manager = newManager(service)
		manager.init()
		
		assertFailsWith<IllegalStateException> {
			manager.exec("ls", Path.of("/workspace"), emptyMap(), 5.seconds).toList()
		}
		
		assertEquals(2, service.pulls.size)
		assertEquals(0, service.startCalls)
		assertFalse(manager.isRunning)
		assertTrue(service.execRequests.isEmpty())
	}
	
	@Test
	fun `stop before start is a no-op`() = runTest {
		val service = FakeContainerService(access = false)
		val manager = newManager(service)
		manager.init()
		
		manager.stop()
		
		assertTrue(service.stopped.isEmpty())
		assertEquals(0, service.shutdownCalls)
	}
	
	@Test
	fun `stop releases container and shuts service down`() = runTest {
		val service = FakeContainerService()
		val manager = newManager(service)
		manager.init()
		manager.exec("ls", Path.of("/workspace"), emptyMap(), 5.seconds).toList()
		
		manager.stop()
		
		assertEquals(listOf("container-1"), service.stopped)
		assertEquals(1, service.shutdownCalls)
		assertFalse(manager.isRunning)
	}
	
	@Test
	fun `stop failure still resets state and shuts service down`() = runTest {
		val service = FakeContainerService()
		val manager = newManager(service)
		manager.init()
		manager.exec("ls", Path.of("/workspace"), emptyMap(), 5.seconds).toList()
		service.stopFailure = IllegalStateException("stop failed")
		
		assertFailsWith<IllegalStateException> { manager.stop() }
		
		assertFalse(manager.isRunning)
		assertEquals(1, service.shutdownCalls)
	}
}
