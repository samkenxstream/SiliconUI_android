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

package im.vector.matrix.android.internal.session.sync

import im.vector.matrix.android.R
import im.vector.matrix.android.api.pushrules.PushRuleService
import im.vector.matrix.android.api.pushrules.RuleScope
import im.vector.matrix.android.internal.crypto.DefaultCryptoService
import im.vector.matrix.android.internal.database.awaitTransaction
import im.vector.matrix.android.internal.session.DefaultInitialSyncProgressService
import im.vector.matrix.android.internal.session.notification.ProcessEventForPushTask
import im.vector.matrix.android.internal.session.reportSubtask
import im.vector.matrix.android.internal.session.sync.model.RoomsSyncResponse
import im.vector.matrix.android.internal.session.sync.model.SyncResponse
import im.vector.matrix.android.internal.util.MatrixCoroutineDispatchers
import im.vector.matrix.sqldelight.session.SessionDatabase
import timber.log.Timber
import javax.inject.Inject
import kotlin.system.measureTimeMillis

internal class SyncResponseHandler @Inject constructor(private val roomSyncHandler: RoomSyncHandler,
                                                       private val userAccountDataSyncHandler: UserAccountDataSyncHandler,
                                                       private val coroutineDispatchers: MatrixCoroutineDispatchers,
                                                       private val groupSyncHandler: GroupSyncHandler,
                                                       private val cryptoSyncHandler: CryptoSyncHandler,
                                                       private val cryptoService: DefaultCryptoService,
                                                       private val tokenStore: SyncTokenStore,
                                                       private val processEventForPushTask: ProcessEventForPushTask,
                                                       private val pushRuleService: PushRuleService,
                                                       private val initialSyncProgressService: DefaultInitialSyncProgressService,
                                                       private val sessionDatabase: SessionDatabase) {

    suspend fun handleResponse(syncResponse: SyncResponse, fromToken: String?) {
        val isInitialSync = fromToken == null
        Timber.v("Start handling sync, is InitialSync: $isInitialSync")
        val reporter = initialSyncProgressService.takeIf { isInitialSync }

        measureTimeMillis {
            if (!cryptoService.isStarted()) {
                Timber.v("Should start cryptoService")
                cryptoService.start(isInitialSync)
            }
        }.also {
            Timber.v("Finish handling start cryptoService in $it ms")
        }

        // Handle the to device events before the room ones
        // to ensure to decrypt them properly
        measureTimeMillis {
            Timber.v("Handle toDevice")
            reportSubtask(reporter, R.string.initial_sync_start_importing_account_crypto, 100, 0.1f) {
                if (syncResponse.toDevice != null) {
                    cryptoSyncHandler.handleToDevice(syncResponse.toDevice, reporter)
                }
            }
        }.also {
            Timber.v("Finish handling toDevice in $it ms")
        }

        // Start one big transaction
        sessionDatabase.awaitTransaction(coroutineDispatchers) {
            measureTimeMillis {
                Timber.v("Handle rooms")
                reportSubtask(reporter, R.string.initial_sync_start_importing_account_rooms, 100, 0.7f) {
                    if (syncResponse.rooms != null) {
                        roomSyncHandler.handle(syncResponse.rooms, reporter, isInitialSync)
                    }
                }
            }.also {
                Timber.v("Finish handling rooms in $it ms")
            }

            measureTimeMillis {
                reportSubtask(reporter, R.string.initial_sync_start_importing_account_data, 100, 0.1f) {
                    Timber.v("Handle accountData")
                    userAccountDataSyncHandler.handle(syncResponse.accountData)
                }
            }.also {
                Timber.v("Finish handling accountData in $it ms")
            }

            measureTimeMillis {
                reportSubtask(reporter, R.string.initial_sync_start_importing_account_groups, 100, 0.1f) {
                    Timber.v("Handle groups")
                    if (syncResponse.groups != null) {
                        groupSyncHandler.handle(syncResponse.groups, reporter)
                    }
                }
            }.also {
                Timber.v("Finish handling groups in $it ms")
            }

            tokenStore.saveToken(syncResponse.nextBatch)
        }


        // Everything else we need to do outside the transaction
        syncResponse.rooms?.also {
            checkPushRules(it, isInitialSync)
            userAccountDataSyncHandler.synchronizeWithServerIfNeeded(it.invite)
        }
        Timber.v("On sync completed")
        cryptoSyncHandler.onSyncCompleted(syncResponse)
    }

    private suspend fun checkPushRules(roomsSyncResponse: RoomsSyncResponse, isInitialSync: Boolean) {
        Timber.v("[PushRules] --> checkPushRules")
        if (isInitialSync) {
            Timber.v("[PushRules] <-- No push rule check on initial sync")
            return
        } // nothing on initial sync

        val rules = pushRuleService.getPushRules(RuleScope.GLOBAL).getAllRules()
        processEventForPushTask.execute(ProcessEventForPushTask.Params(roomsSyncResponse, rules))
        Timber.v("[PushRules] <-- Push task scheduled")
    }
}
