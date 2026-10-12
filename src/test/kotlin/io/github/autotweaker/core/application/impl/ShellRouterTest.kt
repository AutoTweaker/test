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

package io.github.autotweaker.core.application.impl

import io.github.autotweaker.api.types.shell.ShellEvent
import io.github.autotweaker.api.types.shell.ShellExec
import io.github.autotweaker.core.infrastructure.container.ContainerManager
import io.github.autotweaker.core.infrastructure.system.LocalShellExecutor
import io.github.autotweaker.core.test.TestServices
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class ShellRouterTest {
	companion object {
		init {
			TestServices.init()
		}
	}
	
	private val workDir = Path.of("/tmp/work")
	private val local = mockk<LocalShellExecutor>()
	private val container = mockk<ContainerManager>()
	private val router = ShellRouter(local, container)
	
	@Test
	fun `container exec is routed to the container manager`() = runTest {
		val arg = ShellExec("ls -la", workDir, container = true, mapOf("K" to "V"), 3.seconds)
		every { container.exec("ls -la", workDir, mapOf("K" to "V"), 3.seconds) } returns
			flowOf(ShellEvent.Stdout("hi"))
		
		val events = router.exec(arg).toList()
		
		assertEquals(listOf(ShellEvent.Stdout("hi")), events)
		verify(exactly = 1) { container.exec("ls -la", workDir, mapOf("K" to "V"), 3.seconds) }
		verify(exactly = 0) { local.exec(any(), any(), any(), any()) }
	}
	
	@Test
	fun `local exec is routed to the local executor`() = runTest {
		val arg = ShellExec("pwd", workDir, container = false, emptyMap(), 1.seconds)
		every { local.exec("pwd", workDir, emptyMap(), 1.seconds) } returns
			flowOf(ShellEvent.Stderr("oops"))
		
		val events = router.exec(arg).toList()
		
		assertEquals(listOf(ShellEvent.Stderr("oops")), events)
		verify(exactly = 1) { local.exec("pwd", workDir, emptyMap(), 1.seconds) }
		verify(exactly = 0) { container.exec(any(), any(), any(), any()) }
	}
}
