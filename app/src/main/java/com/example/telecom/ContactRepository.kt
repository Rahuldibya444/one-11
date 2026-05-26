package com.example.telecom

import android.content.ContentProviderOperation
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log

data class PhoneXContact(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val photoUri: String? = null
)

object ContactRepository {
    private const val TAG = "PhoneX_ContactRepo"

    fun fetchAllContacts(context: Context): List<PhoneXContact> {
        val contactsList = mutableListOf<PhoneXContact>()
        val hasPermission = context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            Log.w(TAG, "Missing READ_CONTACTS permission, loading starter contacts.")
            return getStarterContacts()
        }

        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI
            )

            val cursor: Cursor? = context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )

            cursor?.use {
                val idIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val photoIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI)

                while (it.moveToNext()) {
                    val id = if (idIndex >= 0) it.getString(idIndex) else ""
                    val name = if (nameIndex >= 0) it.getString(nameIndex) ?: "No Name" else "No Name"
                    val rawNum = if (numberIndex >= 0) it.getString(numberIndex) ?: "" else ""
                    val number = rawNum.replace("[\\s\\-\\(\\)]".toRegex(), "")
                    val photoUri = if (photoIndex >= 0) it.getString(photoIndex) else null

                    if (number.isNotEmpty()) {
                        // Prevent duplicates
                        if (contactsList.none { c -> c.phoneNumber == number }) {
                            contactsList.add(PhoneXContact(id, name, number, photoUri))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Contacts query error: ${e.message}")
        }

        return if (contactsList.isEmpty()) {
            getStarterContacts()
        } else {
            contactsList
        }
    }

    fun addNewContact(context: Context, name: String, phone: String): Boolean {
        val hasPermission = context.checkSelfPermission(android.Manifest.permission.WRITE_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            Log.w(TAG, "Missing WRITE_CONTACTS permission")
            return false
        }
        try {
            val ops = ArrayList<ContentProviderOperation>()

            ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build())

            // Add Display Name
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build())

            // Add Mobile Phone Connection
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build())

            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            Log.d(TAG, "Successfully inserted contact: $name")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert contact: ${e.message}")
            return false
        }
    }

    private fun getStarterContacts(): List<PhoneXContact> {
        return listOf(
            PhoneXContact("1", "Aurora Vance", "+15550192", null),
            PhoneXContact("2", "Caelum Vane", "+15550244", null),
            PhoneXContact("3", "Elysia Thorne", "+15550371", null),
            PhoneXContact("4", "Gideon Cross", "+15550488", null),
            PhoneXContact("5", "Nova Sinclair", "+15550599", null),
            PhoneXContact("6", "Zephyr Hawke", "+15550612", null)
        )
    }
}
