package com.emirrkls.phokarta.core.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.emirrkls.phokarta.core.database.entity.MediaUploadState
import com.emirrkls.phokarta.core.database.entity.VisitDraftPhotoEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface MediaImportResult {
    data class Success(val photo: VisitDraftPhotoEntity) : MediaImportResult
    data object MaxCount : MediaImportResult
    data object UnsupportedType : MediaImportResult
    data object TooLarge : MediaImportResult
    data object Unreadable : MediaImportResult
}

@Singleton
class VisitMediaStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun import(
        ownerUserId: String,
        placeId: String,
        position: Int,
        uri: Uri,
    ): MediaImportResult {
        val resolver = context.contentResolver
        val contentType = resolver.getType(uri)?.lowercase() ?: return MediaImportResult.UnsupportedType
        if (contentType !in SUPPORTED_TYPES) return MediaImportResult.UnsupportedType
        val declaredSize = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
        if (declaredSize != null && declaredSize > MAX_BYTES) return MediaImportResult.TooLarge

        val clientMediaId = UUID.randomUUID().toString()
        val extension = when (contentType) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            else -> "webp"
        }
        val relativePath = "visit-media/${safeOwner(ownerUserId)}/$clientMediaId.$extension"
        val target = resolveOwned(ownerUserId, relativePath) ?: return MediaImportResult.Unreadable
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.part")
        return try {
            val input = resolver.openInputStream(uri) ?: return MediaImportResult.Unreadable
            var total = 0L
            input.use { source ->
                FileOutputStream(temp).use { sink ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) {
                            temp.delete()
                            return MediaImportResult.TooLarge
                        }
                        sink.write(buffer, 0, read)
                    }
                    sink.fd.sync()
                }
            }
            if (total == 0L || !temp.renameTo(target)) {
                temp.delete()
                return MediaImportResult.Unreadable
            }
            afterDurableFileCreated?.invoke()
            if (contentType == "image/jpeg") stripGps(target)
            if (target.length() !in 1..MAX_BYTES) {
                target.delete()
                return MediaImportResult.TooLarge
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, bounds)
            MediaImportResult.Success(
                VisitDraftPhotoEntity(
                    ownerUserId = ownerUserId,
                    placeId = placeId,
                    position = position,
                    clientMediaId = clientMediaId,
                    localRelativePath = relativePath,
                    contentType = contentType,
                    byteSize = target.length(),
                    width = bounds.outWidth.takeIf { it > 0 },
                    height = bounds.outHeight.takeIf { it > 0 },
                    remoteMediaId = null,
                    uploadState = MediaUploadState.LOCAL_ONLY,
                    failureCategory = null,
                ),
            )
        } catch (_: SecurityException) {
            temp.delete()
            target.delete()
            MediaImportResult.Unreadable
        } catch (_: Exception) {
            temp.delete()
            target.delete()
            MediaImportResult.Unreadable
        }
    }

    fun resolveOwned(ownerUserId: String, relativePath: String): File? {
        if (relativePath.startsWith("/") || relativePath.contains("..") || '\\' in relativePath) return null
        val ownerRoot = File(context.filesDir, "visit-media/${safeOwner(ownerUserId)}").canonicalFile
        val file = File(context.filesDir, relativePath).canonicalFile
        return file.takeIf { it.path.startsWith(ownerRoot.path + File.separator) }
    }

    fun deleteOwned(ownerUserId: String, relativePath: String?) {
        relativePath?.let { resolveOwned(ownerUserId, it)?.delete() }
    }

    private fun stripGps(file: File) {
        val exif = ExifInterface(file)
        GPS_TAGS.forEach { exif.setAttribute(it, null) }
        exif.saveAttributes()
    }

    private fun safeOwner(ownerUserId: String): String =
        ownerUserId.replace(Regex("[^A-Za-z0-9._-]"), "_")

    companion object {
        const val MAX_PHOTOS = 20
        const val MAX_BYTES = 15L * 1024 * 1024
        val SUPPORTED_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        @androidx.annotation.VisibleForTesting
        @Volatile
        internal var afterDurableFileCreated: (() -> Unit)? = null
        private val GPS_TAGS = listOf(
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_AREA_INFORMATION,
        )
    }
}
