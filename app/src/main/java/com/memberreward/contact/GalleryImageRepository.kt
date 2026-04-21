package com.memberreward.contact

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore

data class GalleryImage(
    val uri: Uri,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long
)

class GalleryImageRepository(
    private val contentResolver: ContentResolver
) {
    companion object {
        private const val MAX_GALLERY_IMAGES = 100
        private const val MAX_IMAGE_SIZE_BYTES = 5L * 1024L * 1024L
    }

    fun readAllImages(): List<GalleryImage> {
        val images = mutableListOf<GalleryImage>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE
        )

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

            while (cursor.moveToNext() && images.size < MAX_GALLERY_IMAGES) {
                val id = cursor.getLong(idIndex)
                val name = cursor.getString(nameIndex)?.trim().orEmpty()
                val mime = cursor.getString(mimeIndex)?.trim().orEmpty()
                val reportedSize = cursor.getLong(sizeIndex)
                val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                val resolvedSize = if (reportedSize > 0) {
                    reportedSize
                } else {
                    contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                        descriptor.length
                    } ?: -1L
                }

                if (resolvedSize <= 0L || resolvedSize > MAX_IMAGE_SIZE_BYTES) {
                    continue
                }

                val fallbackName = "image_$id.jpg"
                images += GalleryImage(
                    uri = uri,
                    fileName = if (name.isBlank()) fallbackName else name,
                    mimeType = if (mime.isBlank()) "image/jpeg" else mime,
                    sizeBytes = resolvedSize
                )
            }
        }

        return images
    }
}
