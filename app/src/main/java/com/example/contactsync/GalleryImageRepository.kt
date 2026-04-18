package com.example.contactsync

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore

data class GalleryImage(
    val uri: Uri,
    val fileName: String,
    val mimeType: String
)

class GalleryImageRepository(
    private val contentResolver: ContentResolver
) {
    fun readAllImages(): List<GalleryImage> {
        val images = mutableListOf<GalleryImage>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE
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

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val name = cursor.getString(nameIndex)?.trim().orEmpty()
                val mime = cursor.getString(mimeIndex)?.trim().orEmpty()
                val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())

                val fallbackName = "image_$id.jpg"
                images += GalleryImage(
                    uri = uri,
                    fileName = if (name.isBlank()) fallbackName else name,
                    mimeType = if (mime.isBlank()) "image/jpeg" else mime
                )
            }
        }

        return images
    }
}
