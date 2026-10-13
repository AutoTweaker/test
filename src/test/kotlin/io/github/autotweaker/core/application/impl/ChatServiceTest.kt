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

import io.github.autotweaker.api.types.Url.Companion.toUrl
import io.github.autotweaker.api.types.llm.ChatMessage
import io.github.autotweaker.api.types.llm.ChatResult
import io.github.autotweaker.api.types.llm.ChatRequest
import io.github.autotweaker.api.types.llm.LlmResult
import io.github.autotweaker.api.types.llm.ModelData.ModelInfo
import io.github.autotweaker.api.types.llm.Usage
import io.github.autotweaker.api.types.llm.UsageEntry
import io.github.autotweaker.core.domain.agent.RuntimeModel
import io.github.autotweaker.core.domain.agent.RuntimeProvider
import io.github.autotweaker.core.domain.chat.ResilientChat
import io.github.autotweaker.core.domain.port.ModelResolver
import io.github.autotweaker.core.domain.port.UsageRepository
import io.github.autotweaker.core.test.TestServices
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChatServiceTest {
	companion object {
		init {
			TestServices.init()
		}
	}
	
	private val modelRepo = mockk<ModelResolver>()
	private val usageRepo = mockk<UsageRepository>(relaxed = true)
	private val resilientChat = mockk<ResilientChat>()
	private val service = ChatService(modelRepo, usageRepo, resilientChat)
	
	private fun model() = RuntimeModel(
		id = UUID.randomUUID(),
		provider = RuntimeProvider(
			UUID.randomUUID(),
			"provider",
			"https://api.example.com/v1".toUrl(),
			"sk-key",
			emptyList()
		),
		modelInfo = ModelInfo(
			modelId = "test-model",
			contextWindow = 128_000,
			maxOutputTokens = 4096,
			supportsStreaming = true,
			supportsToolCalls = true,
			supportsReasoning = true,
			supportsJsonOutput = true,
			supportsImage = false,
		),
	)
	
	private fun request(modelId: UUID, fallbacks: List<UUID>? = null) =
		ChatRequest(model = modelId, fallbackModels = fallbacks, messages = emptyList())
	
	private fun assembled(usage: Usage?) =
		LlmResult.Assembled(ChatMessage.Assistant("hi"), usage)
	
	private fun stubReturning(results: Flow<ChatResult>) {
		every {
			resilientChat.execute(
				model = any(),
				fallbackModels = any(),
				timeout = any(),
				instructions = any(),
				messages = any(),
				reasoning = any(),
				stream = any(),
				maxTokens = any(),
				tools = any(),
				temperature = any(),
				jsonOutput = any()
			)
		} returns results
	}
	
	@Test
	fun `chat resolves primary model and fallbacks then forwards them`() = runTest {
		val primary = model()
		val fallback = model()
		coEvery { modelRepo.resolve(primary.id) } returns primary
		coEvery { modelRepo.resolve(fallback.id) } returns fallback
		val forwardedModel = slot<RuntimeModel>()
		val forwardedFallbacks = slot<List<RuntimeModel>>()
		every {
			resilientChat.execute(
				model = capture(forwardedModel),
				fallbackModels = capture(forwardedFallbacks),
				timeout = any(),
				instructions = any(),
				messages = any(),
				reasoning = any(),
				stream = any(),
				maxTokens = any(),
				tools = any(),
				temperature = any(),
				jsonOutput = any()
			)
		} returns emptyFlow()
		
		service.chat(request(primary.id, listOf(fallback.id))).toList()
		
		assertEquals(primary, forwardedModel.captured)
		assertEquals(listOf(fallback), forwardedFallbacks.captured)
	}
	
	@Test
	fun `chat forwards null fallbacks when request declares none`() = runTest {
		val primary = model()
		coEvery { modelRepo.resolve(primary.id) } returns primary
		stubReturning(emptyFlow())
		
		service.chat(request(primary.id)).toList()
		
		coVerify(exactly = 1) {
			resilientChat.execute(
				model = primary,
				fallbackModels = isNull(),
				timeout = isNull(),
				instructions = isNull(),
				messages = emptyList(),
				reasoning = isNull(),
				stream = false,
				maxTokens = isNull(),
				tools = isNull(),
				temperature = isNull(),
				jsonOutput = isNull()
			)
		}
	}
	
	@Test
	fun `chat emits underlying results unchanged`() = runTest {
		val primary = model()
		coEvery { modelRepo.resolve(primary.id) } returns primary
		val chunk = ChatResult(LlmResult.Chunk("partial", null, null), primary.id)
		val final = ChatResult(assembled(null), primary.id)
		stubReturning(flowOf(chunk, final))
		
		val results = service.chat(request(primary.id)).toList()
		
		assertEquals(listOf(chunk, final), results)
	}
	
	@Test
	fun `chat persists usage from assembled result tagged with chunk model and null agent`() = runTest {
		val primary = model()
		coEvery { modelRepo.resolve(primary.id) } returns primary
		val usage = Usage(promptTokens = 10, completionTokens = 5)
		val result = ChatResult(assembled(usage), primary.id)
		stubReturning(flowOf(result))
		val saved = slot<UsageEntry>()
		
		val results = service.chat(request(primary.id)).toList()
		
		assertEquals(listOf(result), results)
		coVerify(exactly = 1) { usageRepo.save(capture(saved)) }
		assertEquals(null, saved.captured.agentId)
		assertEquals(primary.id, saved.captured.modelId)
		assertEquals(usage, saved.captured.usage)
	}
	
	@Test
	fun `chat ignores usage on non-assembled chunks`() = runTest {
		val primary = model()
		coEvery { modelRepo.resolve(primary.id) } returns primary
		stubReturning(flowOf(ChatResult(LlmResult.Chunk("partial", null, null), primary.id)))
		
		service.chat(request(primary.id)).toList()
		
		coVerify(exactly = 0) { usageRepo.save(any()) }
	}
	
	@Test
	fun `chat persists nothing when assembled result carries no usage`() = runTest {
		val primary = model()
		coEvery { modelRepo.resolve(primary.id) } returns primary
		stubReturning(flowOf(ChatResult(assembled(null), primary.id)))
		
		service.chat(request(primary.id)).toList()
		
		coVerify(exactly = 0) { usageRepo.save(any()) }
	}
	
	@Test
	fun `chat persists usage even when underlying stream fails after emitting it`() = runTest {
		val primary = model()
		coEvery { modelRepo.resolve(primary.id) } returns primary
		val usage = Usage(promptTokens = 1, completionTokens = 2)
		stubReturning(
			flow {
				emit(ChatResult(assembled(usage), primary.id))
				throw IllegalStateException("stream broke")
			}
		)
		val saved = slot<UsageEntry>()
		
		assertFailsWith<IllegalStateException> {
			service.chat(request(primary.id)).toList()
		}
		
		coVerify(exactly = 1) { usageRepo.save(capture(saved)) }
		assertEquals(null, saved.captured.agentId)
		assertEquals(primary.id, saved.captured.modelId)
		assertEquals(usage, saved.captured.usage)
	}
}
