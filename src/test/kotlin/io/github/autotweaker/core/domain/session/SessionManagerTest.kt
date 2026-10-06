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

package io.github.autotweaker.core.domain.session

import io.github.autotweaker.api.adapter.PathResolver
import io.github.autotweaker.api.now
import io.github.autotweaker.api.store.JsonStore
import io.github.autotweaker.api.types.KebabCase.Companion.toKebab
import io.github.autotweaker.api.types.Sha256
import io.github.autotweaker.api.types.agent.AgentContext
import io.github.autotweaker.api.types.agent.AgentData
import io.github.autotweaker.api.types.agent.AgentIndex
import io.github.autotweaker.api.types.agent.ModelConfig
import io.github.autotweaker.api.types.exception.InvalidWorkspacePathException
import io.github.autotweaker.api.types.exception.notfound.SessionNotFoundException
import io.github.autotweaker.api.types.exception.notfound.WorkspaceNotFoundException
import io.github.autotweaker.api.types.session.SessionData
import io.github.autotweaker.core.domain.agent.AgentDeps
import io.github.autotweaker.core.domain.agent.RuntimeModel
import io.github.autotweaker.core.domain.agent.chat.MessageConverts
import io.github.autotweaker.core.domain.agent.compact.SummaryService
import io.github.autotweaker.core.domain.agent.tool.ToolProvider
import io.github.autotweaker.core.domain.chat.ResilientChat
import io.github.autotweaker.core.domain.port.FileContent
import io.github.autotweaker.core.domain.port.GitStatusService
import io.github.autotweaker.core.domain.port.ModelResolver
import io.github.autotweaker.core.domain.port.RawFileSystem
import io.github.autotweaker.core.domain.port.SessionRepository
import io.github.autotweaker.core.domain.port.SystemInfoService
import io.github.autotweaker.core.domain.port.TemporaryStorage
import io.github.autotweaker.core.domain.port.UsageRepository
import io.github.autotweaker.core.infrastructure.persist.json.WorkspaceManager
import io.github.autotweaker.core.test.TestServices
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SessionManagerTest {
	companion object {
		init {
			TestServices.init()
		}
	}
	
	private lateinit var workspaceDir: Path
	private lateinit var workspaceId: UUID
	private lateinit var sessionRepo: SessionRepository
	private lateinit var manager: SessionManager
	
	// 模拟 SessionRepository 的会话表，便于验证保存 / 删除的往返行为
	private val storedSessions = mutableMapOf<UUID, SessionData>()
	
	@BeforeTest
	fun setUp() {
		workspaceDir = Files.createTempDirectory("session-manager-test")
		// 工具 meta() 会访问 JsonStore（EnvStore 等），屏蔽底层 H2
		every { TestServices.jsonStore.namespace(any()) } answers {
			mockk<JsonStore>().also {
				every { it.get() } answers { null }
				every { it.set(any()) } answers { }
			}
		}
		storedSessions.clear()
		sessionRepo = mockk(relaxed = true)
		coEvery { sessionRepo.saveSessions(any()) } answers {
			firstArg<List<SessionData>>().forEach { storedSessions[it.id] = it }
		}
		coEvery { sessionRepo.loadSession(any()) } answers { storedSessions[firstArg<UUID>()] }
		coEvery { sessionRepo.deleteSessions(any()) } answers {
			firstArg<Set<UUID>>().forEach { storedSessions.remove(it) }
		}
		coEvery { sessionRepo.loadAgent(any()) } returns null
		coEvery { sessionRepo.loadMessageIds(any()) } returns emptySet()
		workspaceId = runBlocking {
			WorkspaceManager.create("session-manager-${UUID.randomUUID()}", workspaceDir).id
		}
		manager = newManager()
	}
	
	@AfterTest
	fun tearDown() {
		runBlocking { manager.shutdown() }
		workspaceDir.toFile().deleteRecursively()
	}
	
	private fun newManager(modelResolver: ModelResolver = modelResolver()): SessionManager = SessionManager(
		agentDeps = deps(),
		sessionRepo = sessionRepo,
		usageRepo = mockk<UsageRepository>(relaxed = true),
		modelRepo = modelResolver,
		secretStore = TestServices.secretStore,
	)
	
	private fun modelResolver() = mockk<ModelResolver>().also {
		coEvery { it.resolve(any()) } returns runtimeModel()
	}
	
	private fun runtimeModel() = mockk<RuntimeModel>(relaxed = true).also { model ->
		every { model.id } returns UUID.randomUUID()
	}
	
	private fun modelConfig() = ModelConfig(
		model = UUID.randomUUID(),
		reasoning = null,
		summarize = UUID.randomUUID(),
		compact = UUID.randomUUID(),
		fallback = emptyList(),
	)
	
	private fun agentData(agentId: UUID, sessionId: UUID) = AgentData(
		id = agentId,
		name = SessionImpl.MAIN_AGENT_NAME.toKebab(),
		sessionId = sessionId,
		creationTime = now(),
		lastAccessTime = now(),
		model = modelConfig(),
		context = AgentContext.emptyContext("system prompt"),
		activeTools = emptySet(),
	)
	
	private fun deps(chat: ResilientChat = mockk(relaxed = true)) = AgentDeps(
		messageCacheImpl = TestServices.messageCache,
		resilientChat = chat,
		messageConverts = MessageConverts(
			fileSystem = mockk<RawFileSystem>(relaxed = true) {
				coEvery { read(any()) } returns FileContent("", false, Sha256(ByteArray(32)))
			},
			pathResolver = mockk<PathResolver>(relaxed = true),
			systemInfo = mockk<SystemInfoService>(relaxed = true),
			gitService = mockk<GitStatusService>(relaxed = true),
		),
		toolProvider = ToolProvider(
			shellExecutor = mockk(relaxed = true),
			rawFileSystem = mockk<RawFileSystem>(relaxed = true),
			pathResolver = mockk<PathResolver>(relaxed = true),
			temporaryStorage = mockk<TemporaryStorage>(relaxed = true),
			summaryService = SummaryService(chat),
		),
		pathResolver = mockk<PathResolver>(relaxed = true),
		temporaryStorage = mockk<TemporaryStorage>(relaxed = true),
	)
	

	
	private suspend fun storeSession(id: UUID, workspace: UUID): SessionData {
		val data = SessionData(
			id = id,
			title = "existing",
			overview = null,
			workspaceId = workspace,
			creationTime = now(),
			lastAccessTime = now(),
			agentIndex = AgentIndex.new(),
		)
		storedSessions[id] = data
		return data
	}
	
	@Test
	fun `get returns registered session and null for unknown id`() = runTest {
		val id = manager.create(workspaceId, modelConfig())
		
		assertEquals(id, manager.get(id)!!.id)
		assertSame(manager.get(id), manager.getOrRestore(id))
		assertNull(manager.get(UUID.randomUUID()))
	}
	
	@Test
	fun `create with unknown workspace throws WorkspaceNotFoundException`() = runTest {
		assertFailsWith<WorkspaceNotFoundException> {
			manager.create(UUID.randomUUID(), modelConfig())
		}
	}
	
	@Test
	fun `create with workspace path that is not a directory throws InvalidWorkspacePathException`() = runTest {
		val dir = Files.createTempDirectory("session-manager-missing")
		val ws = WorkspaceManager.create("session-manager-missing-${UUID.randomUUID()}", dir)
		dir.toFile().deleteRecursively()
		
		assertFailsWith<InvalidWorkspacePathException> {
			manager.create(ws.id, modelConfig())
		}
	}
	
	@Test
	fun `create persists session and links it into workspace`() = runTest {
		val id = manager.create(workspaceId, modelConfig())
		
		assertEquals(workspaceId, storedSessions.getValue(id).workspaceId)
		assertEquals(id, storedSessions.getValue(id).id)
		assertTrue(WorkspaceManager.getData(workspaceId)!!.sessionIds.contains(id))
		assertEquals(workspaceId, manager.get(id)!!.workspaceId)
	}
	
	@Test
	fun `create rolls back the stored session when saving fails`() = runTest {
		coEvery { sessionRepo.saveSessions(any()) } throws IllegalStateException("save failed")
		
		assertFailsWith<IllegalStateException> {
			manager.create(workspaceId, modelConfig())
		}
		
		assertTrue(storedSessions.isEmpty())
		coVerify { sessionRepo.deleteSessions(any()) }
	}
	
	@Test
	fun `delete unknown session returns false and keeps repository untouched`() = runTest {
		assertFalse(manager.delete(UUID.randomUUID()))
		
		coVerify(exactly = 0) { sessionRepo.deleteSessions(any()) }
	}
	
	@Test
	fun `delete removes session from repository and workspace`() = runTest {
		val id = manager.create(workspaceId, modelConfig())
		
		val deleted = manager.delete(id)
		
		assertTrue(deleted)
		assertNull(manager.get(id))
		assertFalse(storedSessions.containsKey(id))
		coVerify { sessionRepo.deleteSessions(setOf(id)) }
		assertFalse(WorkspaceManager.getData(workspaceId)!!.sessionIds.contains(id))
	}
	
	@Test
	fun `delete still succeeds when workspace is already gone`() = runTest {
		val id = UUID.randomUUID()
		storeSession(id, UUID.randomUUID())
		
		val deleted = manager.delete(id)
		
		assertTrue(deleted)
		assertFalse(storedSessions.containsKey(id))
	}
	
	@Test
	fun `getOrRestore unknown session throws SessionNotFoundException`() = runTest {
		assertFailsWith<SessionNotFoundException> {
			manager.getOrRestore(UUID.randomUUID())
		}
	}
	
	@Test
	fun `getOrRestore rebuilds session from repository and caches it`() = runTest {
		val id = UUID.randomUUID()
		val index = AgentIndex.new()
		storeSession(id, workspaceId).also { storedSessions[id] = it.copy(agentIndex = index) }
		coEvery { sessionRepo.loadAgent(index.main.id) } returns agentData(index.main.id, id)
		
		val restored = manager.getOrRestore(id)
		
		assertEquals(id, restored.id)
		assertSame(restored, manager.get(id))
		assertSame(restored, manager.getOrRestore(id))
	}
	
	@Test
	fun `shutdown marks agents of active sessions dead`() = runTest {
		val id = manager.create(workspaceId, modelConfig())
		val session = manager.get(id)!!
		val agent = assertNotNull(session.getOrNull(session.agentIndex.value.main.id))
		
		manager.shutdown()
		
		assertTrue(agent.status.value.isDead)
	}
}
