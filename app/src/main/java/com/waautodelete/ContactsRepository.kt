package com.waautodelete

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Persists the list of blocked contact names using SharedPreferences + Gson.
 */
object ContactsRepository {

    private const val PREFS_NAME = "wa_auto_delete_prefs"
    private const val KEY_CONTACTS = "blocked_contacts"
    private const val KEY_SERVICE_ENABLED = "service_enabled"

    private val gson = Gson()

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns the current list of blocked contact names (trimmed, lower-cased for comparison). */
    fun getContacts(context: Context): List<String> {
        val json = prefs(context).getString(KEY_CONTACTS, null) ?: return emptyList()
        val type = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    /** Saves the full list of blocked contact names. */
    fun saveContacts(context: Context, contacts: List<String>) {
        prefs(context).edit()
            .putString(KEY_CONTACTS, gson.toJson(contacts))
            .apply()
    }

    /** Adds a single contact if not already present. Returns true if added. */
    fun addContact(context: Context, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val current = getContacts(context).toMutableList()
        if (current.any { it.equals(trimmed, ignoreCase = true) }) return false
        current.add(trimmed)
        saveContacts(context, current)
        return true
    }

    /** Removes a contact by exact name match (case-insensitive). Returns true if removed. */
    fun removeContact(context: Context, name: String): Boolean {
        val current = getContacts(context).toMutableList()
        val removed = current.removeAll { it.equals(name, ignoreCase = true) }
        if (removed) saveContacts(context, current)
        return removed
    }

    fun isServiceEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SERVICE_ENABLED, true)

    fun setServiceEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()
    }
}
