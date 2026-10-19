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

package io.github.autotweaker.core.infrastructure.llm

import io.github.autotweaker.api.llm.LlmClient
import io.github.autotweaker.api.types.Url
import io.github.autotweaker.api.types.Url.Companion.toUrl
import io.github.autotweaker.api.types.exception.UnknownProviderTypeException
import io.github.autotweaker.api.types.llm.ChatMessage
import io.github.autotweaker.api.types.llm.ChatTimeout
import io.github.autotweaker.api.types.llm.LlmRequest
import io.github.autotweaker.api.types.llm.LlmResult
import io.github.autotweaker.core.test.TestServices
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

class LlmGatewayImplTest {
	
	private fun request(stream: Boolean = false) = LlmRequest(
		model = "fake-model",
		instructions = null,
		messages = emptyList(),
		reasoning = null,
		stream = stream,
		maxTokens = null,
		tools = null,
		temperature = null,
		jsonOutput = null
	)
	
	private fun timeout() = ChatTimeout(1.seconds, 2.seconds, 3.seconds)
	
	private val baseUrl = "https://override.test/v1".toUrl()
	
	@Test
	fun `send passes request arguments to the client selected by provider type`() = runTest {
		FakeLlmClient.reset()
		FakeLlmClient.chatBehavior = { flowOf(LlmResult.Assembled(ChatMessage.Assistant("hi"))) }
		val request = request()
		val timeout = timeout()
		
		val results = LlmGatewayImpl.send(request, "api-key", baseUrl, "fake", timeout).toList()
		
		val call = assertNotNull(FakeLlmClient.lastCall)
		assertSame(request, call.request)
		assertEquals("api-key", call.apiKey)
		assertEquals(baseUrl, call.baseUrl)
		assertSame(timeout, call.timeout)
		assertEquals("hi", assertIs<LlmResult.Assembled>(results.single()).message.content)
	}
	
	@Test
	fun `send forwards every result emitted by the client`() = runTest {
		FakeLlmClient.reset()
		val chunk = LlmResult.Chunk(content = "hello", reasoningContent = null, toolCalls = null)
		val assembled = LlmResult.Assembled(ChatMessage.Assistant("hello"))
		FakeLlmClient.chatBehavior = { flowOf(chunk, assembled) }
		
		val results = LlmGatewayImpl.send(request(stream = true), "api-key", baseUrl, "fake", timeout()).toList()
		
		assertEquals(listOf(chunk, assembled), results)
	}
	
	@Test
	fun `send converts a client flow failure into a Failed result`() = runTest {
		FakeLlmClient.reset()
		val boom = IllegalStateException("boom")
		FakeLlmClient.chatBehavior = { flow { throw boom } }
		
		val results = LlmGatewayImpl.send(request(), "api-key", baseUrl, "fake", timeout()).toList()
		
		val failed = assertIs<LlmResult.Failed>(results.single())
		assertEquals("IllegalStateException: boom", failed.message)
		assertNull(failed.statusCode)
		assertSame(boom, failed.exception)
	}
	
	@Test
	fun `send throws UnknownProviderTypeException for an unknown provider`() {
		FakeLlmClient.reset()
		
		assertFailsWith<UnknownProviderTypeException> {
			LlmGatewayImpl.send(request(), "api-key", baseUrl, "missing-provider", timeout())
		}
		assertNull(FakeLlmClient.lastCall)
	}
	
	companion object {
		init {
			TestServices.init()
		}
	}
}

/**
 * 通过 `META-INF/services` 注册的假客户端，用于覆盖网关的路由与错误映射。
 */
class FakeLlmClient : LlmClient {
	data class Call(
		val request: LlmRequest,
		val apiKey: String,
		val baseUrl: Url?,
		val timeout: ChatTimeout?,
	)
	
	override val providerInfo = LlmClient.ProviderInfo(
		name = "fake",
		baseUrl = "https://fake.test/v1".toUrl(),
		models = emptyList(),
		errorHandlingRules = emptyList(),
	)
	
	override fun chat(
		request: LlmRequest,
		apiKey: String,
		baseUrl: Url?,
		timeout: ChatTimeout?,
	): Flow<LlmResult> {
		lastCall = Call(request, apiKey, baseUrl, timeout)
		return chatBehavior()
	}
	
	override suspend fun shutdown() {}
	
	companion object {
		@Volatile
		var lastCall: Call? = null
		
		@Volatile
		var chatBehavior: () -> Flow<LlmResult> = { emptyFlow() }
		
		fun reset() {
			lastCall = null
			chatBehavior = { emptyFlow() }
		}
	}
}
