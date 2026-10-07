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

package io.github.autotweaker.core.domain.chat

import io.github.autotweaker.api.types.Sha256
import io.github.autotweaker.api.types.Url
import io.github.autotweaker.api.types.Url.Companion.toUrl
import io.github.autotweaker.api.types.exception.ChatRetriesExhaustedException
import io.github.autotweaker.api.types.llm.ChatMessage
import io.github.autotweaker.api.types.llm.ChatRequest
import io.github.autotweaker.api.types.llm.ChatResult
import io.github.autotweaker.api.types.llm.ChatTimeout
import io.github.autotweaker.api.types.llm.ContentPart
import io.github.autotweaker.api.types.llm.LlmResult
import io.github.autotweaker.api.types.llm.ModelData.Config
import io.github.autotweaker.api.types.llm.ModelData.ModelInfo
import io.github.autotweaker.api.types.llm.ProviderData.ErrorHandlingRule
import io.github.autotweaker.api.types.llm.ProviderData.ErrorHandlingRule.RecoveryStrategy
import io.github.autotweaker.api.types.llm.ReasoningEffort
import io.github.autotweaker.core.domain.agent.RuntimeModel
import io.github.autotweaker.core.domain.agent.RuntimeProvider
import io.github.autotweaker.core.domain.port.LlmGateway
import io.github.autotweaker.core.test.TestServices
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ResilientChatTest {
	companion object {
		init {
			TestServices.init()
		}
	}
	
	private class RecordingGateway(
		private val responder: (Int, ChatRequest) -> Flow<ChatResult>
	) : LlmGateway {
		val requests = mutableListOf<ChatRequest>()
		val timeouts = mutableListOf<ChatTimeout>()
		
		override fun send(
			request: ChatRequest,
			apiKey: String,
			baseUrl: Url,
			providerType: String,
			timeout: ChatTimeout
		): Flow<ChatResult> {
			requests += request
			timeouts += timeout
			return responder(requests.size, request)
		}
	}
	
	private fun provider(
		name: String = "provider-${UUID.randomUUID()}",
		rules: List<ErrorHandlingRule> = emptyList()
	) = RuntimeProvider(UUID.randomUUID(), name, "https://api.example.com/v1".toUrl(), "sk-key", rules)
	
	private fun modelInfo(
		modelId: String = "test-model",
		contextWindow: Int = 128_000,
		supportsStreaming: Boolean = true,
		supportsReasoning: Boolean = true,
		supportsImage: Boolean = false,
		supportsAudio: Boolean = false,
		supportsVideo: Boolean = false
	) = ModelInfo(
		modelId = modelId,
		contextWindow = contextWindow,
		maxOutputTokens = 4096,
		supportsStreaming = supportsStreaming,
		supportsToolCalls = true,
		supportsReasoning = supportsReasoning,
		supportsJsonOutput = true,
		supportsImage = supportsImage,
		supportsAudio = supportsAudio,
		supportsVideo = supportsVideo
	)
	
	private fun model(
		provider: RuntimeProvider = provider(),
		info: ModelInfo = modelInfo(),
		config: Config? = null
	) = RuntimeModel(UUID.randomUUID(), provider, info, config)
	
	private fun assembled(content: String) = ChatResult.Assembled(ChatMessage.Assistant(content))
	
	@Test
	fun `successful attempt emits assembled result tagged with model id and normalizes empty strings`() = runTest {
		val model = model()
		val gateway = RecordingGateway { _, _ ->
			flow { emit(ChatResult.Assembled(ChatMessage.Assistant("", reasoningContent = ""))) }
		}
		
		val results = ResilientChat(gateway).execute(model, null, messages = emptyList()).toList()
		
		val assembled = results.single().result as ChatResult.Assembled
		assertNull(assembled.message.content)
		assertNull(assembled.message.reasoningContent)
		assertEquals(model.id, results.single().model)
		assertEquals("test-model", gateway.requests.single().model)
	}
	
	@Test
	fun `chunk with empty content and reasoning is normalized to null`() = runTest {
		val gateway = RecordingGateway { _, _ ->
			flow { emit(ChatResult.Chunk(content = "", reasoningContent = "", toolCalls = null)) }
		}
		
		val chunk = ResilientChat(gateway)
			.execute(model(), null, messages = emptyList())
			.toList().single().result as ChatResult.Chunk
		
		assertNull(chunk.content)
		assertNull(chunk.reasoningContent)
	}
	
	@Test
	fun `always failing response exhausts every round and throws with attempt count`() = runTest {
		val model = model()
		val gateway = RecordingGateway { _, _ -> flow { emit(ChatResult.Failed("down", 500)) } }
		val emitted = mutableListOf<LlmResult>()
		
		val error = assertFailsWith<ChatRetriesExhaustedException> {
			ResilientChat(gateway).execute(model, null, messages = emptyList()).collect { emitted += it }
		}
		
		// MaxRetries=5 次/轮，LlmChatRetries=2 -> 3 轮
		assertEquals(15, error.attempts)
		assertEquals(15, gateway.requests.size)
		assertEquals(15, emitted.size)
		assertTrue(emitted.all { it.result is ChatResult.Failed })
		assertEquals(listOf(model.id), emitted.map { it.model }.distinct())
	}
	
	@Test
	fun `failed result carrying an exception is emitted with the exception intact`() = runTest {
		val model = model(provider(rules = listOf(ErrorHandlingRule(500, RecoveryStrategy.FALLBACK))))
		val cause = RuntimeException("connection reset")
		val gateway = RecordingGateway { _, _ -> flow { emit(ChatResult.Failed("down", 500, cause)) } }
		val emitted = mutableListOf<LlmResult>()
		
		assertFailsWith<ChatRetriesExhaustedException> {
			ResilientChat(gateway).execute(model, null, messages = emptyList()).collect { emitted += it }
		}
		
		// FALLBACK 且无候选可退，每轮 1 次尝试，共 3 轮
		assertEquals(3, emitted.size)
		assertEquals(cause, (emitted.first().result as ChatResult.Failed).exception)
	}
	
	@Test
	fun `RETRY rule resends the same model and succeeds after transient failures`() = runTest {
		val model = model(provider(rules = listOf(ErrorHandlingRule(500, RecoveryStrategy.RETRY))))
		val gateway = RecordingGateway { index, _ ->
			flow {
				if (index <= 2) emit(ChatResult.Failed("boom", 500))
				else emit(assembled("ok"))
			}
		}
		
		val results = ResilientChat(gateway).execute(model, null, messages = emptyList()).toList()
		
		assertEquals(3, gateway.requests.size)
		assertEquals(3, results.size)
		assertTrue(results.last().result is ChatResult.Assembled)
		assertEquals(listOf(model.id, model.id, model.id), results.map { it.model })
	}
	
	@Test
	fun `FALLBACK rule drops the failed model and uses the next fallback model`() = runTest {
		val primary = model(provider(rules = listOf(ErrorHandlingRule(503, RecoveryStrategy.FALLBACK))), modelInfo("primary"))
		val fallback = model(provider(), modelInfo("fallback"))
		val gateway = RecordingGateway { _, request ->
			flow {
				if (request.model == "primary") emit(ChatResult.Failed("unavailable", 503))
				else emit(assembled("recovered"))
			}
		}
		
		val results = ResilientChat(gateway)
			.execute(primary, listOf(fallback), messages = emptyList())
			.toList()
		
		assertEquals(listOf("primary", "fallback"), gateway.requests.map { it.model })
		assertEquals(2, results.size)
		assertTrue(results[0].result is ChatResult.Failed)
		assertEquals(fallback.id, results[1].model)
		assertEquals("recovered", (results[1].result as ChatResult.Assembled).message.content)
	}
	
	@Test
	fun `PROVIDER_FALLBACK rule removes every candidate sharing the failed provider`() = runTest {
		val primaryProvider = provider(rules = listOf(ErrorHandlingRule(502, RecoveryStrategy.PROVIDER_FALLBACK)))
		val primary = model(primaryProvider, modelInfo("primary"))
		val sameProvider = model(primaryProvider, modelInfo("same-provider"))
		val otherProvider = model(provider(), modelInfo("other-provider"))
		val gateway = RecordingGateway { _, request ->
			flow {
				if (request.model == "primary") emit(ChatResult.Failed("bad gateway", 502))
				else emit(assembled("ok"))
			}
		}
		
		val results = ResilientChat(gateway)
			.execute(primary, listOf(sameProvider, otherProvider), messages = emptyList())
			.toList()
		
		assertEquals(listOf("primary", "other-provider"), gateway.requests.map { it.model })
		assertEquals(otherProvider.id, results.last().model)
	}
	
	@Test
	fun `CONTEXT_FALLBACK rule keeps only candidates with a larger context window`() = runTest {
		val primary = model(
			provider(rules = listOf(ErrorHandlingRule(400, RecoveryStrategy.CONTEXT_FALLBACK))),
			modelInfo("primary", contextWindow = 8_000)
		)
		val smaller = model(provider(), modelInfo("smaller", contextWindow = 4_000))
		val larger = model(provider(), modelInfo("larger", contextWindow = 16_000))
		val gateway = RecordingGateway { _, request ->
			flow {
				if (request.model == "primary") emit(ChatResult.Failed("too long", 400))
				else emit(assembled("ok"))
			}
		}
		
		val results = ResilientChat(gateway)
			.execute(primary, listOf(smaller, larger), messages = emptyList())
			.toList()
		
		assertEquals(listOf("primary", "larger"), gateway.requests.map { it.model })
		assertEquals(larger.id, results.last().model)
	}
	
	@Test
	fun `request timeout defaults to the configured settings when none is given`() = runTest {
		val gateway = RecordingGateway { _, _ -> flow { emit(assembled("ok")) } }
		
		ResilientChat(gateway).execute(model(), null, messages = emptyList()).toList()
		
		assertEquals(ChatTimeout(600.seconds, 10.seconds, 30.seconds), gateway.timeouts.single())
	}
	
	@Test
	fun `explicit timeout overrides the configured defaults`() = runTest {
		val custom = ChatTimeout(5.seconds, 1.seconds, 2.seconds)
		val gateway = RecordingGateway { _, _ -> flow { emit(assembled("ok")) } }
		
		ResilientChat(gateway).execute(model(), null, custom, messages = emptyList()).toList()
		
		assertEquals(custom, gateway.timeouts.single())
	}
	
	@Test
	fun `stream flag is cleared when the model does not support streaming`() = runTest {
		val gateway = RecordingGateway { _, _ -> flow { emit(assembled("ok")) } }
		
		ResilientChat(gateway)
			.execute(model(info = modelInfo(supportsStreaming = false)), null, messages = emptyList(), stream = true)
			.toList()
		
		assertEquals(false, gateway.requests.single().stream)
	}
	
	@Test
	fun `stream flag is preserved when the model supports streaming`() = runTest {
		val gateway = RecordingGateway { _, _ -> flow { emit(assembled("ok")) } }
		
		ResilientChat(gateway)
			.execute(model(info = modelInfo(supportsStreaming = true)), null, messages = emptyList(), stream = true)
			.toList()
		
		assertEquals(true, gateway.requests.single().stream)
	}
	
	@Test
	fun `user media parts not supported by the model are stripped from the request`() = runTest {
		val sha = Sha256(ByteArray(32) { it.toByte() })
		val user = ChatMessage.User(
			listOf(
				ContentPart.Text("hello"),
				ContentPart.Image("image/png", sha),
				ContentPart.ImageUrl("https://example.com/i.png".toUrl()),
				ContentPart.Audio("audio/mpeg", sha),
				ContentPart.AudioUrl("https://example.com/a.mp3".toUrl()),
				ContentPart.Video("video/mp4", sha),
				ContentPart.VideoUrl("https://example.com/v.mp4".toUrl()),
			)
		)
		val gateway = RecordingGateway { _, _ -> flow { emit(assembled("ok")) } }
		
		ResilientChat(gateway)
			.execute(model(info = modelInfo()), null, messages = listOf(user))
			.toList()
		
		val sent = gateway.requests.single().messages.single() as ChatMessage.User
		assertEquals(listOf(ContentPart.Text("hello")), sent.content)
	}
	
	@Test
	fun `user media parts supported by the model are kept untouched`() = runTest {
		val sha = Sha256(ByteArray(32) { it.toByte() })
		val parts = listOf(
			ContentPart.Text("hello"),
			ContentPart.Image("image/png", sha),
			ContentPart.Audio("audio/mpeg", sha),
			ContentPart.Video("video/mp4", sha),
		)
		val gateway = RecordingGateway { _, _ -> flow { emit(assembled("ok")) } }
		
		ResilientChat(gateway)
			.execute(
				model(info = modelInfo(supportsImage = true, supportsAudio = true, supportsVideo = true)),
				null,
				messages = listOf(ChatMessage.User(parts))
			)
			.toList()
		
		val sent = gateway.requests.single().messages.single() as ChatMessage.User
		assertEquals(parts, sent.content)
	}
	
	@Test
	fun `assistant reasoning is dropped when the model does not support reasoning`() = runTest {
		val assistant = ChatMessage.Assistant(content = null, reasoningContent = "thinking")
		val gateway = RecordingGateway { _, _ -> flow { emit(assembled("ok")) } }
		
		ResilientChat(gateway)
			.execute(
				model(info = modelInfo(supportsReasoning = false)),
				null,
				messages = listOf(assistant),
				reasoning = ReasoningEffort.MEDIUM
			)
			.toList()
		
		val request = gateway.requests.single()
		assertNull(request.reasoning)
		val sent = request.messages.single() as ChatMessage.Assistant
		assertNull(sent.reasoningContent)
		assertEquals("", sent.content)
	}
	
	@Test
	fun `assistant message with content and reasoning stays unchanged when thinking is enabled`() = runTest {
		val assistant = ChatMessage.Assistant(content = "answer", reasoningContent = "thinking")
		val gateway = RecordingGateway { _, _ -> flow { emit(assembled("ok")) } }
		
		ResilientChat(gateway)
			.execute(
				model(info = modelInfo(supportsReasoning = true)),
				null,
				messages = listOf(assistant),
				reasoning = ReasoningEffort.MEDIUM
			)
			.toList()
		
		assertEquals(assistant, gateway.requests.single().messages.single())
	}
	
	@Test
	fun `tool result messages pass through unchanged`() = runTest {
		val toolResult = ChatMessage.ToolResult(id = "call-1", content = listOf(ContentPart.Text("42")))
		val gateway = RecordingGateway { _, _ -> flow { emit(assembled("ok")) } }
		
		ResilientChat(gateway)
			.execute(model(), null, messages = listOf(toolResult))
			.toList()
		
		assertEquals(toolResult, gateway.requests.single().messages.single())
	}
	
	@Test
	fun `assistant reasoning is initialized to empty when thinking is enabled but absent`() = runTest {
		val assistant = ChatMessage.Assistant(content = null, reasoningContent = null)
		val gateway = RecordingGateway { _, _ -> flow { emit(assembled("ok")) } }
		
		ResilientChat(gateway)
			.execute(
				model(info = modelInfo(supportsReasoning = true)),
				null,
				messages = listOf(assistant),
				reasoning = ReasoningEffort.MEDIUM
			)
			.toList()
		
		val sent = gateway.requests.single().messages.single() as ChatMessage.Assistant
		assertEquals("", sent.reasoningContent)
		assertEquals("", sent.content)
	}
	
	@Test
	fun `assistant existing reasoning is preserved and null content becomes empty`() = runTest {
		val assistant = ChatMessage.Assistant(content = null, reasoningContent = "thinking")
		val gateway = RecordingGateway { _, _ -> flow { emit(assembled("ok")) } }
		
		ResilientChat(gateway)
			.execute(
				model(info = modelInfo(supportsReasoning = true)),
				null,
				messages = listOf(assistant),
				reasoning = ReasoningEffort.MEDIUM
			)
			.toList()
		
		val sent = gateway.requests.single().messages.single() as ChatMessage.Assistant
		assertEquals("thinking", sent.reasoningContent)
		assertEquals("", sent.content)
	}
	
	@Test
	fun `temperature and max tokens fall back to the model config`() = runTest {
		val gateway = RecordingGateway { _, _ -> flow { emit(assembled("ok")) } }
		
		ResilientChat(gateway)
			.execute(model(config = Config(0.7, 2048, null, null)), null, messages = emptyList())
			.toList()
		
		val request = gateway.requests.single()
		assertEquals(0.7, request.temperature)
		assertEquals(2048, request.maxTokens)
	}
	
	@Test
	fun `explicit temperature and max tokens override the model config`() = runTest {
		val gateway = RecordingGateway { _, _ -> flow { emit(assembled("ok")) } }
		
		ResilientChat(gateway)
			.execute(
				model(config = Config(0.7, 2048, null, null)),
				null,
				messages = emptyList(),
				maxTokens = 123,
				temperature = 1.5
			)
			.toList()
		
		val request = gateway.requests.single()
		assertEquals(1.5, request.temperature)
		assertEquals(123, request.maxTokens)
	}
	
	@Test
	fun `temperature and max tokens stay null without model config or explicit values`() = runTest {
		val gateway = RecordingGateway { _, _ -> flow { emit(assembled("ok")) } }
		
		ResilientChat(gateway).execute(model(config = null), null, messages = emptyList()).toList()
		
		val request = gateway.requests.single()
		assertNull(request.temperature)
		assertNull(request.maxTokens)
	}
}
