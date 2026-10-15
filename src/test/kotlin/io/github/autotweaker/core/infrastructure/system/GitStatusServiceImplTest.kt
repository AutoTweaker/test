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

import io.github.autotweaker.core.test.TestServices
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.*

class GitStatusServiceImplTest {
	companion object {
		init {
			TestServices.init()
		}
	}
	
	private lateinit var root: Path
	private val extraDirs = mutableListOf<Path>()
	
	@BeforeTest
	fun setUp() {
		root = Files.createTempDirectory("git-status-test").toRealPath()
	}
	
	@AfterTest
	fun tearDown() {
		root.toFile().deleteRecursively()
		extraDirs.forEach { it.toFile().deleteRecursively() }
		extraDirs.clear()
	}
	
	private fun git(vararg args: String): String {
		val process = ProcessBuilder(listOf("git", *args))
			.directory(root.toFile())
			.redirectErrorStream(true)
			.apply {
				environment()["GIT_AUTHOR_NAME"] = "Test"
				environment()["GIT_AUTHOR_EMAIL"] = "test@example.com"
				environment()["GIT_COMMITTER_NAME"] = "Test"
				environment()["GIT_COMMITTER_EMAIL"] = "test@example.com"
				environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
				environment()["GIT_CONFIG_SYSTEM"] = "/dev/null"
			}
			.start()
		val output = process.inputStream.bufferedReader().readText()
		check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
		return output.trim()
	}
	
	private fun initRepo() = git("init", "-b", "main")
	
	private fun writeFile(name: String, content: String) = root.resolve(name).writeText(content)
	
	private fun commitAll(message: String) {
		git("add", "-A")
		git("commit", "-m", message, "--no-gpg-sign")
	}
	
	private fun initRepoWithCommit() {
		initRepo()
		writeFile("init.txt", "init")
		commitAll("init")
	}
	
	@Test
	fun `isRepository is false for a plain directory`() {
		assertFalse(GitStatusServiceImpl.isRepository(root))
	}
	
	@Test
	fun `isRepository is true after git init`() {
		initRepo()
		
		assertTrue(GitStatusServiceImpl.isRepository(root))
	}
	
	@Test
	fun `head is null before the first commit`() {
		initRepo()
		
		assertNull(GitStatusServiceImpl.head(root))
	}
	
	@Test
	fun `head returns the sha of the latest commit`() {
		initRepoWithCommit()
		
		assertEquals(git("rev-parse", "HEAD"), GitStatusServiceImpl.head(root))
	}
	
	@Test
	fun `branch returns the current branch name`() {
		initRepo()
		
		assertEquals("main", GitStatusServiceImpl.branch(root))
	}
	
	@Test
	fun `remote is null when the branch has no upstream`() {
		initRepoWithCommit()
		
		assertNull(GitStatusServiceImpl.remote(root))
	}
	
	@Test
	fun `remote returns the upstream ref once the branch tracks a remote`() {
		val bareRoot = Files.createTempDirectory("git-status-origin").toRealPath()
		extraDirs.add(bareRoot)
		val origin = bareRoot.resolve("origin.git")
		check(ProcessBuilder("git", "init", "--bare", "-b", "main", origin.toString()).start().waitFor() == 0)
		initRepoWithCommit()
		git("remote", "add", "origin", origin.toString())
		git("push", "--set-upstream", "origin", "main")
		
		assertEquals("origin/main", GitStatusServiceImpl.remote(root))
	}
	
	@Test
	fun `staged lists staged file with the status prefix stripped`() {
		initRepoWithCommit()
		writeFile("staged.txt", "staged")
		git("add", "staged.txt")
		
		assertEquals(listOf("staged.txt"), GitStatusServiceImpl.staged(root))
		assertEquals(emptyList(), GitStatusServiceImpl.worktree(root))
	}
	
	@Test
	fun `worktree lists modified tracked file but not staged changes`() {
		initRepoWithCommit()
		writeFile("init.txt", "modified")
		
		assertEquals(listOf("init.txt"), GitStatusServiceImpl.worktree(root))
		assertEquals(emptyList(), GitStatusServiceImpl.staged(root))
	}
	
	@Test
	fun `untracked file appears in worktree but not in staged`() {
		initRepoWithCommit()
		writeFile("untracked.txt", "content")
		
		assertEquals(listOf("untracked.txt"), GitStatusServiceImpl.worktree(root))
		assertEquals(emptyList(), GitStatusServiceImpl.staged(root))
	}
	
	@Test
	fun `log returns at most count newest commits first`() {
		initRepoWithCommit()
		writeFile("second.txt", "second")
		commitAll("second")
		
		assertEquals(2, GitStatusServiceImpl.log(root, 10).size)
		val latest = GitStatusServiceImpl.log(root, 1)
		assertEquals(1, latest.size)
		assertTrue(latest.single().contains("second"))
	}
	
	@Test
	fun `log with non positive count returns empty even when commits exist`() {
		initRepoWithCommit()
		
		assertEquals(emptyList(), GitStatusServiceImpl.log(root, 0))
		assertEquals(emptyList(), GitStatusServiceImpl.log(root, -5))
	}
}
