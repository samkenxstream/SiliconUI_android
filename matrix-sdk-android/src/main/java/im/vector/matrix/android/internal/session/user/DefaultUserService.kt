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

package im.vector.matrix.android.internal.session.user

import androidx.paging.PagedList
import im.vector.matrix.android.api.MatrixCallback
import im.vector.matrix.android.api.session.user.UserService
import im.vector.matrix.android.api.session.user.model.User
import im.vector.matrix.android.api.util.Cancelable
import im.vector.matrix.android.api.util.Optional
import im.vector.matrix.android.internal.session.user.accountdata.UpdateIgnoredUserIdsTask
import im.vector.matrix.android.internal.session.user.model.SearchUserTask
import im.vector.matrix.android.internal.task.TaskExecutor
import im.vector.matrix.android.internal.task.configureWith
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

internal class DefaultUserService @Inject constructor(private val userDataSource: UserDataSource,
                                                      private val searchUserTask: SearchUserTask,
                                                      private val updateIgnoredUserIdsTask: UpdateIgnoredUserIdsTask,
                                                      private val taskExecutor: TaskExecutor) : UserService {

    override fun getUser(userId: String): User? {
        return userDataSource.getUser(userId)
    }

    override fun getUserLive(userId: String): Flow<Optional<User>> {
        return userDataSource.getUserLive(userId)
    }

    override fun getUsersLive(): Flow<List<User>> {
        return userDataSource.getUsersLive()
    }

    override fun getPagedUsersLive(filter: String?): Flow<PagedList<User>> {
        return userDataSource.getPagedUsersLive(filter)
    }

    override fun searchUsersDirectory(search: String,
                                      limit: Int,
                                      excludedUserIds: Set<String>,
                                      callback: MatrixCallback<List<User>>): Cancelable {
        val params = SearchUserTask.Params(limit, search, excludedUserIds)
        return searchUserTask
                .configureWith(params) {
                    this.callback = callback
                }
                .executeBy(taskExecutor)
    }

    override fun getIgnoredUsersLive(): Flow<List<User>> {
        return userDataSource.getIgnoredUsersLive()

    }

    override fun ignoreUserIds(userIds: List<String>, callback: MatrixCallback<Unit>): Cancelable {
        val params = UpdateIgnoredUserIdsTask.Params(userIdsToIgnore = userIds.toList())
        return updateIgnoredUserIdsTask
                .configureWith(params) {
                    this.callback = callback
                }
                .executeBy(taskExecutor)
    }

    override fun unIgnoreUserIds(userIds: List<String>, callback: MatrixCallback<Unit>): Cancelable {
        val params = UpdateIgnoredUserIdsTask.Params(userIdsToUnIgnore = userIds.toList())
        return updateIgnoredUserIdsTask
                .configureWith(params) {
                    this.callback = callback
                }
                .executeBy(taskExecutor)
    }
}
