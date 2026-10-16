package com.multicast.bara

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
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
import com.google.android.material.slider.Slider
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
import kotlin.math.abs

class BackgroundSettingsActivity : AppCompatActivity() {
    private val intervals = listOf(15L, 60L, 360L, 1440L)
    private val previewExecutor = Executors.newSingleThreadExecutor()
    private var previewName: String? = null
    private var previewRevision: String? = null
    private var previewGeneration = 0
    private var running = false
    private var queued = false
    private var cropping = false
    private var dirtyCrop = false
    private var imageReady = false
    private var selectedFrame = ArtworkFrame.SCREEN.ordinal
    private var selectedRating = ArtworkRating.GENERAL.ordinal
    private var selectedAspect = ArtworkAspect.SCREEN.ordinal
    private var selectedInterval = 2
    private var draftScreenRatio = 16.0 / 9.0
    private var restoredFile: String? = null
    private var restoredCrop: CropState? = null
    private lateinit var store: ArtworkStore
    private lateinit var preview: CropPreviewView

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
        for (id in listOf(R.id.section_crop, R.id.section_discovery, R.id.section_rotation, R.id.sources_heading)) {
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
        restoredFile = savedInstanceState?.getString("draft_file")
        if (savedInstanceState?.getBoolean("draft_dirty") == true) restoredCrop = CropState(
            savedInstanceState.getDouble("draft_x"), savedInstanceState.getDouble("draft_y"),
            savedInstanceState.getDouble("draft_zoom"), savedInstanceState.getDouble("draft_ratio")).safe()
        val settings = store.settings()
        draftScreenRatio = savedInstanceState?.getDouble("draft_screen_ratio", settings.screenRatio) ?: settings.screenRatio
        findViewById<TextInputEditText>(R.id.tags_input).setText(settings.tags)
        selectedRating = (savedInstanceState?.getInt("draft_rating", settings.rating.ordinal) ?: settings.rating.ordinal).coerceIn(0, ArtworkRating.entries.lastIndex)
        selectedAspect = (savedInstanceState?.getInt("draft_aspect", settings.aspect.ordinal) ?: settings.aspect.ordinal).coerceIn(0, ArtworkAspect.entries.lastIndex)
        selectedFrame = (savedInstanceState?.getInt("draft_frame", settings.frame.ordinal) ?: settings.frame.ordinal).coerceIn(0, ArtworkFrame.entries.lastIndex)
        selectedInterval = (savedInstanceState?.getInt("draft_interval", intervals.indexOf(settings.intervalMinutes))
            ?: intervals.indexOf(settings.intervalMinutes)).coerceIn(0, intervals.lastIndex)
        bindDropdown(R.id.rating_spinner, R.array.rating_options, selectedRating) { selectedRating = it }
        bindDropdown(R.id.aspect_spinner, R.array.aspect_options, selectedAspect) { selectedAspect = it }
        bindDropdown(R.id.frame_spinner, R.array.frame_options, selectedFrame) {
            selectedFrame = it
            updateCropFrame()
        }
        bindDropdown(R.id.interval_spinner, R.array.interval_options, selectedInterval) { selectedInterval = it }
        findViewById<MaterialCheckBox>(R.id.source_danbooru).isChecked = settings.danbooru
        findViewById<MaterialCheckBox>(R.id.source_safebooru).isChecked = settings.safebooru
        findViewById<MaterialSwitch>(R.id.auto_switch).isChecked = settings.automatic
        findViewById<MaterialSwitch>(R.id.wifi_switch).isChecked = settings.wifiOnly
        findViewById<MaterialSwitch>(R.id.auto_switch).setOnCheckedChangeListener { _, _ -> renderControls() }
        findViewById<Slider>(R.id.zoom_slider).apply {
            setLabelFormatter { getString(R.string.zoom_value, it) }
            addOnChangeListener { _, value, fromUser ->
                if (fromUser && imageReady) preview.zoomBy(value.toDouble() / preview.crop.zoom)
            }
        }
        preview.onCropChanged = {
            if (!dirtyCrop && (running || queued)) ArtworkScheduler.cancelRefresh(this)
            dirtyCrop = true
            renderControls()
        }
        findViewById<MaterialButton>(R.id.crop_reset).setOnClickListener { preview.reset() }
        findViewById<MaterialButton>(R.id.capture_screen).setOnClickListener {
            draftScreenRatio = store.deviceScreenRatio()
            selectedFrame = ArtworkFrame.SCREEN.ordinal
            setDropdownText(R.id.frame_spinner, R.array.frame_options, selectedFrame)
            preview.setCrop(preview.crop.copy(ratio = draftScreenRatio))
            dirtyCrop = previewName != null
            renderControls()
        }
        findViewById<MaterialButton>(R.id.crop_apply).setOnClickListener { applySettingsAndCrop() }
        findViewById<MaterialButton>(R.id.save_settings).setOnClickListener { applySettingsAndCrop() }
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
            cropping = infos.any { ArtworkScheduler.CROP in it.tags && !it.state.isFinished }
            running = infos.any { ArtworkScheduler.CROP !in it.tags && it.state == WorkInfo.State.RUNNING }
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

    private fun updateCropFrame() {
        val current = store.current() ?: return
        val ratio = ArtworkFrame.entries[selectedFrame].ratio(draftScreenRatio,
            current.post.width.toDouble() / current.post.height)
        if (abs(ratio - preview.crop.ratio) > 0.000001) {
            preview.setCrop(preview.crop.copy(ratio = ratio))
            dirtyCrop = true
            renderControls()
        }
    }

    private fun showMessage(message: Int) {
        Snackbar.make(findViewById(R.id.settings_root), message, Snackbar.LENGTH_SHORT).show()
    }

    private fun applySettingsAndCrop() {
        val file = previewName
        val crop = preview.crop
        val applyCrop = dirtyCrop && imageReady
        if (!saveSettings()) return
        if (store.current() == null) {
            ArtworkScheduler.refresh(this)
        } else if (applyCrop && file != null && store.current()?.fileName == file) {
            ArtworkScheduler.crop(this, file, crop)
        } else {
            ExampleBackgroundReceiver.broadcastUpdate(this)
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
            frame = ArtworkFrame.entries[selectedFrame],
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
        val available = previewName != null && imageReady && !cropping
        preview.isEnabled = available
        findViewById<TextInputLayout>(R.id.frame_spinner_container).isEnabled = !cropping
        findViewById<TextInputLayout>(R.id.interval_spinner_container).isEnabled = findViewById<MaterialSwitch>(R.id.auto_switch).isChecked
        findViewById<MaterialButton>(R.id.capture_screen).isEnabled = !cropping
        findViewById<MaterialButton>(R.id.crop_reset).isEnabled = available
        findViewById<Slider>(R.id.zoom_slider).apply {
            isEnabled = available
            val zoom = preview.crop.zoom.toFloat().coerceIn(valueFrom, valueTo)
            if (abs(value - zoom) > 0.0001f) value = zoom
        }
        findViewById<TextView>(R.id.zoom_value).text = getString(R.string.zoom_value, preview.crop.zoom.toFloat())
        findViewById<MaterialButton>(R.id.crop_apply).isEnabled = available && dirtyCrop
        findViewById<View>(R.id.crop_dirty).visibility = if (dirtyCrop) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.next_artwork).isEnabled = !running && !queued && !cropping
        findViewById<MaterialButton>(R.id.save_settings).isEnabled = !cropping
        findViewById<View>(R.id.cancel_refresh).visibility = if (running || queued) View.VISIBLE else View.GONE
        findViewById<View>(R.id.load_progress).visibility = if (running || cropping) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.load_status).text = when {
            cropping -> getString(R.string.crop_saving)
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
            previewRevision = cached?.revision
            imageReady = false
            dirtyCrop = name != null && restoredFile == name && restoredCrop != null
            val state = if (dirtyCrop) restoredCrop!! else cached?.crop ?: CropState()
            restoredFile = null
            restoredCrop = null
            val generation = ++previewGeneration
            preview.setImage(null, state)
            if (name != null) previewExecutor.execute {
                val bitmap = BitmapFactory.decodeFile(File(store.directory, name).path, BitmapFactory.Options().apply { inSampleSize = 2 })
                runOnUiThread {
                    if (!isDestroyed && !isFinishing && generation == previewGeneration) {
                        preview.setImage(bitmap, preview.crop)
                        imageReady = bitmap != null
                        renderControls()
                    } else bitmap?.recycle()
                }
            }
        } else if (cached != null && cached.revision != previewRevision) {
            val notifyApplied = dirtyCrop
            previewRevision = cached.revision
            preview.setCrop(cached.crop)
            dirtyCrop = false
            if (notifyApplied) showMessage(R.string.status_crop_applied)
        }
        renderControls()
    }

    override fun onResume() {
        super.onResume()
        if (::store.isInitialized) render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("draft_file", previewName)
        outState.putBoolean("draft_dirty", dirtyCrop)
        outState.putDouble("draft_x", preview.crop.x)
        outState.putDouble("draft_y", preview.crop.y)
        outState.putDouble("draft_zoom", preview.crop.zoom)
        outState.putDouble("draft_ratio", preview.crop.ratio)
        outState.putInt("draft_frame", selectedFrame)
        outState.putInt("draft_rating", selectedRating)
        outState.putInt("draft_aspect", selectedAspect)
        outState.putInt("draft_interval", selectedInterval)
        outState.putDouble("draft_screen_ratio", draftScreenRatio)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        previewGeneration++
        previewExecutor.shutdownNow()
        preview.setImage(null, CropState())
        super.onDestroy()
    }
}
