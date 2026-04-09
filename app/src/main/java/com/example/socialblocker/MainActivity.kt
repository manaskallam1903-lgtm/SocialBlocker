package com.example.socialblocker

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private val PERMISSION_REQUEST_CODE = 101

    // Global Theme Colors for Dynamic Updates
    private var isDarkMode = false
    private var mainTextColor = Color.BLACK
    private var subtitleTextColor = Color.GRAY
    private var buttonBgColor = Color.LTGRAY
    private var buttonTextColor = Color.BLACK
    private var cardBgColor = Color.WHITE
    private var strokeColor = Color.LTGRAY

    // Reference for updating UI
    private lateinit var tvEmergencyContact: TextView
    private lateinit var appsContainerLayout: LinearLayout
    private var isContactPickerOpen = false
    private var isAppPickerOpen = false

    // Data classes
    data class ContactItem(val name: String, val number: String, var isChecked: Boolean)
    // ADDED: Drawable icon field to store the app's visual icon
    data class AppItem(val name: String, val packageName: String, val icon: Drawable?, var isChecked: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // --- PREMIUM MINIMALIST THEME COLORS ---
        isDarkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val bgColor = if (isDarkMode) Color.parseColor("#000000") else Color.parseColor("#F2F2F7")
        cardBgColor = if (isDarkMode) Color.parseColor("#121212") else Color.WHITE
        strokeColor = if (isDarkMode) Color.parseColor("#2A2A2A") else Color.parseColor("#E5E5EA")

        mainTextColor = if (isDarkMode) Color.WHITE else Color.BLACK
        subtitleTextColor = if (isDarkMode) Color.parseColor("#8E8E93") else Color.parseColor("#636366")
        buttonBgColor = if (isDarkMode) Color.parseColor("#2C2C2E") else Color.parseColor("#E5E5EA")
        buttonTextColor = if (isDarkMode) Color.WHITE else Color.BLACK

        window.decorView.setBackgroundColor(bgColor)
        window.statusBarColor = bgColor

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // --- HEADER ---
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 180, 60, 40)
            gravity = Gravity.START
        }

        headerLayout.addView(TextView(this).apply {
            text = "SOCIAL BLOCKER"
            textSize = 38f
            setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL))
            setTextColor(mainTextColor)
            letterSpacing = -0.03f
        })

        val tvStatusSubtitle = TextView(this).apply {
            val (left, total) = getBypassInfo()
            text = "Status: Active • $left of $total bypasses available"
            textSize = 14f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setTextColor(subtitleTextColor)
            setPadding(0, 15, 0, 0)
        }
        headerLayout.addView(tvStatusSubtitle)
        rootLayout.addView(headerLayout)

        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 120)
        }

        // --- 0. APP GUIDE ---
        addSectionHeader(contentLayout, "App Guide")
        val infoCard = createCardLayout(cardBgColor, strokeColor)
        addButtonToCard(infoCard, "How to Use This App", buttonBgColor, buttonTextColor) { showInfoDialog() }
        contentLayout.addView(infoCard)

        // --- 1. BEDTIME SECTION ---
        addSectionHeader(contentLayout, "Bedtime Routine")
        val bedtimeCard = createCardLayout(cardBgColor, strokeColor)
        setupBedtimeUI(bedtimeCard, mainTextColor, buttonBgColor, buttonTextColor)
        contentLayout.addView(bedtimeCard)

        // --- 2. DYNAMIC APP RESTRICTIONS ---
        addSectionHeader(contentLayout, "App Restrictions")
        val manageAppsCard = createCardLayout(cardBgColor, strokeColor)
        addButtonToCard(manageAppsCard, "Select Apps to Restrict", buttonBgColor, buttonTextColor) {
            if (isAppLocked()) {
                Toast.makeText(this, "Settings locked by Hardcore Mode! Request an unlock first.", Toast.LENGTH_LONG).show()
                return@addButtonToCard
            }
            showDynamicAppPicker()
        }
        contentLayout.addView(manageAppsCard)

        // Container for the dynamic app cards
        appsContainerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        contentLayout.addView(appsContainerLayout)

        // Render currently tracked apps immediately
        renderDynamicAppCards()

        // --- 3. EMERGENCY ACCESS ---
        addSectionHeader(contentLayout, "Emergency Access")
        val bypassCard = createCardLayout(cardBgColor, strokeColor)

        tvEmergencyContact = TextView(this).apply {
            textSize = 14f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setTextColor(subtitleTextColor)
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 30)
        }
        bypassCard.addView(tvEmergencyContact)
        updateEmergencyContactsUI()

        addButtonToCard(bypassCard, "Manage Emergency Contacts", buttonBgColor, buttonTextColor) {
            if (isAppLocked()) {
                Toast.makeText(this, "Settings locked by Hardcore Mode!", Toast.LENGTH_LONG).show()
                return@addButtonToCard
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                showMultiContactPicker()
            } else {
                Toast.makeText(this, "Please grant System Permissions below first!", Toast.LENGTH_LONG).show()
            }
        }
        addButtonToCard(bypassCard, "Clear Contacts", buttonBgColor, buttonTextColor) {
            if (isAppLocked()) return@addButtonToCard
            getSharedPreferences("BypassPrefs", Context.MODE_PRIVATE).edit()
                .putString("emergency_numbers", "").putString("emergency_names", "").apply()
            updateEmergencyContactsUI()
            Toast.makeText(this, "Cleared!", Toast.LENGTH_SHORT).show()
        }
        addButtonToCard(bypassCard, "Change Weekly Limit", buttonBgColor, buttonTextColor) {
            showNumberDialog("Weekly Bypasses", "BypassPrefs", "weekly_limit", 1, minVal = 1, maxVal = 3) { _ ->
                val (left, total) = getBypassInfo()
                tvStatusSubtitle.text = "Status: Active • $left of $total bypasses available"
            }
        }
        contentLayout.addView(bypassCard)

        // --- 4. HARDCORE PROTECTION ---
        addSectionHeader(contentLayout, "Hardcore Protection")
        val protectionCard = createCardLayout(cardBgColor, strokeColor)
        setupProtectionUI(protectionCard, mainTextColor, subtitleTextColor, buttonBgColor, buttonTextColor)
        contentLayout.addView(protectionCard)

        // --- 5. PERMISSIONS ---
        addSectionHeader(contentLayout, "System Configuration")
        val permissionsCard = createCardLayout(cardBgColor, strokeColor)
        addButtonToCard(permissionsCard, "1. Enable Accessibility", buttonBgColor, buttonTextColor) {
            if (isAppLocked()) {
                Toast.makeText(this, "Settings locked by Hardcore Mode!", Toast.LENGTH_LONG).show()
                return@addButtonToCard
            }
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addButtonToCard(permissionsCard, "2. System Permissions", buttonBgColor, buttonTextColor) { requestStandardPermissions() }
        addButtonToCard(permissionsCard, "3. Ignore Battery Optimization", buttonBgColor, buttonTextColor) { requestIgnoreBatteryOptimizations() }
        contentLayout.addView(permissionsCard)

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(contentLayout)
        }
        rootLayout.addView(scrollView)

        setContentView(rootLayout)
    }

    // --- NEW: Refresh UI instantly if returning from Settings with Accessibility Revoked ---
    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("BedtimePrefs", Context.MODE_PRIVATE)
        val isHardcore = prefs.getBoolean("hardcore_mode", false)
        val isPendingHardcore = prefs.getBoolean("pending_hardcore", false)

        val isAccEnabled = isAccessibilityServiceEnabled(this)

        if (!isAccEnabled) {
            // Remind them every time they open the app if the permission isn't granted
            Toast.makeText(this, "Reminder: Accessibility Service is not enabled!", Toast.LENGTH_LONG).show()

            if (isHardcore) {
                prefs.edit()
                    .putBoolean("hardcore_mode", false)
                    .putLong("hardcore_unlock_request_time", 0L)
                    .commit()
                // Recreate the activity to forcefully refresh all UI cards and buttons
                recreate()
            }
            if (isPendingHardcore) {
                prefs.edit().putBoolean("pending_hardcore", false).commit()
            }
        } else {
            // If they just granted the permission for a pending Hardcore mode request
            if (isPendingHardcore) {
                prefs.edit()
                    .putBoolean("hardcore_mode", true)
                    .putLong("hardcore_unlock_request_time", 0L)
                    .putBoolean("pending_hardcore", false)
                    .commit()
                Toast.makeText(this, "Accessibility Enabled! Hardcore Mode Activated.", Toast.LENGTH_LONG).show()
                recreate()
            }
        }
    }

    // --- DYNAMIC APP UI LOGIC ---
    private fun renderDynamicAppCards() {
        appsContainerLayout.removeAllViews()
        val prefs = getSharedPreferences("AppTimerPrefs", Context.MODE_PRIVATE)
        val trackedApps = prefs.getStringSet("tracked_packages", setOf("com.instagram.android", "com.google.android.youtube")) ?: setOf()

        for (pkg in trackedApps) {
            val appName = getAppName(pkg)

            // Map old prefixes for backwards compatibility
            val prefix = when (pkg) {
                "com.instagram.android" -> "ig"
                "com.google.android.youtube" -> "yt"
                else -> pkg.replace(".", "_")
            }

            addSectionHeader(appsContainerLayout, "$appName Limits")
            val card = createCardLayout(cardBgColor, strokeColor)
            setupDynamicAppTimerUI(card, prefix, appName, mainTextColor, buttonBgColor, buttonTextColor)
            appsContainerLayout.addView(card)
        }
    }

    private fun setupDynamicAppTimerUI(layout: LinearLayout, prefix: String, appName: String, textColor: Int, btnBg: Int, btnText: Int) {
        val prefs = getSharedPreferences("AppTimerPrefs", Context.MODE_PRIVATE)

        val tvStats = TextView(this).apply {
            textSize = 15f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setTextColor(textColor)
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 30)
        }

        fun updateStats() {
            val limitMins = prefs.getInt("${prefix}_limit_mins", 10)
            val cooldownHours = prefs.getInt("${prefix}_cooldown_hours", 1)
            tvStats.text = "Daily Limit: $limitMins Mins\nCooldown: $cooldownHours Hour(s)"
        }
        updateStats()

        layout.addView(tvStats)
        addButtonToCard(layout, "Set Limit (Mins)", btnBg, btnText) {
            showNumberDialog("$appName Daily Limit (Mins)", "AppTimerPrefs", "${prefix}_limit_mins", 10) { updateStats() }
        }
        addButtonToCard(layout, "Set Cooldown (Hours)", btnBg, btnText) {
            showNumberDialog("$appName Cooldown (Hours)", "AppTimerPrefs", "${prefix}_cooldown_hours", 1) { updateStats() }
        }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    // --- CUSTOM APP PICKER ---
    @SuppressLint("InflateParams")
    private fun showDynamicAppPicker() {
        if (isAppPickerOpen) return
        isAppPickerOpen = true

        Toast.makeText(this, "Loading installed apps...", Toast.LENGTH_SHORT).show()

        Thread {
            val appList = mutableListOf<AppItem>()
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val apps = pm.queryIntentActivities(intent, PackageManager.GET_META_DATA)

            for (resolveInfo in apps) {
                val pkgName = resolveInfo.activityInfo.packageName
                if (pkgName != packageName) { // Exclude Social Blocker itself
                    val name = resolveInfo.loadLabel(pm).toString()
                    val icon = resolveInfo.loadIcon(pm) // Grab the actual high-res icon

                    // Check for duplicates
                    if (!appList.any { it.packageName == pkgName }) {
                        appList.add(AppItem(name, pkgName, icon, false))
                    }
                }
            }
            appList.sortBy { it.name.lowercase() }

            runOnUiThread {
                val prefs = getSharedPreferences("AppTimerPrefs", Context.MODE_PRIVATE)
                val trackedApps = prefs.getStringSet("tracked_packages", setOf("com.instagram.android", "com.google.android.youtube")) ?: setOf()

                appList.forEach { if (trackedApps.contains(it.packageName)) it.isChecked = true }

                val dialogContainer = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(40, 50, 40, 20)
                }

                val searchInput = EditText(this@MainActivity).apply {
                    hint = "Search apps..."
                    setHintTextColor(subtitleTextColor)
                    setTextColor(mainTextColor)
                    inputType = InputType.TYPE_CLASS_TEXT
                    setPadding(40, 35, 40, 35)
                    background = GradientDrawable().apply {
                        cornerRadius = 40f
                        setColor(if (isDarkMode) Color.parseColor("#1C1C1E") else Color.parseColor("#E5E5EA"))
                    }
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 0, 0, 30)
                    }
                }
                dialogContainer.addView(searchInput)

                val listContainer = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                val scrollView = ScrollView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 800)
                    addView(listContainer)
                }
                dialogContainer.addView(scrollView)

                fun renderApps(query: String) {
                    listContainer.removeAllViews()
                    val filtered = appList.filter {
                        it.name.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
                    }

                    if (filtered.isEmpty()) {
                        listContainer.addView(TextView(this@MainActivity).apply {
                            text = "No apps found. (Did you add QUERY_ALL_PACKAGES to Manifest?)"
                            setTextColor(subtitleTextColor)
                            setPadding(20, 40, 20, 40)
                            gravity = Gravity.CENTER
                        })
                        return
                    }

                    for (app in filtered) {
                        val row = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(30, 40, 30, 40)
                            gravity = Gravity.CENTER_VERTICAL
                            isClickable = true

                            // Inject the ImageView into the UI for the App Icon
                            val iconView = ImageView(this@MainActivity).apply {
                                setImageDrawable(app.icon)
                                layoutParams = LinearLayout.LayoutParams(110, 110).apply {
                                    setMargins(0, 0, 40, 0)
                                }
                            }
                            addView(iconView)

                            val nameText = TextView(this@MainActivity).apply {
                                text = "${app.name}\n"
                                append(android.text.SpannableString(app.packageName).apply {
                                    setSpan(android.text.style.RelativeSizeSpan(0.8f), 0, length, 0)
                                    setSpan(android.text.style.ForegroundColorSpan(subtitleTextColor), 0, length, 0)
                                })
                                setTextColor(if (app.isChecked) Color.parseColor("#A3B5FF") else mainTextColor)
                                textSize = 15f
                                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            }

                            val checkText = TextView(this@MainActivity).apply {
                                text = if (app.isChecked) "✓" else ""
                                setTextColor(Color.parseColor("#A3B5FF"))
                                textSize = 20f
                                setTypeface(null, Typeface.BOLD)
                            }

                            addView(nameText)
                            addView(checkText)

                            setOnClickListener {
                                app.isChecked = !app.isChecked
                                nameText.setTextColor(if (app.isChecked) Color.parseColor("#A3B5FF") else mainTextColor)
                                checkText.text = if (app.isChecked) "✓" else ""
                            }
                        }
                        listContainer.addView(row)
                    }
                }

                renderApps("")

                searchInput.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderApps(s.toString()) }
                    override fun afterTextChanged(s: Editable?) {}
                })

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Select Apps to Restrict")
                    .setView(dialogContainer)
                    .setPositiveButton("Save") { _, _ ->
                        val newTrackedSet = mutableSetOf<String>()
                        for (app in appList) {
                            if (app.isChecked) newTrackedSet.add(app.packageName)
                        }

                        // Save tracked packages
                        prefs.edit().putStringSet("tracked_packages", newTrackedSet).apply()

                        // Dynamically refresh the UI with new cards
                        renderDynamicAppCards()

                        Toast.makeText(this@MainActivity, "App restrictions updated!", Toast.LENGTH_SHORT).show()
                        isAppPickerOpen = false
                    }
                    .setNegativeButton("Cancel") { _, _ -> isAppPickerOpen = false }
                    .setOnCancelListener { isAppPickerOpen = false }
                    .show()
            }
        }.start()
    }


    // --- MULTIPLE CONTACTS UI HELPER ---
    private fun updateEmergencyContactsUI() {
        val prefs = getSharedPreferences("BypassPrefs", Context.MODE_PRIVATE)
        var numbers = prefs.getString("emergency_numbers", "") ?: ""
        var names = prefs.getString("emergency_names", "") ?: ""

        if (numbers.isEmpty() && prefs.contains("emergency_number")) {
            numbers = prefs.getString("emergency_number", "") ?: ""
            names = prefs.getString("emergency_name", "") ?: ""
            prefs.edit()
                .putString("emergency_numbers", numbers)
                .putString("emergency_names", names)
                .remove("emergency_number")
                .remove("emergency_name")
                .apply()
        }

        if (numbers.isEmpty()) {
            tvEmergencyContact.text = "Emergency Contacts: None"
        } else {
            val numList = numbers.split(",")
            val nameList = names.split(",")
            val displayText = StringBuilder("Emergency Contacts:\n")
            for (i in numList.indices) {
                val name = if (i < nameList.size && nameList[i].isNotEmpty()) nameList[i] else "Unknown"
                displayText.append("• $name (${numList[i]})\n")
            }
            tvEmergencyContact.text = displayText.toString().trim()
        }
    }

    // --- CUSTOM SEARCHABLE CONTACT PICKER LOGIC ---
    @SuppressLint("InflateParams")
    private fun showMultiContactPicker() {
        if (isContactPickerOpen) return
        isContactPickerOpen = true

        Toast.makeText(this, "Loading contacts...", Toast.LENGTH_SHORT).show()

        Thread {
            val contactList = mutableListOf<ContactItem>()
            val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)

            try {
                contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null,
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
                )?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameIdx) ?: "Unknown"
                        val num = cursor.getString(numIdx)?.replace(Regex("[^0-9+]"), "") ?: ""

                        if (num.isNotEmpty() && !contactList.any { it.number == num }) {
                            contactList.add(ContactItem(name, num, false))
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    isContactPickerOpen = false
                    Toast.makeText(this@MainActivity, "Failed to load contacts.", Toast.LENGTH_SHORT).show()
                }
                return@Thread
            }

            runOnUiThread {
                if (contactList.isEmpty()) {
                    isContactPickerOpen = false
                    Toast.makeText(this@MainActivity, "No contacts found.", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }

                val prefs = getSharedPreferences("BypassPrefs", Context.MODE_PRIVATE)
                val existingNumbers = (prefs.getString("emergency_numbers", "") ?: "").split(",").filter { it.isNotEmpty() }
                contactList.forEach { if (existingNumbers.contains(it.number)) it.isChecked = true }

                val dialogContainer = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(40, 50, 40, 20)
                }

                val searchInput = EditText(this@MainActivity).apply {
                    hint = "Search name or number..."
                    setHintTextColor(subtitleTextColor)
                    setTextColor(mainTextColor)
                    inputType = InputType.TYPE_CLASS_TEXT
                    setPadding(40, 35, 40, 35)
                    background = GradientDrawable().apply {
                        cornerRadius = 40f
                        setColor(if (isDarkMode) Color.parseColor("#1C1C1E") else Color.parseColor("#E5E5EA"))
                    }
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 0, 0, 30)
                    }
                }
                dialogContainer.addView(searchInput)

                val listContainer = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                val scrollView = ScrollView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 800)
                    addView(listContainer)
                }
                dialogContainer.addView(scrollView)

                fun renderContacts(query: String) {
                    listContainer.removeAllViews()
                    val filtered = contactList.filter {
                        it.name.contains(query, ignoreCase = true) || it.number.contains(query)
                    }

                    if (filtered.isEmpty()) {
                        listContainer.addView(TextView(this@MainActivity).apply {
                            text = "No matches."
                            setTextColor(subtitleTextColor)
                            setPadding(20, 40, 20, 40)
                            gravity = Gravity.CENTER
                        })
                        return
                    }

                    for (contact in filtered) {
                        val row = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(30, 40, 30, 40)
                            gravity = Gravity.CENTER_VERTICAL
                            isClickable = true

                            val nameText = TextView(this@MainActivity).apply {
                                text = "${contact.name}\n${contact.number}"
                                setTextColor(if (contact.isChecked) Color.parseColor("#A3B5FF") else mainTextColor)
                                textSize = 15f
                                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            }

                            val checkText = TextView(this@MainActivity).apply {
                                text = if (contact.isChecked) "✓" else ""
                                setTextColor(Color.parseColor("#A3B5FF"))
                                textSize = 20f
                                setTypeface(null, Typeface.BOLD)
                            }

                            addView(nameText)
                            addView(checkText)

                            setOnClickListener {
                                contact.isChecked = !contact.isChecked
                                nameText.setTextColor(if (contact.isChecked) Color.parseColor("#A3B5FF") else mainTextColor)
                                checkText.text = if (contact.isChecked) "✓" else ""
                            }
                        }
                        listContainer.addView(row)
                    }
                }

                renderContacts("")

                searchInput.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderContacts(s.toString()) }
                    override fun afterTextChanged(s: Editable?) {}
                })

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Emergency Contacts")
                    .setView(dialogContainer)
                    .setPositiveButton("Save") { _, _ ->
                        val newNumList = mutableListOf<String>()
                        val newNameList = mutableListOf<String>()
                        for (c in contactList) {
                            if (c.isChecked) {
                                newNumList.add(c.number)
                                newNameList.add(c.name)
                            }
                        }
                        prefs.edit()
                            .putString("emergency_numbers", newNumList.joinToString(","))
                            .putString("emergency_names", newNameList.joinToString(","))
                            .apply()
                        updateEmergencyContactsUI()
                        Toast.makeText(this@MainActivity, "Contacts saved!", Toast.LENGTH_SHORT).show()
                        isContactPickerOpen = false
                    }
                    .setNegativeButton("Cancel") { _, _ -> isContactPickerOpen = false }
                    .setOnCancelListener { isContactPickerOpen = false }
                    .show()
            }
        }.start()
    }

    private fun isAppLocked(): Boolean {
        val prefs = getSharedPreferences("BedtimePrefs", Context.MODE_PRIVATE)
        val isHardcore = prefs.getBoolean("hardcore_mode", false)

        // FAILSAFE: Automatically unlock if accessibility permission is missing
        if (isHardcore && !isAccessibilityServiceEnabled(this)) {
            prefs.edit()
                .putBoolean("hardcore_mode", false)
                .putLong("hardcore_unlock_request_time", 0L)
                .commit()
            return false
        }

        if (!isHardcore) return false

        val requestTime = prefs.getLong("hardcore_unlock_request_time", 0L)
        var waitHours = prefs.getInt("hardcore_wait_hours", 3)
        if (waitHours < 3) waitHours = 3

        if (requestTime == 0L) return true

        val unlockTime = requestTime + (waitHours * 3600000L)
        return System.currentTimeMillis() < unlockTime
    }

    private fun addSectionHeader(layout: LinearLayout, title: String) {
        layout.addView(TextView(this).apply {
            text = title.uppercase()
            textSize = 12f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setTextColor(subtitleTextColor)
            letterSpacing = 0.08f
            setPadding(75, 50, 75, 15)
        })
    }

    private fun createCardLayout(bgColorInt: Int, strokeColorInt: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 60f
                setColor(bgColorInt)
                setStroke(2, strokeColorInt)
            }
            setPadding(40, 40, 40, 40)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(50, 0, 50, 10)
            }
        }
    }

    private fun addButtonToCard(card: LinearLayout, text: String, bgColor: Int, textColor: Int, onClick: () -> Unit): Button {
        val btn = Button(this).apply {
            this.text = text
            this.isAllCaps = false
            this.textSize = 15f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            this.setTextColor(textColor)
            this.setPadding(0, 40, 0, 40)
            background = GradientDrawable().apply {
                cornerRadius = 45f
                setColor(bgColor)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 10, 0, 10)
            }
            stateListAnimator = null
            elevation = 0f

            setOnClickListener { onClick() }
        }
        card.addView(btn)
        return btn
    }

    private fun showInfoDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("App Guide & Warnings")

        val message = """
            Welcome to Social Blocker!
            
            🛌 BEDTIME MODE
            Blacks out your screen completely during sleep hours.
            
            ⏳ DYNAMIC APP RESTRICTIONS & TIMERS
            Use "Select Apps to Restrict" to choose any app to limit. 
            • A floating timer will appear in the top-right corner to show your remaining daily time.
            • Once your limit is reached, a full-screen radial cooldown timer will block the app until the cooldown period ends!
            • Note: Instagram Reels & YouTube Shorts are ALWAYS blocked instantly.
            
            🛡️ HARDCORE PROTECTION
            Actively blocks your phone's Settings so you cannot cheat or uninstall the app.
            
            🔑 BYPASSES & PENALTIES
            Hold the bottom center of the black screen for 3s to get a 1-hour bypass. 
            If a saved Emergency Contact calls, you get a 15-minute free bypass after the call. 
            ⚠️ PENALTY: Opening social media during this post-call bypass instantly ends it and costs 1 weekly bypass!
        """.trimIndent()

        val tvMessage = TextView(this).apply {
            text = message
            textSize = 14f
            setTextColor(mainTextColor)
            setPadding(60, 40, 60, 20)
            setLineSpacing(0f, 1.2f)
        }

        val scrollView = ScrollView(this).apply { addView(tvMessage) }
        builder.setView(scrollView)
        builder.setPositiveButton("I Understand", null)
        builder.show()
    }

    private fun setupProtectionUI(layout: LinearLayout, textColor: Int, subColor: Int, btnBg: Int, btnText: Int) {
        val prefs = getSharedPreferences("BedtimePrefs", Context.MODE_PRIVATE)

        val tvStatus = TextView(this).apply {
            textSize = 14f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setTextColor(textColor)
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 30)
        }

        var btnToggle: Button? = null
        var btnCancel: Button? = null

        fun updateUI() {
            val isHardcore = prefs.getBoolean("hardcore_mode", false)
            val requestTime = prefs.getLong("hardcore_unlock_request_time", 0L)

            var waitHours = prefs.getInt("hardcore_wait_hours", 3)
            if (waitHours < 3) waitHours = 3

            if (btnCancel != null) {
                layout.removeView(btnCancel)
                btnCancel = null
            }

            if (isHardcore) {
                if (requestTime > 0L) {
                    val unlockTime = requestTime + (waitHours * 3600000L)
                    val remainingMs = unlockTime - System.currentTimeMillis()

                    if (remainingMs > 0) {
                        val hoursLeft = remainingMs / 3600000L
                        val minsLeft = (remainingMs % 3600000L) / 60000L
                        tvStatus.text = "STATUS: UNLOCK PENDING\nTime remaining: ${hoursLeft}h ${minsLeft}m"
                        tvStatus.setTextColor(Color.parseColor("#FF9F0A"))

                        btnToggle?.text = "Refresh Timer"
                        btnToggle?.setOnClickListener { updateUI() }

                        btnCancel = addButtonToCard(layout, "Cancel Unlock Request", Color.parseColor("#FF453A"), Color.WHITE) {
                            prefs.edit().putLong("hardcore_unlock_request_time", 0L).apply()
                            Toast.makeText(this, "Unlock Cancelled. Phone is still locked.", Toast.LENGTH_SHORT).show()
                            updateUI()
                        }
                    } else {
                        tvStatus.text = "STATUS: READY TO UNLOCK\nTimer completed."
                        tvStatus.setTextColor(Color.parseColor("#32D74B"))

                        btnToggle?.text = "Confirm Unlock & Disable Hardcore"
                        btnToggle?.setOnClickListener {
                            prefs.edit().putBoolean("hardcore_mode", false)
                                .putLong("hardcore_unlock_request_time", 0L).apply()
                            Toast.makeText(this, "Hardcore Mode Disabled!", Toast.LENGTH_SHORT).show()
                            updateUI()
                        }
                    }
                } else {
                    tvStatus.text = "STATUS: LOCKED\nSettings, limits & uninstalls are blocked."
                    tvStatus.setTextColor(Color.parseColor("#FF453A"))

                    btnToggle?.text = "Request Unlock ($waitHours Hour Wait)"
                    btnToggle?.setOnClickListener {
                        AlertDialog.Builder(this)
                            .setTitle("Request Unlock")
                            .setMessage("Are you sure you want to start the $waitHours hour unlock timer? You can cancel it during the wait if your urge passes.")
                            .setPositiveButton("Start Timer") { _, _ ->
                                prefs.edit().putLong("hardcore_unlock_request_time", System.currentTimeMillis()).apply()
                                Toast.makeText(this, "Timer Started!", Toast.LENGTH_SHORT).show()
                                updateUI()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            } else {
                tvStatus.text = "STATUS: UNLOCKED\nApp can be modified freely."
                tvStatus.setTextColor(Color.parseColor("#32D74B"))

                btnToggle?.text = "Enable Hardcore Lock"
                btnToggle?.setOnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("WARNING: Hardcore Lock")
                        .setMessage("This will block your phone's Settings and prevent uninstallation. If you want to disable it later, you must wait a MINIMUM of 3 HOURS after requesting an unlock. Are you absolutely sure?")
                        .setPositiveButton("I Understand") { _, _ ->
                            showNumberDialog("Set Unlock Wait Time (Hours)", "BedtimePrefs", "hardcore_wait_hours", 3, minVal = 3) { _ ->
                                if (!isAccessibilityServiceEnabled(this@MainActivity)) {
                                    // If permission is missing, save pending flag and redirect to settings
                                    prefs.edit().putBoolean("pending_hardcore", true).apply()
                                    Toast.makeText(this@MainActivity, "Please enable Accessibility Service first to activate Hardcore Mode!", Toast.LENGTH_LONG).show()
                                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                } else {
                                    prefs.edit().putBoolean("hardcore_mode", true)
                                        .putLong("hardcore_unlock_request_time", 0L).apply()
                                    Toast.makeText(this@MainActivity, "Hardcore Mode Activated!", Toast.LENGTH_SHORT).show()
                                    updateUI()
                                }
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }

        layout.addView(tvStatus)
        btnToggle = addButtonToCard(layout, "", btnBg, btnText) { }
        updateUI()
    }

    private fun setupBedtimeUI(layout: LinearLayout, textColor: Int, btnBg: Int, btnText: Int) {
        val prefs = getSharedPreferences("BedtimePrefs", Context.MODE_PRIVATE)

        val tvTime = TextView(this).apply {
            textSize = 32f
            setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL))
            setTextColor(textColor)
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 30)
        }

        fun updateTimeText() {
            val sH = prefs.getInt("start_hour", 23)
            val sM = prefs.getInt("start_minute", 0)
            val eH = prefs.getInt("end_hour", 6)
            val eM = prefs.getInt("end_minute", 0)
            tvTime.text = "${formatTime(sH, sM)} — ${formatTime(eH, eM)}"
        }
        updateTimeText()

        val toggleBtn = addButtonToCard(layout, "", btnBg, btnText) { }
        fun updateToggleBtn() {
            val isEnabled = prefs.getBoolean("bedtime_enabled", true)
            toggleBtn.text = if (isEnabled) "Status: ON (Tap to Disable)" else "Status: OFF (Tap to Enable)"
            toggleBtn.setTextColor(if (isEnabled) Color.parseColor("#32D74B") else Color.parseColor("#FF453A"))
        }
        updateToggleBtn()

        toggleBtn.setOnClickListener {
            if (isAppLocked()) {
                Toast.makeText(this, "Settings locked by Hardcore Mode!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val current = prefs.getBoolean("bedtime_enabled", true)
            prefs.edit().putBoolean("bedtime_enabled", !current).apply()
            updateToggleBtn()
        }

        layout.addView(tvTime)
        addButtonToCard(layout, "Set Sleep Time", btnBg, btnText) {
            showTimePicker("start_hour", "start_minute", prefs) { updateTimeText() }
        }
        addButtonToCard(layout, "Set Wake Time", btnBg, btnText) {
            showTimePicker("end_hour", "end_minute", prefs) { updateTimeText() }
        }
    }

    private fun showNumberDialog(title: String, prefsName: String, key: String, defaultVal: Int, minVal: Int = 1, maxVal: Int = Int.MAX_VALUE, onSave: (Int) -> Unit) {
        if (isAppLocked()) {
            Toast.makeText(this, "Settings locked by Hardcore Mode! Request an unlock first.", Toast.LENGTH_LONG).show()
            return
        }

        val builder = AlertDialog.Builder(this)
        builder.setTitle(title)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(getSharedPreferences(prefsName, Context.MODE_PRIVATE).getInt(key, defaultVal).toString())
            setSelection(text.length)
        }
        val container = LinearLayout(this).apply {
            setPadding(60, 20, 60, 0)
            addView(input, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        builder.setView(container)
        builder.setPositiveButton("Save") { _, _ ->
            var num = input.text.toString().toIntOrNull() ?: defaultVal
            if (num < minVal) {
                num = minVal
                Toast.makeText(this, "Minimum value allowed is $minVal", Toast.LENGTH_SHORT).show()
            } else if (num > maxVal) {
                num = maxVal
                Toast.makeText(this, "Maximum value allowed is $maxVal", Toast.LENGTH_SHORT).show()
            }
            getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putInt(key, num).apply()
            onSave(num)
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun showTimePicker(hourKey: String, minuteKey: String, prefs: android.content.SharedPreferences, onSave: () -> Unit) {
        if (isAppLocked()) {
            Toast.makeText(this, "Settings locked by Hardcore Mode!", Toast.LENGTH_LONG).show()
            return
        }

        val currentHour = prefs.getInt(hourKey, 12)
        val currentMinute = prefs.getInt(minuteKey, 0)
        TimePickerDialog(this, { _, selectedHour, selectedMinute ->
            prefs.edit().putInt(hourKey, selectedHour).putInt(minuteKey, selectedMinute).apply()
            onSave()
        }, currentHour, currentMinute, false).show()
    }

    private fun formatTime(hour: Int, minute: Int): String {
        val amPm = if (hour >= 12) "PM" else "AM"
        var h = if (hour > 12) hour - 12 else hour
        if (h == 0) h = 12
        return String.format("%d:%02d %s", h, minute, amPm)
    }

    private fun getBypassInfo(): Pair<Int, Int> {
        val prefs = getSharedPreferences("BypassPrefs", Context.MODE_PRIVATE)
        val currentWeek = Calendar.getInstance().get(Calendar.WEEK_OF_YEAR)
        val lastBypassWeek = prefs.getInt("last_bypass_week", -1)
        val totalAllowed = prefs.getInt("weekly_limit", 1)
        if (currentWeek != lastBypassWeek) {
            prefs.edit().putInt("used_this_week", 0).apply()
        }
        val used = prefs.getInt("used_this_week", 0)
        return Pair(Math.max(0, totalAllowed - used), totalAllowed)
    }

    private fun requestStandardPermissions() {
        val permissions = mutableListOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        val ungranted = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (ungranted.isNotEmpty()) ActivityCompat.requestPermissions(this, ungranted.toTypedArray(), PERMISSION_REQUEST_CODE)
        else Toast.makeText(this, "Permissions already granted", Toast.LENGTH_SHORT).show()
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:$packageName") }
                    startActivity(intent)
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            } else Toast.makeText(this, "Battery optimization already disabled", Toast.LENGTH_SHORT).show()
        }
    }

    // --- HELPER: Check if Accessibility is Granted ---
    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedComponentName = "${context.packageName}/${AppBlockerService::class.java.canonicalName}"
        var accessibilityEnabled = 0
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED
            )
        } catch (e: Settings.SettingNotFoundException) {
            // Ignore if setting not found
        }

        if (accessibilityEnabled == 1) {
            val settingValue = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (settingValue != null) {
                val splitter = TextUtils.SimpleStringSplitter(':')
                splitter.setString(settingValue)
                while (splitter.hasNext()) {
                    val accessibilityService = splitter.next()
                    if (accessibilityService.equals(expectedComponentName, ignoreCase = true)) {
                        return true
                    }
                }
            }
        }
        return false
    }
}