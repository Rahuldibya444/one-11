package com.example.telecom

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.provider.CallLog
import android.util.Log

data class PhoneXCallLog(
    val id: String,
    val number: String,
    val name: String?,
    val type: Int, // Incoming: 1, Outgoing: 2, Missed: 3, Rejected: 5
    val date: Long,
    val duration: Long
)

object CallLogRepository {
    private const val TAG = "PhoneX_CallLogRepo"

    fun fetchCallLogs(context: Context): List<PhoneXCallLog> {
        val callLogs = mutableListOf<PhoneXCallLog>()
        val hasPermission = context.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            Log.w(TAG, "Missing READ_CALL_LOG permission, loading simulated records.")
            return getSimulatedLogs()
        }

        try {
            val projection = arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            )

            val cursor: Cursor? = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )

            cursor?.use {
                val idIdx = it.getColumnIndex(CallLog.Calls._ID)
                val numberIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val durationIdx = it.getColumnIndex(CallLog.Calls.DURATION)

                while (it.moveToNext()) {
                    val id = if (idIdx >= 0) it.getString(idIdx) else ""
                    val number = if (numberIdx >= 0) it.getString(numberIdx) ?: "" else ""
                    val name = if (nameIdx >= 0) it.getString(nameIdx) else null
                    val type = if (typeIdx >= 0) it.getInt(typeIdx) else 1
                    val date = if (dateIdx >= 0) it.getLong(dateIdx) else 0L
                    val duration = if (durationIdx >= 0) it.getLong(durationIdx) else 0L

                    if (number.isNotEmpty()) {
                        callLogs.add(PhoneXCallLog(id, number, name, type, date, duration))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query Call Logs: ${e.message}")
        }

        return if (callLogs.isEmpty()) {
            getSimulatedLogs()
        } else {
            callLogs
        }
    }

    fun addCallLog(context: Context, number: String, type: Int, duration: Long): Boolean {
        val hasPermission = context.checkSelfPermission(android.Manifest.permission.WRITE_CALL_LOG) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            Log.w(TAG, "Missing WRITE_CALL_LOG permission")
            return false
        }
        try {
            val values = ContentValues().apply {
                put(CallLog.Calls.NUMBER, number)
                put(CallLog.Calls.TYPE, type)
                put(CallLog.Calls.DATE, System.currentTimeMillis())
                put(CallLog.Calls.DURATION, duration)
                put(CallLog.Calls.NEW, 1)
            }
            context.contentResolver.insert(CallLog.Calls.CONTENT_URI, values)
            Log.d(TAG, "Successfully added CallLog entry: $number")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert CallLog: ${e.message}")
            return false
        }
    }

    fun deleteCallLog(context: Context, logId: String): Boolean {
        val hasPermission = context.checkSelfPermission(android.Manifest.permission.WRITE_CALL_LOG) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            Log.w(TAG, "Missing WRITE_CALL_LOG permission")
            return false
        }
        try {
            val deleted = context.contentResolver.delete(
                CallLog.Calls.CONTENT_URI,
                "${CallLog.Calls._ID} = ?",
                arrayOf(logId)
            )
            Log.d(TAG, "Deleted $deleted CallLog entries")
            return deleted > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete CallLog: ${e.message}")
            return false
        }
    }

    fun clearAllLogs(context: Context): Boolean {
        val hasPermission = context.checkSelfPermission(android.Manifest.permission.WRITE_CALL_LOG) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return false
        try {
            context.contentResolver.delete(CallLog.Calls.CONTENT_URI, null, null)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear call logs: ${e.message}")
            return false
        }
    }

    private fun getSimulatedLogs(): List<PhoneXCallLog> {
        val now = System.currentTimeMillis()
        return listOf(
            PhoneXCallLog("101", "+15550244", "Caelum Vane", 1, now - 600000, 42L), // incoming
            PhoneXCallLog("102", "+15550192", "Aurora Vance", 2, now - 3600000, 128L), // outgoing
            PhoneXCallLog("103", "+15550599", "Nova Sinclair", 3, now - 18000000, 0L), // missed
            PhoneXCallLog("104", "+15550371", "Elysia Thorne", 1, now - 86400000, 15L), // incoming
            PhoneXCallLog("105", "+15550612", "Zephyr Hawke", 2, now - 172800000, 310L) // outgoing
        )
    }
}
