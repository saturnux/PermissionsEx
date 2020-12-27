/*
 * PermissionsEx
 * Copyright (C) zml and PermissionsEx contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ca.stellardrift.permissionsex.sponge

import ca.stellardrift.permissionsex.subject.ImmutableSubjectData
import ca.stellardrift.permissionsex.subject.Segment
import ca.stellardrift.permissionsex.subject.SubjectRef
import ca.stellardrift.permissionsex.util.Change
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import org.spongepowered.api.service.context.Context
import org.spongepowered.api.service.permission.Subject
import org.spongepowered.api.service.permission.SubjectData
import org.spongepowered.api.service.permission.SubjectReference
import org.spongepowered.api.util.Tristate

/**
 * Wrapper around ImmutableSubjectData that writes to backend each change
 */
class PEXSubjectData internal constructor(
    private val data: SubjectRef.ToData<*>,
    private val subject: PEXSubject
) : SubjectData {
    private val parentsCache: ConcurrentMap<ContextSet, List<SubjectReference>> = ConcurrentHashMap()
    private val service: PermissionsExService = subject.collection.service

    init {
        data.onUpdate { clearCache() }
    }

    /**
     * Provide a boolean representation of success for the Sponge returns.
     *
     * @return Whether or not the old data object is different from the new data object
     */
    private fun CompletableFuture<Change<ImmutableSubjectData?>>.boolSuccess(): CompletableFuture<Boolean> {
        return thenApply { it.old() != it.current() }
    }

    private fun clearCache() {
        synchronized(parentsCache) { parentsCache.clear() }
    }

    override fun getAllOptions(): Map<Set<Context>, Map<String, String>> {
        return data.get().mapSegmentValues(Segment::options).keysToSponge()
    }

    override fun getOptions(contexts: Set<Context>): Map<String, String> {
        return data.get().segment(contexts.toPex(service.manager)).options()
    }

    override fun setOption(contexts: Set<Context>, key: String, value: String?): CompletableFuture<Boolean> {
        return data.update(contexts.toPex(service.manager)) { it.withOption(key, value) }.boolSuccess()
    }

    override fun clearOptions(contexts: Set<Context>): CompletableFuture<Boolean> {
        return data.update(contexts.toPex(service.manager)) { it.withoutOptions() }.boolSuccess()
    }

    override fun clearOptions(): CompletableFuture<Boolean> {
        return data.update { it.withSegments { _, segment -> segment.withoutOptions() } }.boolSuccess()
    }

    override fun getSubject(): Subject {
        return this.subject
    }

    override fun isTransient(): Boolean {
        return this === this.subject.transientSubjectData
    }

    override fun getAllPermissions(): Map<Set<Context>, Map<String, Boolean>> {
        return data.get().mapSegmentValues {
            it.permissions().mapValues { (_, v) -> v > 0 }
        }.keysToSponge()
    }

    override fun getPermissions(contexts: Set<Context>): Map<String, Boolean> {
        return data.get().segment(contexts.toPex(service.manager)).permissions()
            .mapValues { (_, v) -> v > 0 }
    }

    override fun setPermission(contexts: Set<Context>, permission: String, state: Tristate): CompletableFuture<Boolean> {
        val value = when (state) {
            Tristate.TRUE -> 1
            Tristate.FALSE -> -1
            Tristate.UNDEFINED -> 0
            else -> throw IllegalStateException("Unknown tristate provided $state")
        }
        return data.update(contexts.toPex(service.manager)) { it.withPermission(permission, value) }.thenApply { true }
    }

    override fun clearPermissions(): CompletableFuture<Boolean> {
        return data.update { it.withSegments { _, segment -> segment.withoutPermissions() } }.boolSuccess()
    }

    override fun clearPermissions(contexts: Set<Context>): CompletableFuture<Boolean> {
        return data.update(contexts.toPex(service.manager)) { it.withoutPermissions() }.boolSuccess()
    }

    override fun getAllParents(): Map<Set<Context>, List<SubjectReference>> {
        synchronized(parentsCache) {
            data.get().activeContexts().forEach { getParentsInternal(it) }
            return parentsCache.keysToSponge()
        }
    }

    override fun getParents(contexts: Set<Context>): List<SubjectReference> {
        return getParentsInternal(contexts.toPex(service.manager))
    }

    private fun getParentsInternal(contexts: ContextSet): List<SubjectReference> {
        val existing = parentsCache[contexts]
        if (existing != null) {
            return existing
        }
        val parents: List<SubjectReference>
        synchronized(parentsCache) {
            val rawParents = data.get().segment(contexts).parents()
            parents = rawParents?.map { it.asSponge(this.service) } ?: emptyList()
            val existingParents = parentsCache.putIfAbsent(contexts, parents)
            if (existingParents != null) {
                return existingParents
            }
        }
        return parents
    }

    override fun addParent(contexts: Set<Context>, subject: SubjectReference): CompletableFuture<Boolean> {
        val ref = subject.asPex(service) // validate subject reference
        return data.update(contexts.toPex(service.manager)) { it.plusParent(ref) }.boolSuccess()
    }

    override fun removeParent(set: Set<Context>, subject: SubjectReference): CompletableFuture<Boolean> {
        return data.update(set.toPex(service.manager)) { it.minusParent(subject.asPex(service)) }.boolSuccess()
    }

    override fun clearParents(): CompletableFuture<Boolean> {
        return data.update { it.withSegments { _, segment -> segment.withoutParents() } }.boolSuccess()
    }

    override fun clearParents(set: Set<Context>): CompletableFuture<Boolean> {
        return data.update(set.toPex(service.manager)) { it.withoutParents() }.boolSuccess()
    }
}

private fun <T> Map<ContextSet, T>.keysToSponge(): Map<Set<Context>, T> {
    return mapKeys { (key, _) -> key.toSponge() }
}