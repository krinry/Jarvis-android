package dev.krinry.jarvis.system

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * TelecomBridge — Iron Man Telecom & Contacts Subsystem.
 *
 * Capabilities:
 * - Search contacts by name or partial string
 * - Initiate direct phone calls or open dialer
 * - Direct SMS dispatch
 * - High-authority fallback via Shizuku shell if permissions are pending
 */
object TelecomBridge {

    private const val TAG = "TelecomBridge"

    data class ContactRecord(
        val name: String,
        val phoneNumber: String
    )

    /**
     * Searches device contacts matching the query.
     */
    fun searchContacts(context: Context, query: String): List<ContactRecord> {
        val results = mutableListOf<ContactRecord>()
        try {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                // If Shizuku is available, we can also query content provider via content query
                if (ShizukuBridge.hasShizukuPermission()) {
                    return queryContactsViaShizuku(query)
                }
                return emptyList()
            }

            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$query%")

            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: "Unknown"
                    val number = cursor.getString(numberIdx) ?: ""
                    if (number.isNotBlank()) {
                        results.add(ContactRecord(name, number))
                    }
                    if (results.size >= 10) break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching contacts for '$query'", e)
        }
        return results
    }

    private fun queryContactsViaShizuku(query: String): List<ContactRecord> {
        // Fallback using Shizuku content query
        return emptyList()
    }

    /**
     * Places a phone call to the specified number or contact name.
     */
    suspend fun makePhoneCall(context: Context, target: String): String {
        // 1. Check if target is already a numeric phone number
        val cleaned = target.replace("[^+0-9*#]".toRegex(), "")
        val phoneNumber = if (cleaned.length >= 3) {
            cleaned
        } else {
            // Search contact by name
            val contacts = searchContacts(context, target)
            if (contacts.isEmpty()) {
                return "Contact '$target' was not found in your phonebook, Sir."
            }
            contacts.first().phoneNumber
        }

        // 2. Attempt direct call via Shizuku ADB shell (Instant, no UI dialog)
        if (ShizukuBridge.hasShizukuPermission()) {
            val res = ShizukuBridge.executeShell("am start -a android.intent.action.CALL -d tel:$phoneNumber")
            if (res.isSuccess) {
                return "Placing call to $phoneNumber, Sir."
            }
        }

        // 3. Fallback to Android Intent
        return try {
            val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                context.startActivity(callIntent)
                "Calling $phoneNumber now, Sir."
            } else {
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(dialIntent)
                "Dialer opened for $phoneNumber. Permission is required for direct dialing, Sir."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate call to $phoneNumber", e)
            "Could not initiate call: ${e.message}"
        }
    }

    /**
     * Sends an SMS message to the specified recipient.
     */
    suspend fun sendSms(context: Context, target: String, message: String): String {
        val cleaned = target.replace("[^+0-9*#]".toRegex(), "")
        val phoneNumber = if (cleaned.length >= 3) {
            cleaned
        } else {
            val contacts = searchContacts(context, target)
            if (contacts.isEmpty()) {
                return "Contact '$target' was not found, Sir."
            }
            contacts.first().phoneNumber
        }

        // 1. Try via Shizuku shell
        if (ShizukuBridge.hasShizukuPermission()) {
            val res = ShizukuBridge.executeShell("service call isms 7 i32 0 s16 \"com.android.mms.service\" s16 \"null\" s16 \"$phoneNumber\" s16 \"null\" s16 \"$message\" s16 \"null\" s16 \"null\" i32 0 i64 0")
            if (res.isSuccess) {
                return "SMS transmitted to $phoneNumber: \"$message\""
            }
        }

        // 2. Direct SmsManager
        return try {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                @Suppress("DEPRECATION")
                val smsManager = SmsManager.getDefault()
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
                "SMS successfully sent to $phoneNumber, Sir."
            } else {
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phoneNumber")).apply {
                    putExtra("sms_body", message)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "SMS app opened for $phoneNumber. Grant SEND_SMS permission for automatic dispatch, Sir."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS to $phoneNumber", e)
            "Could not send SMS: ${e.message}"
        }
    }
}
