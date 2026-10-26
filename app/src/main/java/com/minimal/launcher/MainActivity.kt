package com.minimal.launcher

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.GestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Space
import android.widget.TextClock
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.exifinterface.media.ExifInterface
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.util.Calendar
import kotlin.math.abs

private const val BG = 0xFF0E0E0F.toInt()
private const val FG = 0xFFEDEDED.toInt()
private const val MUTED = 0xFF9A9A9E.toInt()
private const val DIM = 0xFF8A8A8E.toInt()
private const val FAINT = 0xFF4A4A4E.toInt()
private const val CARD = 0xFF18181A.toInt()
private const val LINE = 0xFF2A2A2E.toInt()
private const val WHEEL = 0xFF1C1C1F.toInt()
private const val SCREEN = 0xFFE9E8E3.toInt()
private const val INK = 0xFF141416.toInt()
private const val INK2 = 0xFF4A4A4E.toInt()
private const val SCREEN_LINE = 0xFFC4C4C0.toInt()
private const val WHEEL_ICON = 0xFFC8C8CC.toInt()

private const val YTM = "com.google.android.apps.youtube.music"
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val MAX_FAVS = 7

class MainActivity : ComponentActivity() {

    private val ctx: Context get() = this
    private lateinit var prefs: SharedPreferences
    private var apps: List<AppInfo> = emptyList()

    private lateinit var pager: ViewPager2
    private lateinit var gesture: GestureDetector
    private val handler = Handler(Looper.getMainLooper())
    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val gray = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })

    // Home
    private lateinit var favBox: LinearLayout
    private lateinit var dockBox: LinearLayout

    // Drawer
    private lateinit var drawer: LinearLayout
    private lateinit var search: EditText
    private lateinit var drawerList: RecyclerView
    private lateinit var drawerAdapter: DrawerAdapter

    // Memories
    private lateinit var memImage: ImageView
    private lateinit var memEmpty: TextView
    private lateinit var memAgo: TextView
    private lateinit var memCaption: TextView
    private var memOffset = 0

    // Music
    private lateinit var mStatus: TextView
    private lateinit var mState: TextView
    private lateinit var mTitle: TextView
    private lateinit var mArtist: TextView
    private lateinit var mProgress: ProgressBar
    private lateinit var mElapsed: TextView
    private lateinit var mRemain: TextView
    private var controller: MediaController? = null
    private var listeningSessions = false

    private val pickPhotos =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
            if (uris.isNotEmpty()) {
                uris.forEach {
                    try {
                        contentResolver.takePersistableUriPermission(
                            it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {
                    }
                }
                val all = (memories() + uris.map { it.toString() }).distinct()
                prefs.edit().putString("memories", all.joinToString("\n")).apply()
                memOffset = 0
                showMemory()
            }
        }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("launcher", MODE_PRIVATE)
        apps = Apps.load(this)

        val root = FrameLayout(ctx).apply { setBackgroundColor(BG) }
        pager = ViewPager2(ctx)
        pager.adapter = PagesAdapter(listOf(buildMemories(), buildHome(), buildMusic()))
        pager.offscreenPageLimit = 2
        pager.setCurrentItem(1, false)
        root.addView(pager, FrameLayout.LayoutParams(MATCH, MATCH))

        drawer = buildDrawer()
        root.addView(drawer, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        gesture = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                if (abs(dy) < abs(dx) || abs(dy) < dp(80)) return false
                if (drawer.visibility != View.VISIBLE && dy < 0 && pager.currentItem == 1) {
                    openDrawer(); return true
                }
                if (drawer.visibility == View.VISIBLE && dy > 0 &&
                    !drawerList.canScrollVertically(-1)
                ) {
                    closeDrawer(); return true
                }
                return false
            }
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawer.visibility == View.VISIBLE) closeDrawer()
                else pager.setCurrentItem(1, true)
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gesture.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home button pressed while already home.
        closeDrawer()
        pager.setCurrentItem(1, true)
    }

    override fun onResume() {
        super.onResume()
        apps = Apps.load(this)
        if (!prefs.getBoolean("init", false)) setDefaults()
        renderFavs()
        renderDock()
        filterDrawer()
        showMemory()
        connectMedia()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        super.onDestroy()
        controller?.unregisterCallback(controllerCallback)
        if (listeningSessions) {
            try {
                (getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager)
                    .removeOnActiveSessionsChangedListener(sessionsListener)
            } catch (_: Exception) {
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(s: String, sp: Float, color: Int = FG, face: Typeface = regular): TextView =
        TextView(ctx).apply {
            text = s
            textSize = sp
            setTextColor(color)
            typeface = face
        }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun oval(color: Int, stroke: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun weight() = LinearLayout.LayoutParams(MATCH, 0, 1f)

    private fun dialog() = AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun pickApp(title: String, onPick: (AppInfo) -> Unit) {
        dialog().setTitle(title)
            .setItems(apps.map { it.label }.toTypedArray()) { _, which -> onPick(apps[which]) }
            .show()
    }

    private fun safeStart(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    private fun appLabel(pkg: String) = apps.find { it.pkg == pkg }?.label ?: pkg

    // ---------------------------------------------------------------- defaults

    private fun setDefaults() {
        fun byPkg(p: String?): AppInfo? = p?.let { pk -> apps.find { it.pkg == pk } }
        fun byIntent(i: Intent): AppInfo? = byPkg(
            packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.takeIf { it != "android" }
        )

        val dial = byIntent(Intent(Intent.ACTION_DIAL))
        val sms = byPkg(Telephony.Sms.getDefaultSmsPackage(ctx))
        val web = byIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")))
        val cam = byIntent(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
        val whatsapp = byPkg("com.whatsapp")
        val ytm = byPkg(YTM)
        val gmail = byPkg("com.google.android.gm")
        val calendar = byPkg("com.google.android.calendar")
        val photos = byPkg("com.google.android.apps.photos")

        setFavs(listOfNotNull(dial, sms, whatsapp, gmail, calendar, photos).distinct().take(6))
        listOf(dial, whatsapp, web, ytm, cam).forEachIndexed { i, a -> saveSlot("dock$i", a) }
        prefs.edit().putBoolean("init", true).apply()
    }

    // ---------------------------------------------------------------- home

    private fun buildHome(): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(48), dp(32), dp(20))
        }
        val clock = TextClock(ctx).apply {
            format12Hour = "h:mm"
            format24Hour = "HH:mm"
            textSize = 64f
            setTextColor(FG)
            typeface = light
            letterSpacing = -0.03f
            setOnClickListener { safeStart(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
        }
        val date = TextClock(ctx).apply {
            format12Hour = "EEE, d MMM"
            format24Hour = "EEE, d MMM"
            textSize = 15f
            setTextColor(MUTED)
        }
        col.addView(clock)
        col.addView(date)
        col.addView(Space(ctx), weight())

        favBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(favBox)
        col.addView(Space(ctx), weight())

        val hint = label("swipe up for all apps", 12f, DIM).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            setPadding(0, dp(10), 0, dp(14))
            setOnClickListener { openDrawer() }
        }
        col.addView(hint, LinearLayout.LayoutParams(MATCH, WRAP))

        dockBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(CARD, 28, LINE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        col.addView(dockBox, LinearLayout.LayoutParams(MATCH, WRAP))
        return col
    }

    private fun favs(): List<AppInfo> =
        (prefs.getString("favs", "") ?: "").split("|")
            .filter { it.isNotEmpty() }
            .mapNotNull { k -> apps.find { it.key == k } }

    private fun setFavs(list: List<AppInfo>) {
        prefs.edit().putString("favs", list.joinToString("|") { it.key }).apply()
    }

    private fun renderFavs() {
        favBox.removeAllViews()
        val list = favs()
        list.forEachIndexed { i, app ->
            val tv = label(app.label, 28f, FG, light).apply {
                setPadding(0, dp(6), 0, dp(6))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setOnClickListener { Apps.launch(ctx, app) }
                setOnLongClickListener { favMenu(i); true }
            }
            favBox.addView(tv)
        }
        if (list.size < MAX_FAVS) {
            val add = label("+ add app", 16f, FAINT).apply {
                setPadding(0, dp(12), 0, dp(6))
                setOnClickListener {
                    pickApp("Add to home") { a -> setFavs((favs() + a).distinctBy { it.key }); renderFavs() }
                }
            }
            favBox.addView(add)
        }
    }

    private fun favMenu(i: Int) {
        val list = favs().toMutableList()
        if (i !in list.indices) return
        val app = list[i]
        dialog().setTitle(app.label)
            .setItems(arrayOf("Move up", "Move down", "Change app", "Remove from home", "App info")) { _, w ->
                when (w) {
                    0 -> if (i > 0) { list.add(i - 1, list.removeAt(i)); setFavs(list) }
                    1 -> if (i < list.size - 1) { list.add(i + 1, list.removeAt(i)); setFavs(list) }
                    2 -> { pickApp("Replace ${app.label}") { a -> list[i] = a; setFavs(list.distinctBy { it.key }); renderFavs() }; return@setItems }
                    3 -> { list.removeAt(i); setFavs(list) }
                    4 -> appInfo(app)
                }
                renderFavs()
            }
            .show()
    }

    private fun slot(key: String): AppInfo? {
        val v = prefs.getString(key, null) ?: return null
        return apps.find { it.key == v }
    }

    private fun saveSlot(key: String, app: AppInfo?) {
        val e = prefs.edit()
        if (app == null) e.remove(key) else e.putString(key, app.key)
        e.apply()
    }

    private fun renderDock() {
        dockBox.removeAllViews()
        for (i in 0 until 5) {
            val app = slot("dock$i")
            val cell = FrameLayout(ctx)
            if (app != null) {
                val iv = ImageView(ctx).apply {
                    setImageDrawable(Apps.icon(ctx, app)?.mutate()?.also { it.colorFilter = gray })
                    contentDescription = app.label
                    alpha = 0.9f
                }
                cell.addView(iv, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
            } else {
                cell.addView(
                    label("+", 22f, DIM, light),
                    FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER)
                )
            }
            cell.setOnClickListener {
                if (app != null) Apps.launch(ctx, app)
                else pickApp("Quick app ${i + 1}") { a -> saveSlot("dock$i", a); renderDock() }
            }
            cell.setOnLongClickListener {
                dialog().setTitle("Quick app ${i + 1}")
                    .setItems(arrayOf("Change app", "Remove")) { _, w ->
                        if (w == 0) pickApp("Quick app ${i + 1}") { a -> saveSlot("dock$i", a); renderDock() }
                        else { saveSlot("dock$i", null); renderDock() }
                    }
                    .show()
                true
            }
            dockBox.addView(cell, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
    }

    private fun appInfo(app: AppInfo) =
        safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")))

    // ---------------------------------------------------------------- drawer

    private fun buildDrawer(): LinearLayout {
        val d = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(32), dp(40), dp(32), 0)
            visibility = View.GONE
            isClickable = true
        }
        search = EditText(ctx).apply {
            hint = "search"
            setHintTextColor(DIM)
            setTextColor(FG)
            textSize = 20f
            typeface = light
            background = null
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(0, dp(8), 0, dp(8))
        }
        d.addView(search, LinearLayout.LayoutParams(MATCH, WRAP))
        d.addView(View(ctx).apply { setBackgroundColor(0xFF3A3A3E.toInt()) }, LinearLayout.LayoutParams(MATCH, dp(1)))

        drawerAdapter = DrawerAdapter()
        drawerList = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = drawerAdapter
            setPadding(0, dp(12), 0, dp(24))
            clipToPadding = false
        }
        d.addView(drawerList, weight())

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { filterDrawer() }
        })
        search.setOnEditorActionListener { _, _, _ ->
            drawerAdapter.items.firstOrNull()?.let { launchFromDrawer(it) }
            true
        }
        return d
    }

    private fun filterDrawer() {
        val q = search.text.toString().trim().lowercase()
        drawerAdapter.submit(if (q.isEmpty()) apps else apps.filter { it.label.lowercase().contains(q) })
    }

    private fun openDrawer() {
        if (drawer.visibility == View.VISIBLE) return
        search.setText("")
        filterDrawer()
        drawerList.scrollToPosition(0)
        drawer.visibility = View.VISIBLE
        drawer.translationY = window.decorView.height.toFloat()
        drawer.animate().translationY(0f).setDuration(220).start()
    }

    private fun closeDrawer() {
        if (drawer.visibility != View.VISIBLE) return
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(search.windowToken, 0)
        drawer.animate().translationY(window.decorView.height.toFloat()).setDuration(200)
            .withEndAction { drawer.visibility = View.GONE }
            .start()
    }

    private fun launchFromDrawer(app: AppInfo) {
        Apps.launch(ctx, app)
        closeDrawer()
    }

    private fun drawerMenu(app: AppInfo) {
        dialog().setTitle(app.label)
            .setItems(arrayOf("Add to home list", "Set as quick app", "App info", "Uninstall")) { _, w ->
                when (w) {
                    0 -> { setFavs((favs() + app).distinctBy { it.key }); renderFavs() }
                    1 -> dialog().setTitle("Replace which quick app?")
                        .setItems(Array(5) { i -> slot("dock$i")?.label ?: "Empty slot ${i + 1}" }) { _, s ->
                            saveSlot("dock$s", app); renderDock()
                        }.show()
                    2 -> appInfo(app)
                    3 -> safeStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
                }
            }
            .show()
    }

    inner class DrawerAdapter : RecyclerView.Adapter<DrawerAdapter.Holder>() {
        var items: List<AppInfo> = emptyList()
            private set

        fun submit(list: List<AppInfo>) {
            items = list
            notifyDataSetChanged()
        }

        inner class Holder(val row: LinearLayout, val letter: TextView, val name: TextView) :
            RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val letter = label("", 12f, DIM).apply { minWidth = dp(26) }
            val name = label("", 20f, FG, light).apply {
                setPadding(0, dp(9), 0, dp(9))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
                addView(letter)
                addView(name, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            return Holder(row, letter, name)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val app = items[position]
            val first = app.label.firstOrNull()?.uppercaseChar()
            val prev = if (position > 0) items[position - 1].label.firstOrNull()?.uppercaseChar() else null
            holder.letter.text = if (first != prev) first?.toString() ?: "" else ""
            holder.name.text = app.label
            holder.row.setOnClickListener { launchFromDrawer(app) }
            holder.row.setOnLongClickListener { drawerMenu(app); true }
        }

        override fun getItemCount() = items.size
    }

    // ---------------------------------------------------------------- memories (left)

    private fun buildMemories(): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(40), dp(32), dp(28))
        }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(label("TODAY'S MEMORY", 12f, DIM, medium).apply { letterSpacing = 0.15f }, LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(TextClock(ctx).apply {
            format12Hour = "d MMM"; format24Hour = "d MMM"; textSize = 12f; setTextColor(DIM)
        })
        col.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))

        val frame = FrameLayout(ctx).apply {
            background = rounded(WHEEL, 20, LINE)
            clipToOutline = true
        }
        memImage = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(memImage, FrameLayout.LayoutParams(MATCH, MATCH))
        memEmpty = label("Tap to add family photos", 15f, MUTED).apply { gravity = Gravity.CENTER }
        frame.addView(memEmpty, FrameLayout.LayoutParams(MATCH, MATCH))
        memAgo = label("", 12f, FG, medium).apply {
            background = rounded(BG, 14)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        frame.addView(memAgo, FrameLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(14), dp(14), 0, 0) })
        frame.setOnClickListener { if (memories().isEmpty()) addPhotos() }
        frame.setOnLongClickListener { memoryMenu(); true }
        col.addView(frame, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(20) })

        memCaption = label("", 20f, FG, light).apply {
            setPadding(0, dp(16), 0, 0)
            setOnClickListener { editCaption() }
        }
        col.addView(memCaption, LinearLayout.LayoutParams(MATCH, WRAP))

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        row.addView(roundButton("‹") { memOffset--; showMemory() })
        row.addView(label("+ add photos", 14f, MUTED).apply {
            gravity = Gravity.CENTER
            setOnClickListener { addPhotos() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(roundButton("›") { memOffset++; showMemory() })
        col.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        return col
    }

    private fun roundButton(symbol: String, onClick: () -> Unit): View =
        label(symbol, 24f, WHEEL_ICON, light).apply {
            gravity = Gravity.CENTER
            background = oval(BG, LINE)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { onClick() }
        }

    private fun memories(): List<String> =
        (prefs.getString("memories", "") ?: "").split("\n").filter { it.isNotBlank() }

    private fun currentMemory(): String? {
        val list = memories()
        if (list.isEmpty()) return null
        val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        val n = list.size
        return list[(((day + memOffset) % n) + n) % n]
    }

    private fun showMemory() {
        val list = memories()
        val current = currentMemory()
        if (current == null) {
            memImage.setImageDrawable(null)
            memEmpty.text = "Tap to add family photos"
            memEmpty.visibility = View.VISIBLE
            memAgo.visibility = View.GONE
            memCaption.text = "Your family, one photo a day."
            return
        }
        memEmpty.visibility = View.GONE
        val uri = Uri.parse(current)
        val bmp = decode(uri)
        if (bmp == null) {
            memImage.setImageDrawable(null)
            memEmpty.text = "Photo unavailable — long-press to remove"
            memEmpty.visibility = View.VISIBLE
        } else {
            memImage.setImageBitmap(bmp)
        }
        memAgo.text = takenAgo(uri) ?: "${list.indexOf(current) + 1} of ${list.size}"
        memAgo.visibility = View.VISIBLE
        memCaption.text = prefs.getString("cap_$current", null) ?: "Tap to add a caption"
    }

    private fun addPhotos() {
        pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun memoryMenu() {
        val current = currentMemory() ?: return addPhotos()
        dialog().setItems(arrayOf("Edit caption", "Remove this photo", "Add photos")) { _, w ->
            when (w) {
                0 -> editCaption()
                1 -> {
                    prefs.edit()
                        .putString("memories", memories().filter { it != current }.joinToString("\n"))
                        .remove("cap_$current")
                        .apply()
                    try {
                        contentResolver.releasePersistableUriPermission(
                            Uri.parse(current), Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {
                    }
                    showMemory()
                }
                2 -> addPhotos()
            }
        }.show()
    }

    private fun editCaption() {
        val current = currentMemory() ?: return
        val input = EditText(ctx).apply {
            setText(prefs.getString("cap_$current", ""))
            hint = "Who, what, where"
        }
        dialog().setTitle("Caption")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("cap_$current", input.text.toString().trim()).apply()
                showMemory()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun decode(uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val target = resources.displayMetrics.widthPixels
        while (bounds.outWidth / (sample * 2) >= target) sample *= 2
        var bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        val rotation = try {
            contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        } catch (_: Exception) {
            0
        }
        val b = bmp
        if (b != null && rotation != 0) {
            bmp = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        }
        bmp
    } catch (_: Exception) {
        null
    }

    private fun takenAgo(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf("datetaken"), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) {
                val years = ((System.currentTimeMillis() - c.getLong(0)) / 31_557_600_000L).toInt()
                when {
                    years >= 2 -> "$years years ago"
                    years == 1 -> "1 year ago"
                    else -> "This year"
                }
            } else null
        }
    } catch (_: Exception) {
        null
    }

    // ---------------------------------------------------------------- music (right)

    private fun buildMusic(): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(40), dp(32), dp(28))
        }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(label("YT MUSIC", 12f, DIM, medium).apply { letterSpacing = 0.15f }, LinearLayout.LayoutParams(0, WRAP, 1f))
        mStatus = label("", 12f, DIM)
        head.addView(mStatus)
        col.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))

        val screen = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(SCREEN, 18)
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(label("Now Playing", 12f, INK, medium), LinearLayout.LayoutParams(0, WRAP, 1f))
        mState = label("", 12f, INK, medium)
        bar.addView(mState)
        screen.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        screen.addView(View(ctx).apply { setBackgroundColor(SCREEN_LINE) },
            LinearLayout.LayoutParams(MATCH, dp(1)).apply { topMargin = dp(8) })

        mTitle = label("", 20f, INK, medium).apply {
            setPadding(0, dp(16), 0, dp(4)); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        mArtist = label("", 14f, INK2).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        screen.addView(mTitle)
        screen.addView(mArtist)
        screen.addView(Space(ctx), weight())

        mProgress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 1000
            progressTintList = ColorStateList.valueOf(INK)
            progressBackgroundTintList = ColorStateList.valueOf(SCREEN_LINE)
        }
        screen.addView(mProgress, LinearLayout.LayoutParams(MATCH, dp(8)))
        val times = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        mElapsed = label("", 12f, INK2)
        mRemain = label("", 12f, INK2)
        times.addView(mElapsed, LinearLayout.LayoutParams(0, WRAP, 1f))
        times.addView(mRemain)
        screen.addView(times, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        screen.setOnClickListener {
            if (!hasNotifAccess()) safeStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        col.addView(screen, LinearLayout.LayoutParams(MATCH, dp(250)).apply { topMargin = dp(24) })

        col.addView(Space(ctx), weight())
        col.addView(buildWheel(), LinearLayout.LayoutParams(dp(250), dp(250)))
        col.addView(Space(ctx), weight())
        updateMusic()
        return col
    }

    private fun buildWheel(): View {
        val wheel = FrameLayout(ctx).apply { background = oval(WHEEL, LINE) }

        fun click(v: View, action: () -> Unit) {
            v.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                action()
            }
        }
        fun icon(res: Int, desc: String) = ImageView(ctx).apply {
            setImageResource(res)
            setColorFilter(WHEEL_ICON)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = desc
        }

        val menu = label("MENU", 13f, WHEEL_ICON, medium).apply {
            letterSpacing = 0.15f
            gravity = Gravity.CENTER
            contentDescription = "Open YT Music"
        }
        click(menu) { openYtMusic() }
        wheel.addView(menu, FrameLayout.LayoutParams(dp(100), dp(56), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(6) })

        val prev = icon(R.drawable.ic_prev, "Previous")
        click(prev) { media(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
        wheel.addView(prev, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = dp(6) })

        val next = icon(R.drawable.ic_next, "Next")
        click(next) { media(KeyEvent.KEYCODE_MEDIA_NEXT) }
        wheel.addView(next, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER_VERTICAL or Gravity.END).apply { marginEnd = dp(6) })

        val play = icon(R.drawable.ic_playpause, "Play or pause")
        click(play) { media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
        wheel.addView(play, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(6) })

        val center = View(ctx).apply {
            background = oval(BG, LINE)
            contentDescription = "Play or pause"
        }
        click(center) { media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
        wheel.addView(center, FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER))
        return wheel
    }

    private fun openYtMusic() {
        val launch = packageManager.getLaunchIntentForPackage(YTM)
        if (launch != null) {
            safeStart(launch)
        } else {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$YTM")))
            } catch (_: Exception) {
                safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$YTM")))
            }
        }
    }

    private fun media(code: Int) {
        val c = controller
        if (c != null) {
            val t = c.transportControls
            when (code) {
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> t.skipToPrevious()
                KeyEvent.KEYCODE_MEDIA_NEXT -> t.skipToNext()
                else -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            }
            return
        }
        // No session info: send a normal media button press (goes to the last music app).
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun hasNotifAccess() =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(packageName)

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> pickController(list) }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = updateMusic()
        override fun onPlaybackStateChanged(state: PlaybackState?) = updateMusic()
        override fun onSessionDestroyed() = connectMedia()
    }

    private fun connectMedia() {
        if (!hasNotifAccess()) {
            controller = null
            updateMusic()
            return
        }
        val msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val cn = ComponentName(ctx, MediaListener::class.java)
        try {
            if (!listeningSessions) {
                msm.addOnActiveSessionsChangedListener(sessionsListener, cn)
                listeningSessions = true
            }
            pickController(msm.getActiveSessions(cn))
        } catch (_: SecurityException) {
            controller = null
            updateMusic()
        }
    }

    private fun pickController(list: List<MediaController>?) {
        controller?.unregisterCallback(controllerCallback)
        controller = list?.firstOrNull { it.packageName == YTM } ?: list?.firstOrNull()
        controller?.registerCallback(controllerCallback, handler)
        updateMusic()
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (controller != null) updateMusic()
            handler.postDelayed(this, 1000)
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun updateMusic() {
        if (!::mTitle.isInitialized) return
        if (!hasNotifAccess()) {
            mTitle.text = "Show what's playing"
            mArtist.text = "Tap here and allow Minimal Launcher"
            mState.text = ""; mStatus.text = ""
            mProgress.progress = 0; mElapsed.text = ""; mRemain.text = ""
            return
        }
        val c = controller
        val m = c?.metadata
        if (c == null || m == null) {
            mTitle.text = "Nothing playing"
            mArtist.text = "Press MENU to open YT Music"
            mState.text = "❚❚"; mStatus.text = ""
            mProgress.progress = 0; mElapsed.text = "0:00"; mRemain.text = "-0:00"
            return
        }
        mTitle.text = m.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown song"
        mArtist.text = m.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        mStatus.text = if (c.packageName == YTM) "" else appLabel(c.packageName)

        val st = c.playbackState
        val playing = st?.state == PlaybackState.STATE_PLAYING
        mState.text = if (playing) "▶" else "❚❚"
        val duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION)
        var pos = st?.position ?: 0L
        if (playing && st != null) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        if (duration > 0) {
            pos = pos.coerceIn(0L, duration)
            mProgress.progress = (pos * 1000 / duration).toInt()
            mElapsed.text = fmt(pos)
            mRemain.text = "-" + fmt(duration - pos)
        } else {
            mProgress.progress = 0
            mElapsed.text = fmt(pos.coerceAtLeast(0L))
            mRemain.text = ""
        }
    }
}

/** Shows the three prebuilt screens (memories, home, music) side by side. */
class PagesAdapter(private val pages: List<View>) : RecyclerView.Adapter<PagesAdapter.Holder>() {
    class Holder(v: View) : RecyclerView.ViewHolder(v)

    override fun getItemViewType(position: Int) = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = pages[viewType]
        (v.parent as? ViewGroup)?.removeView(v)
        v.layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {}

    override fun getItemCount() = pages.size
}
