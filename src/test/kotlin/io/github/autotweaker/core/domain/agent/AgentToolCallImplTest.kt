package io.github.autotweaker.core.domain.agent

import io.github.autotweaker.api.tool.Tool
import io.github.autotweaker.api.types.agent.ToolCallStatus
import io.github.autotweaker.api.types.message.AgentMessage
import io.github.autotweaker.api.types.tool.ToolResultStatus
import io.github.autotweaker.api.types.tool.UiBlock
import io.github.autotweaker.core.test.TestServices
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Instant

class AgentToolCallImplTest {
	companion object {
		init {
			TestServices.init()
		}
	}

	private val instant = Instant.fromEpochMilliseconds(0)

	private fun call() = AgentMessage.Tool.Call(
		id = UUID.randomUUID(),
		timestamp = instant,
		origin = UUID.randomUUID(),
		callId = "call-1",
		callName = "bash-run",
		arguments = "{}",
		reason = null,
		validatedToolName = null,
		validatedArgs = null,
		resolvedRequest = null,
		presentation = null,
	)

	private fun result() = AgentMessage.Tool.Result(
		id = UUID.randomUUID(),
		timestamp = instant,
		origin = UUID.randomUUID(),
		callId = "call-1",
		content = "done",
		data = null,
		presentation = emptyList(),
		status = ToolResultStatus.SUCCESS,
	)

	private fun ready() = Tool.ResolveResult.Ready(
		result = JsonPrimitive("{}"),
		request = { listOf(UiBlock.Text("request")) },
		executing = { listOf(UiBlock.Text("executing")) },
		cancelled = { listOf(UiBlock.Text("cancelled")) },
		rejected = { listOf(UiBlock.Text("rejected")) },
		failed = { listOf(UiBlock.Text("failed")) },
		timeout = { listOf(UiBlock.Text("timeout")) },
	)

	private fun pending() = AgentToolCallImpl(call(), ready(), null)

	@Test
	fun `pending when resolved and no result`() {
		val toolCall = pending()
		assertEquals(ToolCallStatus.PENDING, toolCall.status.value)
		assertNull(toolCall.result)
	}

	@Test
	fun `finished when result and no resolved`() {
		val res = result()
		val toolCall = AgentToolCallImpl(call(), null, res)
		assertEquals(ToolCallStatus.FINISHED, toolCall.status.value)
		assertSame(res, toolCall.result)
	}

	@Test
	fun `waiting then calling then finish walks the states`() {
		val toolCall = pending()
		toolCall.waiting()
		assertEquals(ToolCallStatus.WAITING, toolCall.status.value)
		toolCall.calling()
		assertEquals(ToolCallStatus.CALLING, toolCall.status.value)
		val res = result()
		toolCall.finish(res)
		assertEquals(ToolCallStatus.FINISHED, toolCall.status.value)
		assertSame(res, toolCall.result)
	}

	@Test
	fun `both arguments missing starts as pending`() {
		val toolCall = AgentToolCallImpl(call(), null, null)
		assertEquals(ToolCallStatus.PENDING, toolCall.status.value)
	}

	@Test
	fun `calling can be invoked directly from pending`() {
		val toolCall = pending()
		toolCall.calling()
		assertEquals(ToolCallStatus.CALLING, toolCall.status.value)
	}

	@Test
	fun `finish clears the stored result`() {
		val toolCall = pending()
		toolCall.waiting()
		toolCall.calling()
		toolCall.finish(result())
		assertNull(toolCall.result)
	}
}
