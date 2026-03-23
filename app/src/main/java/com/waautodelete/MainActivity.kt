package com.waautodelete

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.waautodelete.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: ContactsAdapter

    private val scanResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WaDeleteAccessibilityService.ACTION_SCAN_RESULT) return
            val text = intent.getStringExtra(WaDeleteAccessibilityService.EXTRA_RESULT_TEXT) ?: return
            binding.tvLastScanResult.text = text
            binding.tvLastScanResult.isVisible = true
            binding.btnForceScan.isEnabled = true
            binding.btnForceScan.text = "Force scan now"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupClickListeners()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatusBanner()
        binding.switchEnabled.isChecked = viewModel.isServiceEnabled()
        registerReceiver(
            scanResultReceiver,
            IntentFilter(WaDeleteAccessibilityService.ACTION_SCAN_RESULT),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(scanResultReceiver) } catch (_: Exception) {}
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        adapter = ContactsAdapter { name -> showRemoveConfirmDialog(name) }
        binding.rvContacts.layoutManager = LinearLayoutManager(this)
        binding.rvContacts.adapter = adapter
    }

    private fun setupClickListeners() {
        binding.btnAdd.setOnClickListener {
            val name = binding.etContactName.text.toString().trim()
            viewModel.addContact(name)
            binding.etContactName.text?.clear()
        }

        binding.btnOpenAccessibility.setOnClickListener { openAccessibilitySettings() }

        binding.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setServiceEnabled(isChecked)
            Toast.makeText(this,
                if (isChecked) "Auto-delete enabled" else "Auto-delete paused",
                Toast.LENGTH_SHORT).show()
        }

        binding.btnForceScan.setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "Enable the accessibility service first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.btnForceScan.isEnabled = false
            binding.btnForceScan.text = "Scanning…"
            binding.tvLastScanResult.text = "Scanning…"
            binding.tvLastScanResult.isVisible = true

            sendBroadcast(Intent(WaDeleteAccessibilityService.ACTION_FORCE_SCAN).apply {
                setPackage(packageName)
            })

            // Re-enable button after 5 s in case broadcast result never arrives
            binding.btnForceScan.postDelayed({
                binding.btnForceScan.isEnabled = true
                binding.btnForceScan.text = "Force scan now"
            }, 5000)
        }
    }

    private fun observeViewModel() {
        viewModel.contacts.observe(this) { contacts ->
            adapter.submitList(contacts.toList())
            binding.tvEmptyState.isVisible = contacts.isEmpty()
            binding.rvContacts.isVisible = contacts.isNotEmpty()
        }
        viewModel.event.observe(this) { msg ->
            if (!msg.isNullOrBlank()) {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                viewModel.consumeEvent()
            }
        }
    }

    // ── Service status ────────────────────────────────────────────────────────

    private fun updateServiceStatusBanner() {
        val active = isAccessibilityServiceEnabled()
        binding.bannerServiceStatus.setBackgroundColor(
            if (active) getColor(R.color.status_active) else getColor(R.color.status_inactive)
        )
        binding.tvServiceStatus.text = if (active)
            "✓  Accessibility Service is ACTIVE"
        else
            "⚠  Accessibility Service is DISABLED — tap to enable"
        binding.bannerServiceStatus.setOnClickListener { if (!active) openAccessibilitySettings() }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any {
                it.resolveInfo.serviceInfo.packageName == packageName &&
                it.resolveInfo.serviceInfo.name == WaDeleteAccessibilityService::class.java.name
            }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(this, "Find 'WA Auto Delete' and enable it", Toast.LENGTH_LONG).show()
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    private fun showRemoveConfirmDialog(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Remove contact")
            .setMessage("Stop auto-deleting messages from \"$name\"?")
            .setPositiveButton("Remove") { _, _ -> viewModel.removeContact(name) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
