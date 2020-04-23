/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package im.vector.matrix.android.internal.session.room.prune

import im.vector.matrix.android.api.session.events.model.EventType
import im.vector.matrix.android.api.session.events.model.LocalEcho
import im.vector.matrix.android.api.session.events.model.UnsignedData
import im.vector.matrix.android.internal.database.awaitTransaction
import im.vector.matrix.android.internal.database.mapper.ContentMapper
import im.vector.matrix.android.internal.database.mapper.EventMapper
import im.vector.matrix.android.internal.database.mapper.asDomain
import im.vector.matrix.android.internal.di.MoshiProvider
import im.vector.matrix.android.internal.task.Task
import im.vector.matrix.android.internal.util.MatrixCoroutineDispatchers
import im.vector.matrix.sqldelight.session.EventInsertNotification
import im.vector.matrix.sqldelight.session.SessionDatabase
import timber.log.Timber
import javax.inject.Inject

internal interface PruneEventTask : Task<PruneEventTask.Params, Unit> {

    data class Params(
            val redactionInsertNotifications: List<EventInsertNotification>
    )
}

internal class DefaultPruneEventTask @Inject constructor(private val sessionDatabase: SessionDatabase,
                                                         private val coroutineDispatchers: MatrixCoroutineDispatchers ) : PruneEventTask {

    override suspend fun execute(params: PruneEventTask.Params) {
        sessionDatabase.awaitTransaction(coroutineDispatchers) {
            params.redactionInsertNotifications.forEach { event ->
                pruneEvent(event)
            }
        }
    }

    private fun pruneEvent(redactionInsertNotification: EventInsertNotification) {
        val redactionEvent = sessionDatabase.eventQueries.select(redactionInsertNotification.event_id).executeAsOneOrNull()
        // Check that we know this event
        val redacts = redactionEvent?.redacts
        if (redacts.isNullOrBlank()) {
            return
        }
        val isLocalEcho = LocalEcho.isLocalEchoId(redactionEvent.event_id)
        Timber.v("Redact event for ${redactionEvent.redacts} localEcho=$isLocalEcho")

        val eventToPrune = sessionDatabase.eventQueries.select(redacts).executeAsOneOrNull()
                ?: return

        val typeToPrune = eventToPrune.type
        val stateKey = eventToPrune.state_key
        val allowedKeys = computeAllowedKeys(typeToPrune)
        if (allowedKeys.isNotEmpty()) {
            val prunedContent = ContentMapper.map(eventToPrune.content)?.filterKeys { key -> allowedKeys.contains(key) }
            sessionDatabase.eventQueries.updateContent(ContentMapper.map(prunedContent), eventToPrune.event_id)
        } else {
            when (typeToPrune) {
                EventType.ENCRYPTED,
                EventType.MESSAGE -> {
                    Timber.d("REDACTION for message ${eventToPrune.event_id}")
                    val unsignedData = EventMapper.map(eventToPrune).unsignedData
                            ?: UnsignedData(null, null)

                    // was this event a m.replace
//                    val contentModel = ContentMapper.map(eventToPrune.content)?.toModel<MessageContent>()
//                    if (RelationType.REPLACE == contentModel?.relatesTo?.type && contentModel.relatesTo?.eventId != null) {
//                        eventRelationsAggregationUpdater.handleRedactionOfReplace(eventToPrune, contentModel.relatesTo!!.eventId!!, realm)
//                    }

                    val modified = unsignedData.copy(redactedEvent = redactionEvent.asDomain())
                    val unsignedDataJson = MoshiProvider.providesMoshi().adapter(UnsignedData::class.java).toJson(modified)
                    val prunedContent = ContentMapper.map(emptyMap())
                    sessionDatabase.eventQueries.pruneEvent(
                            content = prunedContent,
                            unsignedData = unsignedDataJson,
                            eventId = eventToPrune.event_id
                    )
                }
//                EventType.REACTION -> {
//                    eventRelationsAggregationUpdater.handleReactionRedact(eventToPrune, realm, userId)
//                }
            }
        }
        /*
        if (typeToPrune == EventType.STATE_ROOM_MEMBER && stateKey != null) {
            TimelineEventEntity.findWithSenderMembershipEvent(realm, eventToPrune.eventId).forEach {
                it.senderName = null
                it.isUniqueDisplayName = false
                it.senderAvatar = null
            }
        }

         */
    }

    private fun computeAllowedKeys(type: String): List<String> {
        // Add filtered content, allowed keys in content depends on the event type
        return when (type) {
            EventType.STATE_ROOM_MEMBER -> listOf("membership")
            EventType.STATE_ROOM_CREATE -> listOf("creator")
            EventType.STATE_ROOM_JOIN_RULES -> listOf("join_rule")
            EventType.STATE_ROOM_POWER_LEVELS -> listOf("users",
                    "users_default",
                    "events",
                    "events_default",
                    "state_default",
                    "ban",
                    "kick",
                    "redact",
                    "invite")
            EventType.STATE_ROOM_ALIASES -> listOf("aliases")
            EventType.STATE_ROOM_CANONICAL_ALIAS -> listOf("alias")
            EventType.FEEDBACK -> listOf("type", "target_event_id")
            else -> emptyList()
        }
    }
}
