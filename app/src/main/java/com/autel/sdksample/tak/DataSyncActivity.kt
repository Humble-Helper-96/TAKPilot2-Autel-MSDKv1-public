package com.autel.sdksample.tak

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.autel.sdksample.R
import com.taklite.client.tak.TakManager
import com.taklite.client.tak.TakMissionClient
import com.taklite.util.AppLog


/**
 * Data Sync (TAK Server Mission API) — list feeds, create a feed, join/leave. Joined feed's
 * markers are pulled onto the map/AR by [TakMissionManager] and dropped markers publish to it.
 */
class DataSyncActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var joined: TextView
    private lateinit var list: LinearLayout

    /** The action-bar menu button returns to the home screen. */
    override fun onSupportNavigateUp(): Boolean {
        AppLog.v(TAG, "menu tapped — back to home")
        finish()
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_sync)
        AppLog.v(TAG, "onCreate")
        // Menu button on the left of the action bar, matching the flight screen and Pre-Flight —
        // returns to the home screen.
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_menu)
        }
        status = findViewById(R.id.dsStatus)
        joined = findViewById(R.id.dsJoined)
        list = findViewById(R.id.dsFeedList)

        findViewById<Button>(R.id.dsRefresh).setOnClickListener { AppLog.v(TAG, "Refresh tapped"); refresh() }
        findViewById<Button>(R.id.dsCreate).setOnClickListener { AppLog.v(TAG, "Create Feed tapped"); showCreateDialog() }

        if (!TakManager.getInstance().isConnected) {
            status.text = "Not connected to TAK. Enroll in TAK Setup first."
        }
        refresh()
    }

    private fun refresh() {
        updateJoined()
        status.text = "Loading feeds…"
        list.removeAllViews()
        TakMissionManager.listFeeds { feeds ->
            if (feeds.isEmpty()) {
                status.text = "No feeds found (or not connected)."
            } else {
                status.text = "${feeds.size} feed(s) on the server."
            }
            for (f in feeds) addFeedRow(f)
        }
    }

    private fun updateJoined() {
        val j = TakMissionManager.joinedFeed
        joined.text = if (j != null) "Joined: $j" else "Not in a feed."
    }

    private fun addFeedRow(f: TakMissionClient.Feed) {
        val d = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
            setBackgroundColor(androidx.core.content.ContextCompat.getColor(this@DataSyncActivity, R.color.tp_surface_card))
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = (8 * d).toInt()
        row.layoutParams = lp

        val name = TextView(this).apply {
            text = f.name + (if (f.passwordProtected) "  🔒" else "")
            setTextColor(Color.WHITE); textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        row.addView(name)

        val meta = TextView(this).apply {
            val role = f.defaultRole?.takeIf { it.isNotEmpty() } ?: "—"
            text = "${f.itemCount} items · role $role" +
                    (if (!f.description.isNullOrEmpty()) "\n${f.description}" else "")
            setTextColor(androidx.core.content.ContextCompat.getColor(this@DataSyncActivity, R.color.tp_text_tertiary)); textSize = 12f
        }
        row.addView(meta)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val isJoined = f.name == TakMissionManager.joinedFeed
        val btn = Button(this).apply {
            text = if (isJoined) "Leave" else "Join"
            // Match the app's button convention (the layout uses TakButton.* styles; a
            // programmatic Button cannot take an XML style, so set the role colours directly).
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(this@DataSyncActivity, R.color.tp_btn_info))
            setTextColor(androidx.core.content.ContextCompat.getColor(this@DataSyncActivity, R.color.tp_text_primary))
        }
        btn.setOnClickListener {
            if (isJoined) {
                AppLog.v(TAG, "Leave feed tapped: ${f.name}")
                TakMissionManager.leaveFeed { ok ->
                    AppLog.i(TAG, "leaveFeed ${f.name} -> $ok")
                    Toast.makeText(this, if (ok) "Left ${f.name}" else "Leave failed", Toast.LENGTH_SHORT).show()
                    refresh()
                }
            } else {
                AppLog.v(TAG, "Join feed tapped: ${f.name}")
                if (f.passwordProtected) promptPasswordThenJoin(f.name) else doJoin(f.name, null)
            }
        }
        btnRow.addView(btn)

        // Delete only when THIS drone created the feed (owner). Server enforces owner-only too.
        val isCreator = f.creatorUid != null && f.creatorUid == TakMissionManager.myUid()
        if (isCreator) {
            val del = Button(this).apply {
                text = "Delete"
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(this@DataSyncActivity, R.color.tp_btn_danger))
                setTextColor(androidx.core.content.ContextCompat.getColor(this@DataSyncActivity, R.color.tp_text_primary))
            }
            val dlp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            dlp.marginStart = (8 * d).toInt()
            del.layoutParams = dlp
            del.setOnClickListener { AppLog.v(TAG, "Delete feed tapped: ${f.name}"); confirmDelete(f.name) }
            btnRow.addView(del)
        }
        row.addView(btnRow)
        list.addView(row)
    }

    private fun confirmDelete(name: String) {
        AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
            .setTitle("Delete feed?")
            .setMessage("Delete \"$name\" from the TAK Server for everyone? This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                status.text = "Deleting $name…"
                TakMissionManager.deleteFeed(name) { ok ->
                    AppLog.i(TAG, "deleteFeed $name -> $ok")
                    Toast.makeText(this, if (ok) "Deleted $name" else "Delete failed (owner only)", Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }
            .show()
    }

    private fun promptPasswordThenJoin(name: String) {
        val input = dialogEditText().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Feed password"
        }
        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("Join $name")
            .setView(input)
            .setPositiveButton("Join") { _, _ -> doJoin(name, input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doJoin(name: String, password: String?) {
        status.text = "Joining $name…"
        TakMissionManager.joinFeed(name, password) { ok ->
            AppLog.i(TAG, "joinFeed $name -> $ok")
            Toast.makeText(this, if (ok) "Joined $name" else "Join failed", Toast.LENGTH_SHORT).show()
            refresh()
        }
    }

    // ---- Dark-dialog widget helpers ----
    //
    // A view passed to AlertDialog.setView() is built with the ACTIVITY's context, so it
    // inherits the activity theme, NOT the R.style.TakDialogTheme the builder was given. That
    // theme only reaches the dialog's own frame, title and buttons. The result was a correctly
    // dark card containing near-black text — readable in the layout inspector, invisible in the
    // field. Both helpers below exist to close that gap; neither is cosmetic.

    /**
     * Text colours for dialog content. Hint is deliberately lighter than the theme's #777777,
     * which measured too dim against the dialog background on a smaller controller's screen.
     *
     * ⚠ **`by lazy`, AND IT IS NOT A STYLE CHOICE — WITHOUT IT THIS SCREEN CANNOT OPEN AT ALL.**
     * A property initialiser on an Activity runs in the CONSTRUCTOR, before the framework has
     * called `attachBaseContext`. At that moment `ContextWrapper.mBase` is still null, so
     * `applicationContext` throws NullPointerException inside `newInstance` and Android reports
     * it as "Unable to instantiate activity" — the screen never reaches onCreate and the whole
     * process dies.
     *
     * As a plain field it crashes on EVERY open: colour literals need no context, but the token
     * lookups do. Nothing catches it: it is not a compile error, the app restarts straight back
     * to the home screen, and a pilot reads that as having simply left the screen.
     *
     * ⚠ DO NOT "SIMPLIFY" THIS BACK TO A DIRECT INITIALISER, and do not swap `applicationContext`
     * for `this` — `Context.getColor` resolves through the same null `mBase`. Anything on an
     * Activity that needs a Context must wait for onCreate, which is what `by lazy` does here.
     */
    private val dialogTextColor by lazy {
        androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_primary)
    }
    private val dialogHintColor by lazy {
        androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_hint_dialog)
    }

    /** EditText with explicit dark-dialog colours. */
    private fun dialogEditText(): EditText = EditText(this).apply {
        setTextColor(dialogTextColor)
        setHintTextColor(dialogHintColor)
    }

    /**
     * Spinner adapter that forces white text on both the closed view and the dropdown rows.
     *
     * Colours are set in code rather than via a themed ContextThemeWrapper because
     * `simple_spinner_dropdown_item` resolves its colour through the *spinner popup's* theme,
     * which is a separate resolution path from the dialog's — wrapping the context fixes the
     * closed view and leaves the dropdown list unstyled. Setting it on the returned view is the
     * one place both paths pass through.
     */
    private fun darkSpinnerAdapter(items: MutableList<String>) =
        object : android.widget.ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_dropdown_item, items) {
            override fun getView(pos: Int, convert: android.view.View?, parent: android.view.ViewGroup) =
                super.getView(pos, convert, parent).also {
                    (it as? TextView)?.setTextColor(dialogTextColor)
                }
            override fun getDropDownView(pos: Int, convert: android.view.View?, parent: android.view.ViewGroup) =
                super.getDropDownView(pos, convert, parent).also {
                    (it as? TextView)?.setTextColor(dialogTextColor)
                }
        }

    private fun showCreateDialog() {
        val d = resources.displayMetrics.density
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        }
        val nameIn = dialogEditText().apply { hint = "Feed name" }
        val descIn = dialogEditText().apply { hint = "Description (optional)" }
        col.addView(nameIn); col.addView(descIn)

        // Default role for users who join this feed.
        val roleLabel = TextView(this).apply {
            text = "Default role for members who join:"
            setTextColor(androidx.core.content.ContextCompat.getColor(this@DataSyncActivity, R.color.tp_text_tertiary)); textSize = 12f
            setPadding(0, (10 * d).toInt(), 0, (2 * d).toInt())
        }
        col.addView(roleLabel)
        val roleLabels = arrayOf("Subscriber (add/edit items)", "Read-Only (view only)", "Owner (full admin)")
        val roleValues = arrayOf("MISSION_SUBSCRIBER", "MISSION_READONLY_SUBSCRIBER", "MISSION_OWNER")
        val roleSpinner = android.widget.Spinner(this).apply {
            adapter = darkSpinnerAdapter(roleLabels.toMutableList())
            setSelection(0)
        }
        col.addView(roleSpinner)

        // Channel (group) the feed is scoped to — who can see it.
        val chanLabel = TextView(this).apply {
            text = "Channel (who can access this feed):"
            setTextColor(androidx.core.content.ContextCompat.getColor(this@DataSyncActivity, R.color.tp_text_tertiary)); textSize = 12f
            setPadding(0, (10 * d).toInt(), 0, (2 * d).toInt())
        }
        col.addView(chanLabel)
        val chanSpinner = android.widget.Spinner(this)
        col.addView(chanSpinner)
        // Populate with the cert's channels; first entry = default scope (no group param).
        val chanValues = ArrayList<String?>().apply { add(null) }
        val chanNames = ArrayList<String>().apply { add("Default (my scope)") }
        chanSpinner.adapter = darkSpinnerAdapter(chanNames)
        TakMissionManager.listGroups { groups ->
            for (g in groups) { chanValues.add(g); chanNames.add(g) }
            (chanSpinner.adapter as android.widget.ArrayAdapter<*>).notifyDataSetChanged()
        }

        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("Create Feed")
            .setView(col)
            .setPositiveButton("Create") { _, _ ->
                val n = nameIn.text.toString().trim()
                if (n.isEmpty()) { Toast.makeText(this, "Name required", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val role = roleValues[roleSpinner.selectedItemPosition]
                val group = chanValues.getOrNull(chanSpinner.selectedItemPosition)
                status.text = "Creating $n…"
                TakMissionManager.createFeed(n, descIn.text.toString().trim().ifEmpty { null }, role, group) { ok ->
                    AppLog.i(TAG, "createFeed $n -> $ok")
                    Toast.makeText(this, if (ok) "Created $n" else "Create failed", Toast.LENGTH_SHORT).show()
                    if (ok) doJoin(n, null) else refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object { private const val TAG = "DataSyncActivity" }
}
