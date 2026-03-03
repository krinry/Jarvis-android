package dev.krinry.jarvis.agent

import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log

/**
 * ContactsLookup — Search contacts by name, get phone/email.
 *
 * Supports fuzzy matching: "Mom" matches "Mom ❤️", "Mommy", etc.
 * Used internally by call/SMS actions when AI gives a name instead of number.
 */
object ContactsLookup {

    private const val TAG = "ContactsLookup"

    data class ContactResult(
        val name: String,
        val phone: String?,
        val email: String?
    )

    /**
     * Execute contact search. Returns formatted result for AI.
     * AI: {"action":"find_contact","text":"Mom"}
     */
    fun execute(action: ActionExecutor.AgentAction, context: Context): String {
        val query = action.text
        if (query.isNullOrBlank()) return "❌ Contact name nahi diya"

        return try {
            val contacts = searchContacts(context, query)
            if (contacts.isEmpty()) {
                "❌ Contact nahi mila: \"$query\""
            } else {
                val sb = StringBuilder("✅ Contacts mille (${contacts.size}):\n")
                contacts.take(5).forEachIndexed { i, c ->
                    sb.append("${i + 1}. ${c.name}")
                    c.phone?.let { sb.append(" | 📞 $it") }
                    c.email?.let { sb.append(" | ✉ $it") }
                    sb.append("\n")
                }
                sb.toString().trim()
            }
        } catch (e: SecurityException) {
            "❌ Contacts permission nahi hai. Settings mein allow karo."
        } catch (e: Exception) {
            "❌ Contact error: ${e.message?.take(50)}"
        }
    }

    /**
     * Search contacts by name. Returns list of matches.
     * Used internally by DirectIntentExecutor for call/SMS name resolution.
     */
    fun searchContacts(context: Context, query: String): List<ContactResult> {
        val contacts = mutableListOf<ContactResult>()

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        val cursor: Cursor? = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$query%"),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )

        cursor?.use {
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val phoneIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (it.moveToNext() && contacts.size < 10) {
                val name = it.getString(nameIdx) ?: continue
                val phone = it.getString(phoneIdx)
                // Avoid duplicate entries for same name
                if (contacts.none { c -> c.name == name && c.phone == phone }) {
                    contacts.add(ContactResult(name, phone, null))
                }
            }
        }

        Log.d(TAG, "Search '$query' → ${contacts.size} results")
        return contacts
    }

    /**
     * Find first matching phone number for a name.
     * Quick lookup for call/SMS: returns phone number or null.
     */
    fun findPhoneByName(context: Context, name: String): String? {
        return searchContacts(context, name).firstOrNull()?.phone
    }
}
