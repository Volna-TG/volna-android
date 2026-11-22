package app.volna

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.*
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.view.animation.PathInterpolator
import android.widget.*
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val easing = PathInterpolator(0.2f, 0f, 0f, 1f)
    private lateinit var screen: FrameLayout
    private var screenAnimation: ValueAnimator? = null
    private lateinit var pageScroll: ScrollView
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var font: Typeface
    private var page = 0
    private var visible = false
    private var openingTelegram = false
    private var lastState = ""
    private var dark = false
    private var background = 0
    private var surface = 0
    private var ink = 0
    private var muted = 0
    private var line = 0
    private var accent = 0
    private var tint = 0
    private var stateText: TextView? = null
    private var supportingText: TextView? = null
    private var statusChip: TextView? = null
    private var actionLabel: TextView? = null
    private var actionButton: FrameLayout? = null
    private var powerIcon: ImageView? = null
    private var errorAction: TextView? = null
    private var selectedRoute: TextView? = null
    private var telegramButton: LinearLayout? = null
    private val connectionChanged = Runnable { handler.post { if (visible) updateConnection() } }
    private var activeDialog: Dialog? = null
    private var notificationPromptHandled = false
    private var openUpdateFromNotice = false
    private var updateWave: ValueAnimator? = null
    private var brandTaps = 0
    private var lastBrandTap = 0L

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        openUpdateFromNotice = intent.action == UpdateCheckJob.OPEN_UPDATE || state?.getBoolean("open_update") == true
        if (intent.action == UpdateCheckJob.OPEN_UPDATE) intent.action = null
        UpdateCheckJob.schedule(this)
        font = Typeface.create("sans-serif", Typeface.NORMAL)
        page = state?.getInt("page", 0) ?: 0
        notificationPromptHandled = state?.getBoolean("notification_prompt_handled") ?: false
        Prefs.get(this).edit().remove("vless_profile").remove("builtin_route").apply()
        java.io.File(noBackupFilesDir, "xray-runtime.json").delete()
        Prefs.secret(this)
        screen = FrameLayout(this)
        setContentView(screen)
        applyPalette()
        render()
        if (Prefs.get(this).getBoolean("open_start", false) && LinkService.live == null) startConnection()
    }

    override fun onResume() {
        super.onResume()
        visible = true
        LinkService.addListener(connectionChanged)
        updateConnection()
        syncCatalog(false)
        if (!Prefs.get(this).getBoolean("support_shown", false)) showSupport()
        else if (activeDialog == null) maybeRequestNotifications()
        if (pendingInstall) {
            pendingInstall = false
            if (packageManager.canRequestPackageInstalls() && Updates.apk(this).isFile) offerInstall()
        } else if (openUpdateFromNotice) {
            showRequestedUpdate()
        } else if (activeDialog == null) {
            autoCheckUpdates()
        }
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        if (newIntent.action == UpdateCheckJob.OPEN_UPDATE) {
            openUpdateFromNotice = true
            if (visible) showRequestedUpdate()
        }
    }

    private fun showRequestedUpdate() {
        if (updateBusy) return
        openUpdateFromNotice = false
        UpdateCheckJob.clearNotification(this)
        checkUpdates(true)
    }

    private fun maybeRequestNotifications() {
        if (!visible || isFinishing || isDestroyed || notificationPromptHandled) return
        notificationPromptHandled = true
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
    }

    override fun onPause() {
        visible = false
        updateWave?.cancel()
        brandTaps = 0
        LinkService.removeListener(connectionChanged)
        super.onPause()
    }

    override fun onDestroy() {
        openingTelegram = false
        updateWorker.shutdownNow()
        LinkService.removeListener(connectionChanged)
        screenAnimation?.cancel()
        activeDialog?.setOnDismissListener(null)
        activeDialog?.dismiss()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putInt("page", page)
        out.putBoolean("open_update", openUpdateFromNotice)
        out.putBoolean("notification_prompt_handled", notificationPromptHandled)
        super.onSaveInstanceState(out)
    }

    @Deprecated("Compatibility with Android 8–12")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (page != 0) navigate(0)
        else super.onBackPressed()
    }

    private fun dp(value: Float) = (value * resources.displayMetrics.density).roundToInt()
    private fun dp(value: Int) = dp(value.toFloat())
    private fun lp(width: Int = -1, height: Int = -2) = LinearLayout.LayoutParams(width, height)
    private fun color(value: String) = Color.parseColor(value)
    @Suppress("DEPRECATION")
    private fun applyPalette() {
        val theme = Prefs.get(this).getString("appearance", "system")
        dark = theme == "dark" || (theme == "system" && (resources.configuration.uiMode and 0x30) == 0x20)
        background = color(if (dark) "#0D1016" else "#F7F8FA")
        surface = color(if (dark) "#171C25" else "#FFFFFF")
        ink = color(if (dark) "#F1F4F9" else "#152033")
        muted = color(if (dark) "#9BA8BA" else "#65758B")
        line = color(if (dark) "#2A3342" else "#E5EAF1")
        accent = color(if (dark) "#8CB5FF" else "#285CE8")
        tint = color(if (dark) "#243654" else "#EAF0FF")
        window.statusBarColor = background
        window.navigationBarColor = surface
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (dark) 0 else mask, mask)
        } else {
            window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }
    private fun shape(fill: Int, radius: Int = 18, stroke: Int = 0) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat(); if (stroke != 0) setStroke(dp(1), stroke)
    }
    private fun ripple(fill: Int, radius: Int = 18, stroke: Int = 0) = RippleDrawable(ColorStateList.valueOf(if (dark) 0x228CB5FF else 0x18285CE8), shape(fill, radius, stroke), shape(Color.WHITE, radius))
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun text(value: String, size: Float = 15f, textColor: Int = ink, weight: Int = 400) = TextView(this).apply {
        this.text = value; setTextSize(size); setTextColor(textColor); typeface = font
        setFontVariationSettings("'wght' $weight"); includeFontPadding = false
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun add(parent: LinearLayout, child: View, params: LinearLayout.LayoutParams = lp()) { parent.addView(child, params) }
    private fun space(parent: LinearLayout, height: Int) { add(parent, View(this), lp(1, dp(height))) }
    private fun icon(resource: Int, color: Int = muted, size: Int = 22) = ImageView(this).apply {
        setImageResource(resource); imageTintList = ColorStateList.valueOf(color); scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = lp(dp(size), dp(size))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private fun motionEnabled() = Prefs.get(this).getBoolean("motion", true) && ValueAnimator.areAnimatorsEnabled()
    private fun respond(view: View, action: () -> Unit) {
        view.isClickable = true; view.isFocusable = true
        view.setOnClickListener {
            if (motionEnabled()) view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(70).withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(easing).start()
            }.start()
            action()
        }
    }
    private fun iconButton(resource: Int, label: String, action: () -> Unit) = FrameLayout(this).apply {
        layoutParams = lp(dp(48), dp(48)); background = ripple(surface, 16)
        contentDescription = label; addView(icon(resource, ink), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        respond(this, action)
    }

    private fun navigate(destination: Int) {
        if (destination == page) return
        brandTaps = 0
        val direction = if (destination > page) 1 else -1
        page = destination
        render(direction = direction)
    }

    private fun onBrandTap() {
        if (!visible || activeDialog != null) return
        val now = SystemClock.uptimeMillis()
        if (now - lastBrandTap > 350L) brandTaps = 0
        lastBrandTap = now
        brandTaps++
        if (brandTaps == 5) {
            brandTaps = 0
            val animated = motionEnabled()
            val options = android.app.ActivityOptions.makeCustomAnimation(this,
                if (animated) android.R.anim.fade_in else 0, if (animated) android.R.anim.fade_out else 0)
            startActivity(Intent(this, WaveEasterEggActivity::class.java), options.toBundle())
        }
    }

    private fun render(direction: Int = 0, revealTheme: Boolean = false, scrollY: Int = 0) {
        screenAnimation?.cancel()
        val previous = screen.getChildAt(0)
        lastState = ""
        stateText = null
        statusChip = null
        supportingText = null
        actionButton = null
        selectedRoute = null
        root = column().apply { setBackgroundColor(this@MainActivity.background) }
        val header = row().apply { setPadding(dp(24), dp(12), dp(20), dp(6)) }
        val brand = row()
        brand.addView(icon(R.drawable.ic_wave, accent, 28))
        val brandName = text("Волна", 26f, ink, 650)
        val brandLp = lp(-2, -2).apply { leftMargin = dp(10) }; add(brand, brandName, brandLp)
        brand.contentDescription = "Волна"
        brandName.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        brand.isFocusable = true
        brand.isSoundEffectsEnabled = false
        brand.setOnClickListener { onBrandTap() }
        add(header, brand, lp(-2, dp(56)))
        add(header, View(this), lp(0, 1).apply { weight = 1f })
        val headerAction = iconButton(if (page == 0) R.drawable.ic_settings_2 else R.drawable.ic_x, if (page == 0) "Настройки" else "Закрыть настройки") { navigate(1 - page) }
        add(header, headerAction, lp(dp(48), dp(48)))
        add(root, header)
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; isFillViewport = true; clipToPadding = true }
        content = column().apply { setPadding(dp(24), dp(10), dp(24), dp(28)) }
        pageScroll = scroll
        scroll.addView(content)
        add(root, scroll, lp(-1, 0).apply { weight = 1f })
        if (page == 0) connectionPage() else settingsPage()
        val bottom = row().apply { setBackgroundColor(surface); setPadding(dp(16), dp(10), dp(16), dp(12)) }
        for (index in 0..1) {
            val item = column().apply {
                gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)); background = ripple(if (index == page) tint else surface, 16)
                contentDescription = if (index == 0) "Подключение" else "Настройки"
                id = 3000 + index
            }
            add(item, icon(if (index == 0) R.drawable.ic_link else R.drawable.ic_sliders_horizontal, if (index == page) accent else muted, 22), lp(dp(22), dp(22)))
            space(item, 5); add(item, text(if (index == 0) "Подключение" else "Настройки", 12f, if (index == page) accent else muted, if (index == page) 600 else 450), lp(-2, -2))
            item.isSelected = index == page
            respond(item) { navigate(index) }
            add(bottom, item, lp(0, dp(62)).apply { weight = 1f; if (index == 0) rightMargin = dp(10) })
        }
        add(root, View(this).apply { setBackgroundColor(line) }, lp(-1, dp(1)))
        add(root, bottom)
        updateConnection()
        val next = root
        if (scrollY > 0) scroll.post { scroll.scrollTo(0, scrollY) }
        if (previous == null || !motionEnabled() || (direction == 0 && !revealTheme)) {
            screen.removeAllViews()
            screen.addView(next)
            return
        }
        previous.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        screen.addView(next)
        val distance = dp(24) * direction.toFloat()
        val revealHeight = screen.height
        val width = screen.width
        if (revealTheme) next.clipBounds = android.graphics.Rect(0, revealHeight, width, revealHeight)
        else {
            next.alpha = 0f
            next.translationX = distance
        }
        screenAnimation = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (revealTheme) 380L else 270L
            interpolator = easing
            addUpdateListener {
                val progress = it.animatedValue as Float
                if (revealTheme) {
                    val top = ((1f - progress) * revealHeight).roundToInt()
                    next.clipBounds = android.graphics.Rect(0, top, width, revealHeight)
                } else {
                    next.alpha = progress
                    next.translationX = (1f - progress) * distance
                    previous.translationX = -distance * progress
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    next.clipBounds = null
                    next.alpha = 1f
                    next.translationX = 0f
                    screen.removeView(previous)
                    screenAnimation = null
                }
            })
            start()
        }
    }

    private fun refreshPage() = render(scrollY = pageScroll.scrollY)

    private fun connectionPage() {
        val hero = column().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(10), 0, dp(10)) }
        statusChip = text("Не подключено", 12f, muted, 550).apply {
            gravity = Gravity.CENTER; setPadding(dp(14), dp(7), dp(14), dp(7)); background = shape(surface, 40)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        add(hero, statusChip!!, lp(-2, -2)); space(hero, 18)
        stateText = text("Готово к работе", 24f, ink, 650).apply { gravity = Gravity.CENTER; maxWidth = dp(310) }
        add(hero, stateText!!); space(hero, 12)
        supportingText = text("Включите соединение, затем откройте Telegram.", 14f, muted).apply { gravity = Gravity.CENTER; maxWidth = dp(300) }
        add(hero, supportingText!!); space(hero, 24)
        actionButton = FrameLayout(this).apply {
            id = 1001; background = ripple(tint, 40, if (dark) line else color("#D8E3FF")); elevation = dp(1).toFloat()
            contentDescription = "Подключиться"
        }
        powerIcon = icon(R.drawable.ic_power, accent, 48)
        actionButton!!.addView(powerIcon, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        respond(actionButton!!) { if (LinkService.live == null) startConnection() else stopConnection() }
        add(hero, actionButton!!, lp(dp(112), dp(112)))
        space(hero, 12); actionLabel = text("Подключиться", 15f, accent, 650).apply { gravity = Gravity.CENTER }
        add(hero, actionLabel!!); space(hero, 10)
        errorAction = text("Переподключиться", 14f, accent, 550).apply {
            gravity = Gravity.CENTER; setPadding(dp(16), dp(12), dp(16), dp(12)); background = ripple(this@MainActivity.background, 12); visibility = View.GONE
            respond(this) { startConnection(true) }
        }
        add(hero, errorAction!!, lp(-2, -2))
        add(content, hero)
        val route = row().apply { setPadding(dp(16), dp(16), dp(12), dp(16)); background = ripple(surface, 20, line) }
        route.addView(icon(R.drawable.ic_wifi, accent))
        val routeTexts = column(); add(routeTexts, text("Профиль", 12f, muted)); space(routeTexts, 4)
        selectedRoute = text(routeLabel(), 15f, ink, 550); add(routeTexts, selectedRoute!!)
        add(route, routeTexts, lp(0, -2).apply { weight = 1f; leftMargin = dp(12) })
        route.addView(icon(R.drawable.ic_chevron_right, muted, 20)); respond(route) { chooseRoute() }; add(content, route)
        space(content, 12)
        telegramButton = row().apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(17), dp(16), dp(17)); background = ripple(if (dark) color("#477DEB") else color("#285CE8"), 18); id = 1002; contentDescription = "Открыть Telegram" }
        telegramButton!!.addView(icon(R.drawable.ic_send, Color.WHITE, 20))
        add(telegramButton!!, text("Открыть Telegram", 15f, Color.WHITE, 600), lp(-2, -2).apply { leftMargin = dp(10) })
        respond(telegramButton!!) { openTelegram() }; add(content, telegramButton!!, lp(-1, dp(58)))
        space(content, 16)
        add(content, text("Telegram проекта", 13f, accent, 550).apply {
            gravity = Gravity.CENTER; setPadding(dp(10), dp(10), dp(10), dp(10))
            background = ripple(this@MainActivity.background, 12); respond(this) { openProjectTelegram() }
        })
    }

    private fun routeLabel() = Profiles.selected(this)?.name ?: "Добавить профиль"

    private fun dialogStyle() = if (dark) R.style.VolnaDialogDark else R.style.VolnaDialog

    private data class ModalAction(val label: String, val run: () -> Unit)

    private fun modal(
        title: String,
        message: String? = null,
        primary: ModalAction? = null,
        secondary: ModalAction? = null,
        dismissible: Boolean = true,
        onDismiss: (() -> Unit)? = null,
        body: (LinearLayout, Dialog) -> Unit = { _, _ -> }
    ): Dialog {
        activeDialog?.dismiss()
        val modal = Dialog(this, dialogStyle())
        val panel = column().apply {
            background = shape(surface, 24, line)
            clipToOutline = true
            setPadding(dp(18), dp(12), dp(18), dp(16))
            isFocusableInTouchMode = true
            if (Build.VERSION.SDK_INT >= 28) accessibilityPaneTitle = title
        }
        val header = row()
        val heading = text(title, 22f, ink, 650).apply {
            setPadding(dp(4), dp(10), dp(8), dp(10))
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
        add(header, heading, lp(0, -2).apply { weight = 1f })
        if (dismissible) add(header, iconButton(R.drawable.ic_x, "Закрыть диалог") { modal.dismiss() }, lp(dp(48), dp(48)))
        add(panel, header)

        val contents = column().apply { setPadding(dp(4), dp(4), dp(4), 0) }
        if (message != null) add(contents, text(message, 14f, muted))
        body(contents, modal)
        val scroller = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(contents)
        }
        add(panel, scroller, lp(-1, -2).apply { weight = 1f })
        if (secondary != null || primary != null) {
            space(panel, 14)
            for (action in listOfNotNull(primary, secondary)) {
                if (action === secondary && primary != null) space(panel, 6)
                add(panel, modalButton(action.label, action === primary) {
                    modal.dismiss()
                    action.run()
                })
            }
        }
        modal.setContentView(panel)
        modal.setCancelable(dismissible)
        modal.setCanceledOnTouchOutside(dismissible)
        modal.setOnDismissListener {
            if (activeDialog === modal) activeDialog = null
            onDismiss?.invoke()
        }
        activeDialog = modal
        modal.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(if (dark) 0.65f else 0.43f)
            setWindowAnimations(if (motionEnabled()) R.style.VolnaDialogMotion else 0)
            @Suppress("DEPRECATION")
            val keyboardMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            setSoftInputMode(keyboardMode)
        }
        modal.show()
        modal.window?.setLayout(
            minOf(dp(440), resources.displayMetrics.widthPixels - dp(32)),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        panel.requestFocus()
        return modal
    }

    private fun modalButton(label: String, primary: Boolean = false, run: () -> Unit) =
        text(label, 14f, if (primary) Color.WHITE else accent, 600).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = ripple(if (primary) color("#285CE8") else tint, 14)
            respond(this, run)
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                }
            }
        }

    private fun choices(
        title: String,
        labels: List<String>,
        selected: Int? = null,
        primary: ModalAction? = null,
        secondary: ModalAction? = null,
        onChoice: (Int) -> Unit
    ) {
        modal(title, primary = primary, secondary = secondary) { body, dialog ->
            labels.forEachIndexed { index, label ->
                if (index > 0) space(body, 6)
                val checked = index == selected
                val option = row().apply {
                    minimumHeight = dp(52)
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    background = ripple(if (checked) tint else surface, 14, if (checked) 0 else line)
                    contentDescription = label
                    accessibilityDelegate = object : View.AccessibilityDelegate() {
                        override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.className = if (selected == null) Button::class.java.name else RadioButton::class.java.name
                            info.isCheckable = selected != null
                            info.isChecked = checked
                        }
                    }
                }
                add(option, text(label, 15f, if (checked) accent else ink, if (checked) 600 else 450).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, lp(0, -2).apply { weight = 1f })
                if (selected == null || checked)
                    option.addView(icon(if (checked) R.drawable.ic_check else R.drawable.ic_chevron_right, if (checked) accent else muted, 20))
                respond(option) { dialog.dismiss(); onChoice(index) }
                add(body, option)
            }
        }
    }

    private fun chooseRoute() {
        val entries = Profiles.list(this)
        val active = Profiles.selected(this)
        val imported = entries.filter { !it.managed() }
        choices(
            "Профили", entries.map { it.name }, entries.indexOfFirst { it.id == active?.id },
            primary = ModalAction("Добавить профиль", ::importProfiles),
            secondary = if (imported.isEmpty()) null else ModalAction("Управление профилями") { manageProfiles(imported) }
        ) { index ->
            if (entries[index].id != active?.id) {
                Profiles.select(this, entries[index].id)
                if (LinkService.live != null) startConnection(true)
                refreshPage()
            }
        }
    }

    private fun quickConnectProfiles() {
        val profiles = Profiles.list(this).filter { it.link.isNotEmpty() }
        if (profiles.isEmpty()) {
            importProfiles()
            return
        }
        choices("Быстрое подключение", profiles.map { it.name },
            primary = ModalAction("Добавить профиль", ::importProfiles)) { index ->
            val profile = profiles[index]
            val restart = LinkService.live != null && Profiles.selected(this).id != profile.id
            Profiles.select(this, profile.id)
            if (page != 0) navigate(0) else refreshPage()
            startConnection(restart)
        }
    }

    private fun manageProfiles(entries: List<Profiles.Entry>) {
        choices("Управление профилями", entries.map { it.name }, secondary = ModalAction("Назад", ::chooseRoute)) { index ->
            val entry = entries[index]
            val subscribed = entry.source.isNotEmpty()
            val actions = if (subscribed) listOf("Обновить подписку", "Удалить подписку") else listOf("Удалить профиль")
            choices(entry.name, actions, secondary = ModalAction("Назад") { manageProfiles(entries) }) { action ->
                if (subscribed && action == 0) loadProfiles(entry.source)
                else modal(
                    actions[action] + "?",
                    message = if (subscribed) "Будут удалены все профили из этой подписки." else null,
                    primary = ModalAction("Удалить") {
                        val previous = Profiles.selected(this)?.let { it.id + it.link }
                        try {
                            Profiles.remove(this, entry)
                            profilesChanged(previous)
                        } catch (_: Exception) { toast("Не удалось удалить профиль") }
                    },
                    secondary = ModalAction("Отмена") { manageProfiles(entries) }
                )
            }
        }
    }

    private fun importProfiles() {
        val field = EditText(ContextThemeWrapper(this, dialogStyle())).apply {
            hint = "https://… или vless://…"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            maxLines = 5
            minLines = 2
            setTextColor(ink)
            setHintTextColor(muted)
            setTextSize(15f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = shape(this@MainActivity.background, 12, line)
        }
        modal(
            "Импорт профилей", "Вставьте HTTPS-ссылку подписки или ссылки VLESS или Hysteria 2.",
            primary = ModalAction("Импортировать") { loadProfiles(field.text.toString()) },
            secondary = ModalAction("Отмена", ::chooseRoute)
        ) { body, _ ->
            space(body, 14)
            add(body, field)
        }
    }

    private var importingProfiles = false

    private fun loadProfiles(input: String) {
        if (importingProfiles) return
        importingProfiles = true
        val previous = Profiles.selected(this)?.let { it.id + it.link }
        val progress = modal("Загружаем профили…", dismissible = false) { body, _ ->
            if (motionEnabled()) add(body, ProgressBar(this).apply {
                indeterminateTintList = ColorStateList.valueOf(accent)
                contentDescription = "Идёт загрузка"
            }, lp(dp(28), dp(28)))
        }
        val context = applicationContext
        Thread({
            val result = runCatching { Profiles.importInput(context, input) }
            runOnUiThread {
                importingProfiles = false
                if (!isDestroyed && !isFinishing) {
                    progress.dismiss()
                    result.onSuccess {
                        profilesChanged(previous)
                        toast("Профилей в импорте: $it")
                    }.onFailure {
                        toast(if (it is IllegalArgumentException) it.message ?: "Проверьте ссылку" else "Не удалось загрузить или сохранить подписку")
                    }
                }
            }
        }, "volna-profile-import").start()
    }

    private fun profilesChanged(previous: String?) {
        val current = Profiles.selected(this)?.let { it.id + it.link }
        if (LinkService.live != null && previous != current) {
            if (current == null) stopConnection() else startConnection(true)
        }
        refreshPage()
    }

    private fun section(title: String): LinearLayout {
        space(content, 22); add(content, text(title, 13f, muted, 550).apply { setPadding(dp(2), 0, 0, dp(10)) })
        return column().apply { background = shape(surface, 20, line); clipToOutline = true; add(content, this) }
    }
    private fun settingRow(parent: LinearLayout, resource: Int, title: String, caption: String? = null, action: (() -> Unit)? = null): LinearLayout {
        val item = row().apply { setPadding(dp(16), dp(16), dp(16), dp(16)); if (action != null) background = ripple(surface, 16) }
        item.addView(icon(resource, muted, 22))
        val words = column(); add(words, text(title, 15f, ink, 500))
        if (caption != null) { space(words, 5); add(words, text(caption, 12f, muted)) }
        add(item, words, lp(0, -2).apply { weight = 1f; leftMargin = dp(14) })
        if (action != null) { item.addView(icon(R.drawable.ic_chevron_right, muted, 18)); respond(item, action) }
        add(parent, item); return item
    }
    private fun divider(parent: LinearLayout) = add(parent, View(this).apply { setBackgroundColor(line) }, lp(-1, dp(1)).apply { leftMargin = dp(52); rightMargin = dp(16) })
    private fun toggle(parent: LinearLayout, resource: Int, title: String, hint: String, key: String, default: Boolean) {
        val item = settingRow(parent, resource, title, hint)
        val switch = Switch(this).apply {
            isChecked = Prefs.get(this@MainActivity).getBoolean(key, default); contentDescription = title
            buttonTintListCompat()
            setOnCheckedChangeListener { _, checked -> Prefs.get(this@MainActivity).edit().putBoolean(key, checked).apply() }
        }
        item.addView(switch)
    }
    private fun Switch.buttonTintListCompat() {
        thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(accent, muted))
        trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(tint, line))
    }
    private val updateWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var updateBusy = false
    private var pendingInstall = false

    private var catalogBusy = false

    private fun syncCatalog(manual: Boolean) {
        val app = applicationContext
        val prefs = Prefs.get(app)
        val now = System.currentTimeMillis()
        val next = prefs.getLong("catalog_next", 0)
        if (catalogBusy || (!manual && next - now in 1..43_200_000L)) return
        catalogBusy = true
        updateWorker.execute {
            val result = runCatching { ProfileCatalog.refresh(app) }
            val delay = if (result.isSuccess) 43_200_000L else 900_000L
            prefs.edit().putLong("catalog_next", System.currentTimeMillis() + delay).apply()
            runOnUiThread {
                catalogBusy = false
                if (!visible || isFinishing || isDestroyed) return@runOnUiThread
                if (result.getOrDefault(false)) refreshPage()
                if (manual) toast(result.fold(
                    { if (it) "Профили обновлены" else "Установлены актуальные профили" },
                    { "Каталог недоступен. Сохранённые профили доступны." }))
            }
        }
    }

    private fun chooseWebsite() {
        val field = EditText(ContextThemeWrapper(this, dialogStyle())).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setText(Updates.site(this@MainActivity))
            selectAll()
        }
        modal("Адрес сайта", "Адрес для получения профилей и обновлений приложения.",
            primary = ModalAction("Сохранить") {
                try {
                    Updates.changeSite(this, field.text.toString())
                    refreshPage()
                    syncCatalog(true)
                } catch (_: Exception) { toast("Введите HTTPS-адрес сайта без пути") }
            },
            secondary = ModalAction("Вернуть исходный адрес") {
                Updates.changeSite(this, "")
                refreshPage()
                syncCatalog(true)
            }) { body, _ -> add(body, field) }
    }

    private fun autoCheckUpdates() {
        val prefs = Prefs.get(this)
        val now = System.currentTimeMillis()
        val last = prefs.getLong("update_last_check", 0)
        if (now >= last && now - last < 12 * 60 * 60 * 1000L) return
        prefs.edit().putLong("update_last_check", now).apply()
        checkUpdates(false)
    }

    private fun checkUpdates(manual: Boolean) {
        if (updateBusy) {
            if (manual) toast("Проверка уже идёт")
            return
        }
        updateBusy = true
        val checking = if (manual) modal("Проверка обновлений", dismissible = false) { body, _ ->
            val indicator = ProgressBar(this).apply { indeterminateTintList = ColorStateList.valueOf(accent) }
            add(body, indicator, lp(dp(30), dp(30)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            space(body, 14)
            add(body, text("Проверяем версию на сайте «Волны»…", 14f, muted))
        } else null
        val app = applicationContext
        updateWorker.execute {
            val result = runCatching { Updates.check(app) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                updateBusy = false
                checking?.dismiss()
                if (!visible) return@runOnUiThread
                if (openUpdateFromNotice) {
                    showRequestedUpdate()
                    return@runOnUiThread
                }
                if (!manual && activeDialog?.isShowing == true) return@runOnUiThread
                result.onSuccess { release ->
                    if (release == null) {
                        if (manual) modal("У тебя последняя версия", "Установлена «Волна» ${Updates.version(this)}.")
                    } else {
                        showUpdate(release)
                    }
                }.onFailure { failure ->
                    if (manual) {
                        val message = if (failure is Updates.NoPatch)
                            "Для установленной сборки патч отсутствует. Скачай свежий APK с сайта «Волны»."
                        else "Проверь подключение к интернету и попробуй ещё раз."
                        val website = if (failure is Updates.NoPatch) ModalAction("Открыть сайт") {
                            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updates.site(this)))) }
                            catch (_: ActivityNotFoundException) { toast("Не удалось открыть сайт") }
                        } else null
                        modal("Обновление недоступно", message, primary = website)
                    }
                }
            }
        }
    }

    private fun showUpdate(release: Updates.Release) {
        val size = android.text.format.Formatter.formatShortFileSize(this, release.patchSize)
        val wave = WaveArtwork(this).apply {
            background = shape(color("#0E1931"), 16)
            clipToOutline = true
        }
        modal(
            "Доступна «Волна» ${release.version}",
            primary = ModalAction("Скачать обновление") { downloadUpdate(release) },
            secondary = ModalAction("Позже") {},
            onDismiss = {
                updateWave?.cancel()
                updateWave = null
            }
        ) { body, _ ->
            add(body, wave, lp(-1, dp(164)))
            space(body, 12)
            add(body, text("Размер загрузки · $size", 14f, muted, 500))
        }
        if (motionEnabled()) {
            updateWave = ValueAnimator.ofFloat(.18f, 1f).apply {
                duration = 2900
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener { wave.seek(it.animatedValue as Float) }
                start()
            }
        }
    }

    private fun downloadUpdate(release: Updates.Release) {
        if (updateBusy) return
        updateBusy = true
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(line)
        }
        val phase = text("Загрузка патча", 14f, muted)
        val amount = text("0%", 14f, accent, 600)
        var task: java.util.concurrent.Future<*>? = null
        val progress = modal(
            "Волна ${release.version}",
            dismissible = false,
            secondary = ModalAction("Отменить") {
                task?.cancel(true)
                updateBusy = false
            }
        ) { body, _ ->
            val labels = row()
            add(labels, phase, lp(0, -2).apply { weight = 1f })
            add(labels, amount, lp(-2, -2))
            add(body, labels)
            space(body, 10)
            add(body, bar, lp(-1, dp(8)))
        }
        val app = applicationContext
        task = updateWorker.submit {
            val result = runCatching {
                Updates.prepare(app, release, object : Updates.Progress {
                    override fun downloading(percent: Int) = runOnUiThread {
                        if (progress.isShowing) {
                            bar.setProgress(percent, motionEnabled())
                            amount.text = "$percent%"
                        }
                    }

                    override fun verifying() = runOnUiThread {
                        if (progress.isShowing) {
                            phase.text = "Собираем и проверяем APK"
                            bar.setProgress(100, motionEnabled())
                            amount.text = ""
                        }
                    }
                })
            }
            runOnUiThread {
                if (isDestroyed || isFinishing || !progress.isShowing) return@runOnUiThread
                updateBusy = false
                progress.dismiss()
                if (!visible) return@runOnUiThread
                if (result.isSuccess) offerInstall()
                else modal("Не удалось применить патч", "Проверь связь и свободное место, затем попробуй ещё раз.")
            }
        }
    }

    private fun offerInstall() {
        modal("Обновление готово", "Android попросит подтвердить установку.",
            primary = ModalAction("Установить") { installUpdate() })
    }

    private fun installUpdate() {
        if (!Updates.apk(this).isFile) {
            toast("Патч нужно скачать заново")
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            modal(
                "Разрешение на установку",
                "Разреши установку из «Волны» в настройках Android. После этого вернись в приложение.",
                primary = ModalAction("Открыть настройки") {
                    try {
                        pendingInstall = true
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    } catch (_: ActivityNotFoundException) {
                        pendingInstall = false
                        toast("Настройки установки недоступны")
                    }
                }
            )
            return
        }
        val uri = Uri.parse("content://app.volna.updates/${Updates.APK_NAME}")
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(install) }
        catch (_: ActivityNotFoundException) { toast("Не удалось открыть установщик Android") }
    }

    private fun settingsPage() {
        add(content, text("Настройки", 30f, ink, 650)); space(content, 4)

        val appearance = section("Внешний вид")
        settingRow(appearance, R.drawable.ic_sun_moon, "Тема", when (Prefs.get(this).getString("appearance", "system")) { "light" -> "Светлая"; "dark" -> "Тёмная"; else -> "Как на устройстве" }) { chooseTheme() }
        divider(appearance); toggle(appearance, R.drawable.ic_sliders_horizontal, "Анимации", "Переходы и отклики интерфейса", "motion", true)
        val connection = section("Подключение")
        settingRow(connection, R.drawable.ic_wifi, "Профиль", routeLabel()) { chooseRoute() }
        divider(connection); settingRow(connection, R.drawable.ic_power, "Мои профили", "Быстрое подключение") { quickConnectProfiles() }
        divider(connection); settingRow(connection, R.drawable.ic_link, "Скопировать ссылку Telegram") {
            try { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Волна", Prefs.link(this))); toast("Ссылка скопирована") } catch (_: IllegalStateException) { toast("Сначала запустите подключение") }
        }
        val battery = section("Работа в фоне")
        toggle(battery, R.drawable.ic_smartphone, "Запуск при открытии", "Включать соединение вместе с приложением", "open_start", false)
        divider(battery); toggle(battery, R.drawable.ic_refresh_cw, "После перезагрузки", "Восстанавливать включённое соединение", "boot", false)
        divider(battery); toggle(battery, R.drawable.ic_shield_check, "Напрямую через VPN", "Использовать VPN телефона вместо профиля", "sleep_vpn", false)
        divider(battery); settingRow(battery, R.drawable.ic_battery, "Расход батареи", "Разрешить фоновую работу") {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) { toast("Настройки недоступны") }
        }
        val updates = section("Обновления")
        settingRow(updates, R.drawable.ic_link, "Адрес сайта", java.net.URI.create(Updates.site(this)).host, ::chooseWebsite)
        divider(updates)
        settingRow(updates, R.drawable.ic_refresh_cw, "Обновить профили", "Каталог сайта «Волны»") { syncCatalog(true) }
        divider(updates)
        settingRow(updates, R.drawable.ic_refresh_cw, "Проверить обновления", "Версия ${Updates.version(this)}") { checkUpdates(true) }
        val support = section("Поддержать проект")
        settingRow(support, R.drawable.ic_link, "Пожертвование", "ВТБ · 2200 2460 2273 3045") { copyDonationCard() }
        divider(support); settingRow(support, R.drawable.ic_send, "Telegram проекта", "@TgUnlock2026") { openProjectTelegram() }
        space(content, 24); add(content, text("Волна ${Updates.version(this)}", 13f, muted, 550).apply { gravity = Gravity.CENTER })
    }
    private fun chooseTheme() {
        val values = listOf("system", "light", "dark")
        val prefs = Prefs.get(this)
        val selected = values.indexOf(prefs.getString("appearance", "system")).coerceAtLeast(0)
        choices("Тема приложения", listOf("Как на устройстве", "Светлая", "Тёмная"), selected) { index ->
            if (index != selected) {
                val previousDark = dark
                val scroll = pageScroll.scrollY
                prefs.edit().putString("appearance", values[index]).apply()
                applyPalette()
                render(revealTheme = previousDark != dark, scrollY = scroll)
            }
        }
    }

    private fun copyDonationCard() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Карта ВТБ", "2200246022733045"))
        toast("Номер карты скопирован")
    }
    private fun openProjectTelegram() {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/TgUnlock2026"))) }
        catch (_: ActivityNotFoundException) { toast("Не удалось открыть Telegram проекта") }
    }
    private fun showSupport() {
        if (activeDialog != null || isFinishing) return
        modal(
            "Поддержка Волны",
            "Если приложение помогает оставаться на связи, можно поддержать его развитие.",
            primary = ModalAction("Закрыть") {},
            onDismiss = ::maybeRequestNotifications
        ) { body, _ ->
            space(body, 16)
            val card = column().apply {
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = shape(this@MainActivity.background, 14, line)
            }
            add(card, text("Карта ВТБ", 12f, muted, 500))
            space(card, 6)
            add(card, text("2200 2460 2273 3045", 19f, ink, 600).apply { setTextIsSelectable(true) })
            add(body, card)
            space(body, 10)
            add(body, modalButton("Скопировать номер карты", run = ::copyDonationCard))
            space(body, 6)
            add(body, modalButton("Telegram проекта", run = ::openProjectTelegram))
        }
        Prefs.get(this).edit().putBoolean("support_shown", true).apply()
    }

    private fun updateConnection() {
        if (page != 0 || stateText == null) return
        val service = LinkService.live
        val code = when { service == null && Prefs.get(this).getString("last_error", "")!!.isNotEmpty() -> "error"; service == null -> "off"; service.starting -> "starting"; service.isReady() && service.paused -> "vpn"; service.isReady() -> "ready"; service.error.isNotEmpty() -> "unavailable"; else -> "starting" }
        if (lastState == code) return
        val animateState = lastState.isNotEmpty() && motionEnabled()
        lastState = code
        stateText?.text = when (code) { "vpn" -> "Через VPN"; "ready" -> "Соединение готово"; "starting" -> "Подключаемся"; "unavailable" -> "Маршрут недоступен"; "error" -> "Не удалось подключиться"; else -> "Готово к работе" }
        supportingText?.text = when (code) { "vpn" -> "Новые соединения Telegram идут через VPN телефона."; "ready" -> if (service?.isStandard() == true) "Прокси MTProto готов. Откройте Telegram и подтвердите подключение." else "Локальный SOCKS5 запущен. Доступность сервера проверит Telegram."; "starting" -> "Запускаем выбранный маршрут."; "unavailable" -> service?.error?.takeIf { it.isNotBlank() } ?: "Проверьте выбранный профиль или импортируйте другой."; "error" -> Prefs.get(this).getString("last_error", "")?.takeIf { it.isNotBlank() } ?: "Повторите подключение. Если ошибка остаётся, перезапустите приложение."; else -> "Включите соединение, затем откройте Telegram." }
        statusChip?.text = when (code) { "vpn" -> "Прямой маршрут"; "ready" -> "Прокси готов"; "starting" -> "Подключение"; "unavailable", "error" -> "Нет соединения"; else -> "Не подключено" }
        actionLabel?.text = if (service == null) "Подключиться" else "Отключиться"
        actionButton?.contentDescription = actionLabel?.text
        powerIcon?.setImageResource(if (code == "ready" || code == "vpn") R.drawable.ic_check else R.drawable.ic_power)
        errorAction?.visibility = if (code == "unavailable" || code == "error") View.VISIBLE else View.GONE
        if (animateState) for (view in listOfNotNull(stateText, supportingText, statusChip, powerIcon)) {
            view.animate().cancel()
            view.alpha = 0.55f
            view.translationY = dp(5).toFloat()
            view.animate().alpha(1f).translationY(0f).setDuration(210).setInterpolator(easing).start()
        }
    }
    private fun startConnection(restart: Boolean = false) {
        if (Profiles.selected(this) == null) {
            openingTelegram = false
            importProfiles()
            return
        }
        try { val intent = Intent(this, LinkService::class.java); if (restart) intent.action = LinkService.RESTART; startForegroundService(intent); handler.postDelayed({ updateConnection() }, 200) }
        catch (_: Exception) { toast("Не удалось начать подключение") }
    }
    private fun stopConnection() { Prefs.get(this).edit().putBoolean("wanted", false).apply(); stopService(Intent(this, LinkService::class.java)); handler.postDelayed({ updateConnection() }, 200) }
    private fun openTelegram() {
        val service = LinkService.live
        if (service?.isRunning() == true) { launchTelegram(); return }
        if (openingTelegram) return
        openingTelegram = true; startConnection(); waitForConnection(0)
    }
    private fun waitForConnection(attempt: Int) {
        handler.postDelayed({
            if (isFinishing || isDestroyed) { openingTelegram = false; return@postDelayed }
            val service = LinkService.live
            when {
                service?.isRunning() == true -> { openingTelegram = false; launchTelegram() }
                attempt >= 35 -> { openingTelegram = false; toast("Не удалось запустить соединение") }
                else -> waitForConnection(attempt + 1)
            }
        }, 300)
    }
    private fun launchTelegram() {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Prefs.link(this)))) }
        catch (_: ActivityNotFoundException) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Prefs.link(this).replace("tg://", "https://t.me/")))) }
            catch (_: Exception) { toast("Установите Telegram и попробуйте снова") }
        } catch (_: IllegalStateException) { toast("Сначала запустите подключение") }
    }
    private fun toast(value: String) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show() }
}
