package com.waautodelete

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _contacts = MutableLiveData<List<String>>()
    val contacts: LiveData<List<String>> get() = _contacts

    private val _event = MutableLiveData<String?>()
    val event: LiveData<String?> get() = _event

    init {
        loadContacts()
    }

    private fun loadContacts() {
        _contacts.value = ContactsRepository.getContacts(getApplication())
    }

    fun addContact(name: String) {
        val added = ContactsRepository.addContact(getApplication(), name)
        if (added) {
            loadContacts()
        } else {
            _event.value = if (name.isBlank()) "Name cannot be empty" else "Contact already in list"
        }
    }

    fun removeContact(name: String) {
        ContactsRepository.removeContact(getApplication(), name)
        loadContacts()
    }

    fun consumeEvent() {
        _event.value = null
    }

    fun isServiceEnabled(): Boolean =
        ContactsRepository.isServiceEnabled(getApplication())

    fun setServiceEnabled(enabled: Boolean) {
        ContactsRepository.setServiceEnabled(getApplication(), enabled)
    }
}
