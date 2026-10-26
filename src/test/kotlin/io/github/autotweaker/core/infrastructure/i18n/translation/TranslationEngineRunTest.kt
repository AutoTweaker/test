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

package io.github.autotweaker.core.infrastructure.i18n.translation

import io.github.autotweaker.api.base.en
import io.github.autotweaker.api.base.zh
import io.github.autotweaker.api.store.JsonStore
import io.github.autotweaker.api.types.Localizations
import io.github.autotweaker.api.types.Url.Companion.toUrl
import io.github.autotweaker.api.types.llm.ChatMessage
import io.github.autotweaker.api.types.llm.ChatRequest
import io.github.autotweaker.api.types.llm.ChatResult
import io.github.autotweaker.api.types.llm.LlmResult
import io.github.autotweaker.api.types.llm.ModelData.ModelInfo
import io.github.autotweaker.api.types.llm.ReasoningEffort
import io.github.autotweaker.api.types.message.ContentPart
import io.github.autotweaker.core.application.impl.ChatService
import io.github.autotweaker.core.domain.agent.RuntimeModel
import io.github.autotweaker.core.domain.agent.RuntimeProvider
import io.github.autotweaker.core.domain.port.ModelResolver
import io.github.autotweaker.core.infrastructure.i18n.I18nRegistry
import io.github.autotweaker.core.infrastructure.i18n.I18nServiceImpl
import io.github.autotweaker.core.test.TestServices
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.*

class TranslationEngineRunTest {
	companion object {
		init {
			TestServices.init()
		}
	}
	
	private val json = Json { ignoreUnknownKeys = true; isLenient = true }
	private val registry = mutableMapOf<String, Localizations>()
	private val chat = mockk<ChatService>()
	private val modelRepo = mockk<ModelResolver>()
	private val modelId = UUID.randomUUID()
	private val target: Locale = Locale.CHINESE
	private lateinit var engine: TranslationEngine
	
	private fun runtimeModel() = RuntimeModel(
		id = modelId,
		provider = RuntimeProvider(
			UUID.randomUUID(),
			"provider",
			"https://api.example.com/v1".toUrl(),
			"sk-key",
			emptyList(),
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
	
	@BeforeTest
	fun setUp() {
		registry.clear()
		requests.clear()
		mockkObject(I18nRegistry)
		every { I18nRegistry.get(any()) } answers { registry[firstArg<String>()] }
		every { I18nRegistry.getAll() } returns registry
		every { TestServices.jsonStore.namespace(any()) } answers {
			mockk<JsonStore>().also {
				every { it.get() } answers { null }
				every { it.set(any()) } answers { }
			}
		}
		coEvery { modelRepo.resolve(modelId) } returns runtimeModel()
		engine = TranslationEngine(chat)
	}
	
	@AfterTest
	fun tearDown() {
		unmockkObject(I18nRegistry)
	}
	
	private fun answer(body: String?, usage: LlmResult = LlmResult.Assembled(ChatMessage.Assistant(body), null)) =
		flowOf(ChatResult(usage, modelId))
	
	private fun userText(request: ChatRequest): String =
		(request.messages.single() as ChatMessage.User).content
			.filterIsInstance<ContentPart.Text>()
			.joinToString("") { it.content }
	
	private fun batchOf(request: ChatRequest): Map<String, List<String>> {
		val text = userText(request)
		val jsonText = text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)
		return json.decodeFromString(jsonText)
	}
	
	private fun persisted(key: String): String? =
		I18nServiceImpl.getAllEntries()[key]?.entries
			?.firstOrNull { it.key.language == target.language }?.value
	
	private val requests = ConcurrentLinkedQueue<ChatRequest>()
	
	private fun captureRequests(response: (Map<String, List<String>>) -> String?) {
		every { chat.chat(any()) } answers {
			val request = firstArg<ChatRequest>()
			requests.add(request)
			answer(response(batchOf(request)))
		}
	}
	
	@Test
	fun `run sends one json output request with prompts filled in`() = runBlocking {
		registry["run.request.key"] = mapOf(en to "Hello")
		captureRequests { """{"run.request.key":"你好"}""" }
		
		engine.run(modelId, target, modelRepo)
		
		verify(exactly = 1) { chat.chat(any()) }
		val request = requests.singleOrNull() ?: error("request was not captured")
		assertEquals(modelId, request.model)
		assertEquals(false, request.stream)
		assertEquals(true, request.jsonOutput)
		assertEquals(ReasoningEffort.MEDIUM, request.reasoning)
		assertEquals(mapOf("run.request.key" to listOf("Hello")), batchOf(request))
		
		val instructions = assertNotNull(request.instructions)
		assertFalse(instructions.contains("{{target_language}}"))
		assertTrue(instructions.contains(target.displayName))
		val text = userText(request)
		assertFalse(text.contains("{{target_language}}"))
		assertFalse(text.contains("{{content_to_translate}}"))
		assertTrue(text.contains(target.displayName))
	}
	
	@Test
	fun `run persists translation wrapped in surrounding prose`() = runBlocking {
		registry["run.prose.key"] = mapOf(en to "Hello")
		captureRequests { """Here is the result: {"run.prose.key":"你好"} - done""" }
		
		engine.run(modelId, target, modelRepo)
		
		assertEquals("你好", persisted("run.prose.key"))
	}
	
	@Test
	fun `run only requests entries that miss the target language`() = runBlocking {
		registry["run.skip.done"] = mapOf(en to "Done", zh to "完成")
		registry["run.skip.todo"] = mapOf(en to "Todo")
		captureRequests { """{"run.skip.todo":"待办"}""" }
		
		engine.run(modelId, target, modelRepo)
		
		val request = requests.singleOrNull() ?: error("request was not captured")
		assertEquals(mapOf("run.skip.todo" to listOf("Todo")), batchOf(request))
		assertEquals("完成", persisted("run.skip.done"))
		assertEquals("待办", persisted("run.skip.todo"))
	}
	
	@Test
	fun `run does not call chat when every entry has the target language`() = runBlocking {
		registry["run.complete.key"] = mapOf(en to "Hello", zh to "你好")
		
		engine.run(modelId, target, modelRepo)
		
		verify(exactly = 0) { chat.chat(any()) }
	}
	
	@Test
	fun `run keeps translations that preserve placeholders and drops the rest`() = runBlocking {
		registry["run.placeholder.good"] = mapOf(en to "Hello %s")
		registry["run.placeholder.bad"] = mapOf(en to "Hello %s")
		captureRequests { """{"run.placeholder.good":"你好 %s","run.placeholder.bad":"你好"}""" }
		
		engine.run(modelId, target, modelRepo)
		
		assertEquals("你好 %s", persisted("run.placeholder.good"))
		assertNull(persisted("run.placeholder.bad"))
	}
	
	@Test
	fun `run drops blank translation`() = runBlocking {
		registry["run.blank.key"] = mapOf(en to "Hello")
		captureRequests { """{"run.blank.key":"  "}""" }
		
		engine.run(modelId, target, modelRepo)
		
		assertNull(persisted("run.blank.key"))
	}
	
	@Test
	fun `run ignores response without json object`() = runBlocking {
		registry["run.nojson.key"] = mapOf(en to "Hello")
		captureRequests { "sorry, I cannot translate that" }
		
		engine.run(modelId, target, modelRepo)
		
		assertNull(persisted("run.nojson.key"))
	}
	
	@Test
	fun `run ignores response with unparsable json`() = runBlocking {
		registry["run.badjson.key"] = mapOf(en to "Hello")
		captureRequests { """{"run.badjson.key" "Hello"}""" }
		
		engine.run(modelId, target, modelRepo)
		
		assertNull(persisted("run.badjson.key"))
	}
	
	@Test
	fun `run ignores assembled answer without text`() = runBlocking {
		registry["run.nocontent.key"] = mapOf(en to "Hello")
		captureRequests { null }
		
		engine.run(modelId, target, modelRepo)
		
		assertNull(persisted("run.nocontent.key"))
	}
	
	@Test
	fun `run ignores failed chat result`() = runBlocking {
		registry["run.failed.key"] = mapOf(en to "Hello")
		every { chat.chat(any()) } returns answer(null, LlmResult.Failed("boom", 500))
		
		engine.run(modelId, target, modelRepo)
		
		assertNull(persisted("run.failed.key"))
	}
	
	@Test
	fun `run finishes when chat flow throws`() = runBlocking {
		registry["run.throw.key"] = mapOf(en to "Hello")
		every { chat.chat(any()) } returns flow { throw IllegalStateException("boom") }
		
		engine.run(modelId, target, modelRepo)
		
		assertNull(persisted("run.throw.key"))
	}
	
	@Test
	fun `run splits entries into batches of forty keys`() = runBlocking {
		val keys = (1..41).map { "run.batch.key$it" }
		keys.forEach { registry[it] = mapOf(en to "source $it") }
		captureRequests { batch ->
			Json.encodeToString(
				MapSerializer(String.serializer(), String.serializer()),
				batch.keys.associateWith { "T:$it" },
			)
		}
		
		engine.run(modelId, target, modelRepo)
		
		assertEquals(listOf(1, 40), requests.map { batchOf(it).size }.sorted())
		assertEquals(keys.toSet(), requests.flatMap { batchOf(it).keys }.toSet())
		assertEquals(keys.associateWith { "T:$it" }, keys.associateWith { persisted(it) })
	}
}
