/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.matrix.android.internal.session.room.tombstone

import com.squareup.sqldelight.Query
import im.vector.matrix.android.api.session.events.model.EventType
import im.vector.matrix.android.api.session.events.model.toModel
import im.vector.matrix.android.api.session.room.model.VersioningState
import im.vector.matrix.android.api.session.room.model.tombstone.RoomTombstoneContent
import im.vector.matrix.android.internal.database.SqlLiveEntityObserver
import im.vector.matrix.android.internal.database.awaitTransaction
import im.vector.matrix.android.internal.database.mapper.ContentMapper
import im.vector.matrix.android.internal.util.MatrixCoroutineDispatchers
import im.vector.matrix.sqldelight.session.EventInsertNotification
import im.vector.matrix.sqldelight.session.SessionDatabase
import javax.inject.Inject

internal class RoomTombstoneEventLiveObserver @Inject constructor(sessionDatabase: SessionDatabase,
                                                                  private val coroutineDispatchers: MatrixCoroutineDispatchers )
    : SqlLiveEntityObserver<EventInsertNotification>(sessionDatabase) {

    override val query: Query<EventInsertNotification>
        get() = sessionDatabase.observerTriggerQueries.getAllEventInsertNotifications(listOf(EventType.STATE_ROOM_TOMBSTONE))

    override suspend fun handleChanges(results: List<EventInsertNotification>) {
        val notificationsIds = ArrayList<String>(results.size)
        sessionDatabase.awaitTransaction(coroutineDispatchers) {
            results.forEach {
                notificationsIds.add(it.event_id)
                val eventContent = sessionDatabase.eventQueries.selectContent(it.event_id).executeAsOneOrNull()?.content
                        ?: return@forEach
                val createRoomContent = ContentMapper.map(eventContent).toModel<RoomTombstoneContent>()
                if (createRoomContent?.replacementRoomId == null) return@forEach
                sessionDatabase.roomSummaryQueries.insertOrIgnore(it.room_id)
                val currentVersioningState = sessionDatabase.roomSummaryQueries.getVersioningState(it.room_id).executeAsOne()
                if (currentVersioningState == VersioningState.NONE.name) {
                    sessionDatabase.roomSummaryQueries.setVersioningState(VersioningState.UPGRADED_ROOM_NOT_JOINED.name, it.room_id)
                }
            }
            sessionDatabase.observerTriggerQueries.deleteEventInsertNotifications(notificationsIds)
        }
    }
}
