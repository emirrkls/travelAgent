package com.emirrkls.phokarta.core.media

import android.content.Context
import com.emirrkls.phokarta.core.database.dao.PendingMutationDao
import com.emirrkls.phokarta.core.database.dao.VisitDraftDao
import com.emirrkls.phokarta.core.time.EpochClock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class MediaReconciliationResult(
    val removedFiles: Int,
    val retainedFiles: Int,
)

@Singleton
class MediaFileReconciler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val drafts: VisitDraftDao,
    private val mutations: PendingMutationDao,
    private val mediaStore: VisitMediaStore,
    private val fileMutationLock: MediaFileMutationLock,
    private val clock: EpochClock,
) {
    suspend fun reconcile(): MediaReconciliationResult = fileMutationLock.withLock {
        val root = File(context.filesDir, MEDIA_DIRECTORY).canonicalFile
        if (!root.exists()) return@withLock MediaReconciliationResult(0, 0)

        val referenced = buildSet {
            drafts.getAllPhotos().forEach { photo ->
                mediaStore.resolveOwned(photo.ownerUserId, photo.localRelativePath)
                    ?.canonicalPath
                    ?.let(::add)
            }
            mutations.getAllVisitPhotos().forEach { photo ->
                val path = photo.localRelativePath ?: return@forEach
                mediaStore.resolveOwned(photo.ownerUserId, path)
                    ?.canonicalPath
                    ?.let(::add)
            }
        }
        var removed = 0
        var retained = 0
        root.walkBottomUp().forEach { candidate ->
            if (candidate == root) return@forEach
            val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return@forEach
            if (!canonical.path.startsWith(root.path + File.separator)) return@forEach
            if (candidate.isFile) {
                when {
                    canonical.path in referenced -> retained++
                    isWithinGracePeriod(candidate) -> retained++
                    candidate.delete() -> removed++
                }
            } else if (candidate.isDirectory && candidate.list()?.isEmpty() == true) {
                candidate.delete()
            }
        }
        MediaReconciliationResult(removed, retained)
    }

    private fun isWithinGracePeriod(file: File): Boolean {
        val ageMs = clock.nowMillis() - file.lastModified()
        return ageMs < STALE_FILE_GRACE_MS
    }

    companion object {
        const val MEDIA_DIRECTORY = "visit-media"
        /** Young `.part` and just-renamed files are kept until Room can record ownership. */
        const val STALE_FILE_GRACE_MS = 5L * 60L * 1000L
    }
}
