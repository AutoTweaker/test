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

package io.github.autotweaker.core.infrastructure.config

import io.github.autotweaker.api.store.JsonStore
import io.github.autotweaker.api.types.Url
import io.github.autotweaker.api.types.Url.Companion.toUrl
import io.github.autotweaker.api.types.exception.notfound.SecretNotFoundException
import io.github.autotweaker.api.types.llm.ModelData
import io.github.autotweaker.api.types.llm.ProviderData
import io.github.autotweaker.core.domain.port.SecretStore
import io.github.autotweaker.core.infrastructure.persist.json.ModelStore
import io.github.autotweaker.core.infrastructure.persist.json.ProviderStore
import io.github.autotweaker.core.test.TestServices
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.JsonElement
import java.util.*
import kotlin.reflect.KClass

internal class InMemorySecretStore : SecretStore {
	val secrets = mutableMapOf<UUID, String>()
	
	override suspend fun set(secret: String, id: UUID) {
		secrets[id] = secret
	}
	
	override suspend fun get(id: UUID): String = secrets[id] ?: throw SecretNotFoundException(id)
	
	override suspend fun list(): List<UUID> = secrets.keys.toList()
	
	override suspend fun remove(id: UUID): Boolean = secrets.remove(id) != null
	
	override fun requireUnlocked() {}
}

internal val testModelInfo = ModelData.ModelInfo(
	modelId = "test-model",
	contextWindow = 128000,
	maxOutputTokens = 4096,
	supportsStreaming = true,
	supportsToolCalls = true,
	supportsReasoning = true,
	supportsJsonOutput = true,
)

internal fun testProviderData(
	id: UUID = UUID.randomUUID(),
	apiKey: UUID,
	displayName: String = "provider-$id",
	providerType: String = "deepseek",
	baseUrl: Url = "https://api.test.com/v1".toUrl(),
) = ProviderData(
	id = id,
	displayName = displayName,
	providerType = providerType,
	apiKey = apiKey,
	baseUrl = baseUrl,
	errorHandlingRules = emptyList(),
)

internal fun testModelData(
	id: UUID = UUID.randomUUID(),
	providerId: UUID,
	displayName: String = "model-$id",
) = ModelData(
	id = id,
	displayName = displayName,
	modelInfo = testModelInfo,
	providerId = providerId,
)

internal suspend fun clearConfigStores() {
	ProviderStore.getAll().keys.forEach { ProviderStore.delete(it) }
	ModelStore.getAll().keys.forEach { ModelStore.delete(it) }
}

internal fun stubJsonStores(entries: MutableMap<KClass<*>, JsonElement?>) {
	every { TestServices.jsonStore.namespace(any()) } answers {
		val kClass = firstArg<KClass<*>>()
		mockk<JsonStore>().also { store ->
			every { store.get() } answers { entries[kClass] }
			every { store.set(any()) } answers { entries[kClass] = firstArg<JsonElement>() }
		}
	}
}
