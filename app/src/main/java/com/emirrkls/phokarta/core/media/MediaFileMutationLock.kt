package com.emirrkls.phokarta.core.media

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes local media file creation/deletion with reconciliation so an in-flight
 * import cannot lose its `.part` or just-renamed file before Room records ownership.
 */
@Singleton
class MediaFileMutationLock @Inject constructor() {
    private val mutex = Mutex()

    suspend fun <T> withLock(action: suspend () -> T): T = mutex.withLock { action() }
}
