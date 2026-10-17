package com.multicast.bara

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.widget.NestedScrollView
import androidx.core.view.WindowInsetsCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class BackgroundSettingsActivity : AppCompatActivity() {
    private val intervals = listOf(15L, 60L, 360L, 1440L)
    private val previewExecutor = Executors.newSingleThreadExecutor()
    private var previewName: String? = null
    private var previewGeneration = 0
    private var running = false
    private var queued = false
    private var selectedRating = ArtworkRating.GENERAL.ordinal
    private var selectedAspect = ArtworkAspect.SCREEN.ordinal
    private var selectedInterval = 2
    private var draftScreenRatio = 16.0 / 9.0
    private lateinit var store: ArtworkStore
    private lateinit var preview: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        enableEdgeToEdge()
        setContentView(R.layout.activity_background_settings)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settings_root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        for (id in listOf(R.id.section_preview, R.id.section_discovery, R.id.section_rotation, R.id.sources_heading)) {
            ViewCompat.setAccessibilityHeading(findViewById(id), true)
        }
        ViewCompat.requestApplyInsets(findViewById(R.id.settings_root))
        findViewById<NestedScrollView>(R.id.settings_scroll).setOnScrollChangeListener(
            NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
                findViewById<AppBarLayout>(R.id.app_bar).setLifted(scrollY > 0)
            }
        )
        store = ArtworkStore(this)
        preview = findViewById(R.id.artwork_preview)
        val settings = store.settings()
        draftScreenRatio = savedInstanceState?.getDouble("draft_screen_ratio", settings.screenRatio) ?: settings.screenRatio
        findViewById<TextInputEditText>(R.id.tags_input).setText(settings.tags)
        selectedRating = (savedInstanceState?.getInt("draft_rating", settings.rating.ordinal) ?: settings.rating.ordinal).coerceIn(0, ArtworkRating.entries.lastIndex)
        selectedAspect = (savedInstanceState?.getInt("draft_aspect", settings.aspect.ordinal) ?: settings.aspect.ordinal).coerceIn(0, ArtworkAspect.entries.lastIndex)
        selectedInterval = (savedInstanceState?.getInt("draft_interval", intervals.indexOf(settings.intervalMinutes))
            ?: intervals.indexOf(settings.intervalMinutes)).coerceIn(0, intervals.lastIndex)
        bindDropdown(R.id.rating_spinner, R.array.rating_options, selectedRating) { selectedRating = it }
        bindDropdown(R.id.aspect_spinner, R.array.aspect_options, selectedAspect) { selectedAspect = it }
        bindDropdown(R.id.interval_spinner, R.array.interval_options, selectedInterval) { selectedInterval = it }
        findViewById<MaterialCheckBox>(R.id.source_danbooru).isChecked = settings.danbooru
        findViewById<MaterialCheckBox>(R.id.source_safebooru).isChecked = settings.safebooru
        findViewById<MaterialSwitch>(R.id.auto_switch).isChecked = settings.automatic
        findViewById<MaterialSwitch>(R.id.wifi_switch).isChecked = settings.wifiOnly
        findViewById<MaterialSwitch>(R.id.auto_switch).setOnCheckedChangeListener { _, _ -> renderControls() }
        findViewById<MaterialButton>(R.id.capture_screen).setOnClickListener {
            draftScreenRatio = store.deviceScreenRatio()
        }
        findViewById<MaterialButton>(R.id.save_settings).setOnClickListener { applySettings() }
        findViewById<MaterialButton>(R.id.next_artwork).setOnClickListener {
            if (saveSettings()) ArtworkScheduler.refresh(this)
        }
        findViewById<MaterialButton>(R.id.cancel_refresh).setOnClickListener { ArtworkScheduler.cancelRefresh(this) }
        findViewById<MaterialButton>(R.id.open_original).setOnClickListener {
            val post = store.current()?.post ?: return@setOnClickListener
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(post.pageUrl))) }
            catch (_: ActivityNotFoundException) { showMessage(R.string.no_browser) }
        }
        findViewById<MaterialButton>(R.id.open_clockdesk).setOnClickListener {
            packageManager.getLaunchIntentForPackage(ExampleBackgroundReceiver.CLOCKDESK_PACKAGE)?.let { startActivity(it) }
        }
        val manager = WorkManager.getInstance(this)
        manager.getWorkInfosByTagLiveData(ArtworkScheduler.TAG).observe(this) { infos ->
            running = infos.any { it.state == WorkInfo.State.RUNNING }
            render()
        }
        manager.getWorkInfosForUniqueWorkLiveData(ArtworkScheduler.REFRESH).observe(this) { infos ->
            queued = infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
            render()
        }
        ArtworkScheduler.schedule(this, update = false)
        render()
    }

    private fun setDropdownText(id: Int, items: Int, position: Int) {
        findViewById<MaterialAutoCompleteTextView>(id).setText(resources.getStringArray(items)[position], false)
    }

    private fun bindDropdown(id: Int, items: Int, position: Int, onSelect: (Int) -> Unit) {
        findViewById<MaterialAutoCompleteTextView>(id).apply {
            setSimpleItems(items)
            setDropdownText(id, items, position)
            setOnItemClickListener { _, _, selected, _ -> onSelect(selected) }
        }
    }

    private fun showMessage(message: Int) {
        Snackbar.make(findViewById(R.id.settings_root), message, Snackbar.LENGTH_SHORT).show()
    }

    private fun applySettings() {
        if (!saveSettings()) return
        if (store.current() == null) {
            ArtworkScheduler.refresh(this)
        } else {
            showMessage(R.string.settings_saved)
        }
    }

    private fun saveSettings(): Boolean {
        val tags = try { TagQuery.parse(findViewById<TextInputEditText>(R.id.tags_input).text.toString()).text }
        catch (e: TagQueryException) {
            findViewById<TextInputLayout>(R.id.tags_container).error = getString(e.reason.message.resource)
            return false
        }
        findViewById<TextInputLayout>(R.id.tags_container).error = null
        val updated = store.settings().copy(
            tags = tags,
            rating = ArtworkRating.entries[selectedRating],
            danbooru = findViewById<MaterialCheckBox>(R.id.source_danbooru).isChecked,
            safebooru = findViewById<MaterialCheckBox>(R.id.source_safebooru).isChecked,
            automatic = findViewById<MaterialSwitch>(R.id.auto_switch).isChecked,
            wifiOnly = findViewById<MaterialSwitch>(R.id.wifi_switch).isChecked,
            intervalMinutes = intervals[selectedInterval],
            aspect = ArtworkAspect.entries[selectedAspect],
            screenRatio = draftScreenRatio
        )
        if (updated.sources.isEmpty()) {
            showMessage(R.string.source_required)
            return false
        }
        if (store.settings() != updated) {
            WorkManager.getInstance(this).cancelUniqueWork(ArtworkScheduler.REFRESH)
            store.save(updated)
        }
        ArtworkScheduler.schedule(this)
        ExampleBackgroundReceiver.broadcastUpdate(this)
        render()
        return true
    }

    private fun renderControls() {
        findViewById<TextInputLayout>(R.id.interval_spinner_container).isEnabled = findViewById<MaterialSwitch>(R.id.auto_switch).isChecked
        findViewById<MaterialButton>(R.id.next_artwork).isEnabled = !running && !queued
        findViewById<View>(R.id.cancel_refresh).visibility = if (running || queued) View.VISIBLE else View.GONE
        findViewById<View>(R.id.load_progress).visibility = if (running) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.load_status).text = when {
            running -> getString(R.string.loading_artwork)
            queued -> getString(R.string.waiting_network)
            else -> store.status()
        }
    }

    private fun render() {
        val installed = ExampleBackgroundReceiver.clockDeskInstalled(this)
        findViewById<View>(R.id.clockdesk_missing).visibility = if (installed) View.GONE else View.VISIBLE
        findViewById<View>(R.id.open_clockdesk).visibility = if (installed) View.VISIBLE else View.GONE
        val cached = store.current()
        findViewById<TextView>(R.id.artwork_caption).text = if (cached == null) getString(R.string.no_artwork) else
            getString(R.string.artwork_caption, cached.post.source.label,
                resources.getStringArray(R.array.rating_labels)[cached.post.rating.ordinal],
                cached.post.artist.ifBlank { getString(R.string.unknown_artist) },
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(cached.savedAt)))
        findViewById<MaterialButton>(R.id.open_original).isEnabled = cached != null
        findViewById<View>(R.id.empty_preview).visibility = if (cached == null) View.VISIBLE else View.GONE
        val name = cached?.fileName
        if (name != previewName) {
            previewName = name
            val generation = ++previewGeneration
            preview.setImageBitmap(null)
            if (name != null) previewExecutor.execute {
                val bitmap = BitmapFactory.decodeFile(File(store.directory, name).path, BitmapFactory.Options().apply { inSampleSize = 2 })
                runOnUiThread {
                    if (!isDestroyed && !isFinishing && generation == previewGeneration) {
                        preview.setImageBitmap(bitmap)
                        renderControls()
                    } else bitmap?.recycle()
                }
            }
        }
        renderControls()
    }

    override fun onResume() {
        super.onResume()
        if (::store.isInitialized) render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("draft_rating", selectedRating)
        outState.putInt("draft_aspect", selectedAspect)
        outState.putInt("draft_interval", selectedInterval)
        outState.putDouble("draft_screen_ratio", draftScreenRatio)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        previewGeneration++
        previewExecutor.shutdownNow()
        preview.setImageBitmap(null)
        super.onDestroy()
    }
}
