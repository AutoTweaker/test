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

package io.github.autotweaker.core.infrastructure.system

import io.github.autotweaker.api.types.shell.ShellEvent
import io.github.autotweaker.api.types.shell.ShellResult
import io.github.autotweaker.core.test.TestServices
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LocalShellExecutorTest {
	companion object {
		init {
			TestServices.init()
		}
	}
	
	private val shell = LocalShellExecutor()
	private lateinit var dir: Path
	
	@BeforeTest
	fun setUp() {
		dir = Files.createTempDirectory("shell-exec-test").toRealPath()
	}
	
	@AfterTest
	fun tearDown() {
		dir.toFile().deleteRecursively()
	}
	
	private suspend fun collect(
		command: String,
		workDir: Path = dir,
		env: Map<String, String> = emptyMap(),
		timeout: Duration = 30.seconds,
	): List<ShellEvent> = shell.exec(command, workDir, env, timeout).toList()
	
	private fun stdoutOf(events: List<ShellEvent>): List<String> =
		events.filterIsInstance<ShellEvent.Stdout>().map { it.text }
	
	private fun stderrOf(events: List<ShellEvent>): List<String> =
		events.filterIsInstance<ShellEvent.Stderr>().map { it.text }
	
	private fun exitOf(events: List<ShellEvent>): ShellResult =
		(events.last() as ShellEvent.Exit).result
	
	@Test
	fun `stdout lines are emitted in order followed by exit zero`() = runTest {
		val events = collect("printf 'line1\\nline2\\n'")
		
		assertEquals(listOf("line1", "line2"), stdoutOf(events))
		assertTrue(events.last() is ShellEvent.Exit)
		assertEquals(0, exitOf(events).exitCode)
		assertFalse(exitOf(events).timeout)
	}
	
	@Test
	fun `stderr lines are emitted separately from stdout`() = runTest {
		val events = collect("echo oops 1>&2")
		
		assertEquals(listOf("oops"), stderrOf(events))
		assertEquals(emptyList(), stdoutOf(events))
		assertEquals(0, exitOf(events).exitCode)
		assertFalse(exitOf(events).timeout)
	}
	
	@Test
	fun `non zero exit code is reported without timeout`() = runTest {
		val events = collect("exit 3")
		
		assertEquals(3, exitOf(events).exitCode)
		assertFalse(exitOf(events).timeout)
	}
	
	@Test
	fun `command sees the environment passed to exec`() = runTest {
		val events = collect("echo \"\$AT_TEST_ENV\"", env = mapOf("AT_TEST_ENV" to "from-test"))
		
		assertEquals(listOf("from-test"), stdoutOf(events))
		assertEquals(0, exitOf(events).exitCode)
	}
	
	@Test
	fun `command runs in the given working directory`() = runTest {
		val workDir = Files.createTempDirectory("shell-exec-cwd").toRealPath()
		try {
			val events = collect("pwd", workDir = workDir)
			
			assertEquals(listOf(workDir.toString()), stdoutOf(events))
		} finally {
			workDir.toFile().deleteRecursively()
		}
	}
	
	@Test
	fun `command exceeding timeout is killed and reported as timeout`() = runTest {
		val events = collect("sleep 30", timeout = 300.milliseconds)
		
		assertTrue(exitOf(events).timeout)
		assertEquals(-1, exitOf(events).exitCode)
	}
}
