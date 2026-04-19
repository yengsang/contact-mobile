package com.memberreward.contact

import android.content.ContentResolver
import android.provider.ContactsContract

data class PhoneContact(
    val name: String,
    val phone: String,
    val email: String?
)

class ContactsRepository(
    private val contentResolver: ContentResolver
) {

    fun readContacts(): List<PhoneContact> {
        val emailByContactId = readEmailMap()
        val contacts = linkedMapOf<String, PhoneContact>()

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val phoneIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (cursor.moveToNext()) {
                val contactId = cursor.getLong(idIndex)
                val name = cursor.getString(nameIndex)?.trim().orEmpty()
                val phone = cursor.getString(phoneIndex)?.trim().orEmpty()

                if (name.isBlank() || phone.isBlank()) {
                    continue
                }

                val normalizedPhone = phone.filterNot { it.isWhitespace() }
                val dedupeKey = "$name|$normalizedPhone"

                contacts.putIfAbsent(
                    dedupeKey,
                    PhoneContact(
                        name = name,
                        phone = normalizedPhone,
                        email = emailByContactId[contactId]
                    )
                )
            }
        }

        return contacts.values.toList()
    }

    private fun readEmailMap(): Map<Long, String> {
        val emails = mutableMapOf<Long, String>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Email.CONTACT_ID,
            ContactsContract.CommonDataKinds.Email.ADDRESS
        )

        contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
            val emailIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS)

            while (cursor.moveToNext()) {
                val contactId = cursor.getLong(idIndex)
                val email = cursor.getString(emailIndex)?.trim().orEmpty()
                if (email.isNotBlank() && contactId !in emails) {
                    emails[contactId] = email
                }
            }
        }

        return emails
    }
}
