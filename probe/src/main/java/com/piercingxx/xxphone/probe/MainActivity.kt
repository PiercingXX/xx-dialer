package com.piercingxx.xxphone.probe

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.piercingxx.xxphone.probe.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pendingRole: String? = null
    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val roleRequestLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            evaluateRoleRequest(result.resultCode)
        }

    private val runtimePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            grants.forEach { (permission, granted) ->
                ProbeLog.log("perms", "event" to "request_result", "permission" to permission, "granted" to granted)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProbeLog.init(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRequestDialer.setOnClickListener { requestRole(RoleManager.ROLE_DIALER) }
        binding.btnRequestScreening.setOnClickListener { requestRole(RoleManager.ROLE_CALL_SCREENING) }
        binding.btnAuditRoles.setOnClickListener { auditEnvironment(trigger = "button") }
        binding.btnPostRing.setOnClickListener { RingPoster.post(this, source = "manual_button") }
        binding.btnPhoneLookup.setOnClickListener { runPhoneLookupProbe(runLabel = "initial") }
        binding.btnScopeRecheck.setOnClickListener { runPhoneLookupProbe(runLabel = "scope_recheck") }
        binding.btnShareLog.setOnClickListener { shareLog() }

        ProbeLog.attachSink { snapshot -> renderLog(snapshot) }
        logSessionHeader()
        requestMissingRuntimePermissions()
        auditEnvironment(trigger = "startup")
        RingPoster.consumeTap(intent, this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        RingPoster.consumeTap(intent, this)
    }

    override fun onDestroy() {
        probeScope.cancel()
        ProbeLog.detachSink()
        super.onDestroy()
    }

    private fun renderLog(snapshot: String) {
        binding.tvLog.text = snapshot
        binding.scroll.post { binding.scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun logSessionHeader() {
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull()
        ProbeLog.log(
            "session",
            "event" to "session_start",
            "package" to packageName,
            "version" to version,
            "target_sdk" to 35
        )
        ProbeLog.event(
            "device",
            "fingerprint=${Build.FINGERPRINT} release=${Build.VERSION.RELEASE} sdk_int=${Build.VERSION.SDK_INT} incremental=${Build.VERSION.INCREMENTAL}"
        )
        ProbeLog.event(
            "device",
            "manufacturer=${Build.MANUFACTURER} product=${Build.PRODUCT} model=${Build.MODEL}"
        )
        ProbeLog.event(
            "device",
            "grapheneos_updater_version=${grapheneOsVersion()} feature_telephony=${packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)}"
        )
    }

    private fun grapheneOsVersion(): String =
        runCatching { packageManager.getPackageInfo(GRAPHENEOS_UPDATER_PACKAGE, 0).versionName }
            .getOrNull() ?: "unreadable"

    private fun requestMissingRuntimePermissions() {
        val missing = RUNTIME_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return
        runtimePermissionLauncher.launch(missing.toTypedArray())
    }

    private fun logPermissionStates() {
        for (permission in RUNTIME_PERMISSIONS) {
            val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            ProbeLog.log("perms", "event" to "state", "permission" to permission, "granted" to granted)
        }
    }

    private fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun requestRole(role: String) {
        val roleManager = getSystemService(RoleManager::class.java)
        if (roleManager == null || !roleManager.isRoleAvailable(role)) {
            ProbeLog.log("roles", "role" to role, "request" to "skipped", "role_available" to false)
            return
        }
        if (roleManager.isRoleHeld(role)) {
            ProbeLog.log("roles", "role" to role, "request" to "skipped", "already_held" to true)
            return
        }
        pendingRole = role
        ProbeLog.log("roles", "role" to role, "request" to "system_dialog_launched")
        roleRequestLauncher.launch(roleManager.createRequestRoleIntent(role))
    }

    private fun evaluateRoleRequest(resultCode: Int) {
        val role = pendingRole ?: return
        pendingRole = null
        val heldAfter = getSystemService(RoleManager::class.java)?.isRoleHeld(role) == true
        val resultCodeName = if (resultCode == RESULT_OK) "RESULT_OK" else "RESULT_CANCELED"
        ProbeLog.log(
            "roles",
            "role" to role,
            "request_result" to resultCodeName,
            "held_after_request" to heldAfter
        )
        if (heldAfter) {
            ProbeLog.event("roles", "outcome=granted role=$role")
            binding.tvRoleHint.text = ""
            auditEnvironment(trigger = "role_granted")
        } else {
            ProbeLog.event("roles", "outcome=not_held role=$role signature=request_ok_but_not_held_or_user_cancelled")
            ProbeLog.event("roles", "hint=App_Info -> three_dot_menu -> Allow_restricted_settings then re-request the role")
            binding.tvRoleHint.text = getString(R.string.restricted_settings_hint)
        }
    }

    private fun auditEnvironment(trigger: String) {
        val roleManager = getSystemService(RoleManager::class.java)
        val telecomManager = getSystemService(android.telecom.TelecomManager::class.java)
        ProbeLog.event("audit", "event=begin trigger=$trigger")
        for (role in listOf(RoleManager.ROLE_DIALER, RoleManager.ROLE_CALL_SCREENING)) {
            val held = roleManager?.isRoleHeld(role) == true
            val available = roleManager?.isRoleAvailable(role) == true
            ProbeLog.log(
                "audit",
                "role" to role,
                "held" to held,
                "available" to available,
                "holders" to roleHolders(roleManager, role)
            )
        }
        val notifications = NotificationManagerCompat.from(this)
        ProbeLog.log(
            "audit",
            "default_dialer_package" to (telecomManager?.defaultDialerPackage ?: "unreadable"),
            "can_use_full_screen_intent" to notifications.canUseFullScreenIntent(),
            "notifications_enabled" to notifications.areNotificationsEnabled(),
            "default_ringtone" to runCatching { Settings.System.DEFAULT_RINGTONE_URI.toString() }.getOrDefault("unreadable")
        )
        logPermissionStates()
        RingPoster.describe(this)
    }

    private fun roleHolders(roleManager: RoleManager?, role: String): String {
        if (roleManager == null) return "unreadable:no_role_manager"
        return runCatching {
            val method = roleManager.javaClass.getMethod("getRoleHolders", String::class.java)
            @Suppress("UNCHECKED_CAST")
            (method.invoke(roleManager, role) as? List<String>)?.joinToString("|") ?: "none"
        }.getOrElse { "unreadable:${it.javaClass.simpleName}" }
    }

    private fun runPhoneLookupProbe(runLabel: String) {
        if (!hasContactsPermission()) {
            ProbeLog.log("phone_lookup", "run" to runLabel, "aborted" to true, "reason" to "READ_CONTACTS not granted")
            requestMissingRuntimePermissions()
            return
        }
        probeScope.launch(Dispatchers.IO) {
            phoneLookupRows(runLabel)
            dataTableAccountColumns(runLabel)
        }
    }

    private fun phoneLookupRows(runLabel: String) {
        ProbeLog.log(
            "phone_lookup",
            "run" to runLabel,
            "event" to "start",
            "target_contact" to getString(R.string.test_contact_name),
            "number_forms" to TEST_NUMBER_FORMS.joinToString(",")
        )
        for (form in TEST_NUMBER_FORMS) {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(form))
            try {
                contentResolver.query(uri, PHONE_LOOKUP_PROJECTION, null, null, null)?.use { cursor ->
                    ProbeLog.log("phone_lookup", "run" to runLabel, "filter_form" to form, "rows" to cursor.count)
                    while (cursor.moveToNext()) {
                        val nonNullColumns = PHONE_LOOKUP_PROJECTION
                            .filterIndexed { index, _ -> !cursor.isNull(index) }
                            .map { it.substringAfterLast('.') }
                        ProbeLog.event(
                            "phone_lookup",
                            "run=$runLabel form=$form row=${cursor.position}" +
                                " display_name=${valueAt(cursor, ContactsContract.PhoneLookup.DISPLAY_NAME)}" +
                                " normalized_number=${valueAt(cursor, ContactsContract.PhoneLookup.NORMALIZED_NUMBER)}" +
                                " photo_uri=${valueAt(cursor, ContactsContract.PhoneLookup.PHOTO_URI)}" +
                                " columns_non_null=[${nonNullColumns.joinToString(",")}]"
                        )
                    }
                } ?: ProbeLog.log("phone_lookup", "run" to runLabel, "filter_form" to form, "result" to "null_cursor")
            } catch (t: Throwable) {
                ProbeLog.log(
                    "phone_lookup",
                    "run" to runLabel,
                    "filter_form" to form,
                    "error" to "${t.javaClass.simpleName}: ${t.message}"
                )
            }
        }
        ProbeLog.event(
            "phone_lookup",
            "run=$runLabel instruction=toggle GrapheneOS Contact Scopes in App Info then press re-run and compare rows"
        )
    }

    private fun dataTableAccountColumns(runLabel: String) {
        val selection = "${ContactsContract.Data.DISPLAY_NAME} LIKE ?"
        try {
            contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                DATA_PROJECTION,
                selection,
                arrayOf("%${getString(R.string.test_contact_name)}%"),
                null
            )?.use { cursor ->
                var accountNameRows = 0
                while (cursor.moveToNext()) {
                    if (!cursor.isNull(cursor.getColumnIndex(COLUMN_ACCOUNT_NAME))) accountNameRows++
                }
                ProbeLog.log(
                    "contacts_data",
                    "run" to runLabel,
                    "rows" to cursor.count,
                    "account_name_non_null_rows" to accountNameRows,
                    "note" to "targetSdk35 today; provider tightening at targetSdk37 removes account columns from Data"
                )
            } ?: ProbeLog.log("contacts_data", "run" to runLabel, "result" to "null_cursor")
        } catch (t: Throwable) {
            ProbeLog.log(
                "contacts_data",
                "run" to runLabel,
                "error" to "${t.javaClass.simpleName}: ${t.message}"
            )
        }
    }

    private fun valueAt(cursor: android.database.Cursor, column: String): String {
        val index = cursor.getColumnIndex(column)
        return if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else "absent_or_null"
    }

    private fun shareLog() {
        val file = ProbeLog.file()
        if (file == null || !file.exists()) {
            ProbeLog.event("export", "event=share_aborted reason=no_log_file_yet")
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "xx-probe log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Send probe log"))
        ProbeLog.log(
            "export",
            "event" to "share_uri_issued",
            "authority" to "$packageName.fileprovider",
            "bytes" to file.length(),
            "transport" to "ACTION_SEND chooser; app holds no INTERNET permission"
        )
    }

    private companion object {
        const val GRAPHENEOS_UPDATER_PACKAGE = "com.grapheneos.updater"
        const val TEST_NUMBER_RAW = "555-0100"
        const val TEST_NUMBER_E164 = "+15550100"
        val TEST_NUMBER_FORMS = listOf(TEST_NUMBER_RAW, TEST_NUMBER_E164)

        val RUNTIME_PERMISSIONS = arrayOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.POST_NOTIFICATIONS
        )

        val PHONE_LOOKUP_PROJECTION = arrayOf(
            ContactsContract.PhoneLookup._ID,
            ContactsContract.PhoneLookup.DISPLAY_NAME,
            ContactsContract.PhoneLookup.NUMBER,
            ContactsContract.PhoneLookup.NORMALIZED_NUMBER,
            ContactsContract.PhoneLookup.PHOTO_URI,
            ContactsContract.PhoneLookup.CONTACT_ID
        )

        val DATA_PROJECTION = arrayOf(
            ContactsContract.Data._ID,
            ContactsContract.Data.DISPLAY_NAME,
            ContactsContract.Data.MIMETYPE,
            COLUMN_ACCOUNT_NAME,
            COLUMN_ACCOUNT_TYPE
        )

        const val COLUMN_ACCOUNT_NAME = "account_name"
        const val COLUMN_ACCOUNT_TYPE = "account_type"
    }
}
