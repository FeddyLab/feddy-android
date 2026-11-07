package app.feddy.sdk.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import app.feddy.sdk.R
import app.feddy.sdk.core.FeddyCore
import app.feddy.sdk.core.Foreground
import app.feddy.sdk.core.ReplyPopupPick
import app.feddy.sdk.core.ReplyPopupPicker
import app.feddy.sdk.core.Rgb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.min

/**
 * Puts a reply the user has not seen in front of them when they come back to
 * the app. Most unread replies were never missed for lack of a return visit:
 * the user came back and never opened the panel.
 *
 * Not a notification: no permission, nothing in the shade, the host's
 * notification setup untouched. A card the SDK draws over the host's own
 * screen, once per reply.
 *
 * Drawn with plain Views, never Compose: the host's activity may be a plain
 * `Activity` (every Flutter app's is), and a ComposeView there has no
 * lifecycle owner to run on.
 */
internal object ReplyPopupCoordinator {
    /** Long enough for a launch screen or paywall to settle; folds one launch's triggers into one check. */
    private const val SETTLE_MS = 2_000L
    private const val VISIBLE_MS = 8_000L

    var enabled: Boolean = true
        set(value) {
            val old = field
            field = value
            if (!value) hide() else if (!old) scheduleCheck()
        }

    private var started = false
    private var checkJob: Job? = null
    private var dismissJob: Job? = null
    /**
     * Weak: the card's context is the host's activity, and this object lives
     * for the whole process. It is removed when that activity pauses anyway.
     */
    private var cardRef: WeakReference<ReplyPopupCard>? = null
    private var card: ReplyPopupCard?
        get() = cardRef?.get()
        set(value) {
            cardRef = value?.let { WeakReference(it) }
        }
    /** A check found no screen to draw on; the next host screen to come up retries it. */
    private var deferred = false

    fun start() {
        if (started) return
        started = true
        FeddyCore.onForeground = { scheduleCheck() }
        Foreground.onResumedChanged = { activity ->
            val shownOn = card?.host
            if (shownOn != null && shownOn !== activity) hide(animated = false)
            if (activity != null && !Foreground.isOwnActivity(activity) && deferred) scheduleCheck()
        }
        scheduleCheck()
    }

    /** Every trigger lands here: launch, back to the foreground, the unread count going up. */
    fun scheduleCheck() {
        if (!started) return
        checkJob?.cancel()
        checkJob = FeddyCore.scope.launch {
            delay(SETTLE_MS)
            check()
        }
    }

    fun hide(animated: Boolean = true) {
        dismissJob?.cancel()
        val current = card ?: return
        card = null
        current.dismiss(animated)
    }

    private val hostActivity: Activity?
        get() = Foreground.resumedActivity?.takeIf { !it.isFinishing && !Foreground.isOwnActivity(it) }

    private val canShow: Boolean
        get() = enabled && card == null && Foreground.isForeground && FeddyActivity.visible == 0

    private suspend fun check() {
        val client = FeddyCore.client ?: return
        if (!canShow) return
        if (hostActivity == null) {
            deferred = true
            return
        }
        try {
            // The count is the cheap question; the list only when there is something to look at.
            if (client.unreadCount() <= 0) return
            val list = client.conversations()
            FeddyCore.loadConfig()
            if (!canShow) return
            val host = hostActivity ?: run {
                deferred = true
                return
            }
            deferred = false
            val shown = FeddyCore.prefs.popupShown
            val pick = ReplyPopupPicker.pick(list, shown)
            // Recorded only once it is on screen: a reply marked shown but never
            // drawn would never get another chance.
            if (pick != null && !show(host, pick)) return
            FeddyCore.prefs.popupShown = ReplyPopupPicker.record(pick, shown, list)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // Offline or the server hiccuped: the next trigger tries again.
        }
    }

    private fun show(host: Activity, pick: ReplyPopupPick): Boolean {
        val content = host.findViewById<ViewGroup>(android.R.id.content) ?: return false
        val view = ReplyPopupCard(host, pick, onOpen = { open(pick) }, onClose = { hide() }, onInteracting = { interacting ->
            if (interacting) dismissJob?.cancel() else scheduleAutoDismiss()
        })
        card = view
        view.attach(content)
        val accessibility = host.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (accessibility?.isTouchExplorationEnabled == true) {
            // A timed card cannot be read in time with TalkBack; it stays until dismissed.
            ViewCompat.setAccessibilityPaneTitle(view, view.spokenSummary)
        } else {
            scheduleAutoDismiss()
        }
        return true
    }

    private fun scheduleAutoDismiss() {
        dismissJob?.cancel()
        dismissJob = FeddyCore.scope.launch {
            delay(VISIBLE_MS)
            hide()
        }
    }

    private fun open(pick: ReplyPopupPick) {
        val host = card?.host
        hide()
        val context: Context = host ?: Foreground.resumedActivity ?: return
        FeddyActivity.open(context, FeddyActivity.Start.Home, pick.conversationId)
    }
}

/**
 * Reads like a system notification banner, which every phone user already
 * knows how to read and dismiss: who, when, two lines, swipe up. No app
 * name (it is the app the user is in) and no close button.
 */
@SuppressLint("ViewConstructor")
private class ReplyPopupCard(
    val host: Activity,
    private val pick: ReplyPopupPick,
    private val onOpen: () -> Unit,
    private val onClose: () -> Unit,
    private val onInteracting: (Boolean) -> Unit,
) : FrameLayout(host) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = (value * density)
    private fun dpi(value: Float) = dp(value).toInt()

    private val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val cardColor = when {
        Build.VERSION.SDK_INT >= 31 && dark -> host.getColor(android.R.color.system_neutral1_800)
        Build.VERSION.SDK_INT >= 31 -> host.getColor(android.R.color.system_neutral1_10)
        dark -> Color.rgb(0x2B, 0x2B, 0x2F)
        else -> Color.WHITE
    }
    private val textColor = if (dark) Color.rgb(0xE6, 0xE1, 0xE5) else Color.rgb(0x1C, 0x1B, 0x1F)
    private val secondaryColor = (textColor and 0x00FFFFFF) or (0xA6 shl 24)
    private val radius = dp(18f)
    private val animationsOn = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()

    private val replied = context.getString(R.string.feddy_replied_format)
    private val newReply = context.getString(R.string.feddy_new_reply)

    val spokenSummary: String
        get() {
            val name = pick.authorName?.takeIf { it.isNotEmpty() }
            val title = if (name != null) String.format(replied, name) else newReply
            return "$title. $message"
        }

    private val message: String
        get() = pick.message?.trim()?.takeIf { it.isNotEmpty() } ?: newReply

    init {
        background = GradientDrawable().apply {
            setColor(cardColor)
            cornerRadius = radius
        }
        elevation = dp(10f)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(14f), dpi(14f), dpi(16f), dpi(14f))
        }
        row.addView(buildMark(), LinearLayout.LayoutParams(dpi(40f), dpi(40f)).apply { marginEnd = dpi(12f) })
        val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        header.addView(
            TextView(context).apply {
                this.text = heading()
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(
            TextView(context).apply {
                this.text = compactTime()
                setTextColor(secondaryColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 1
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dpi(8f) },
        )
        text.addView(header)
        text.addView(
            TextView(context).apply {
                this.text = message
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(1f) },
        )
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        isClickable = true
        isFocusable = true
        contentDescription = spokenSummary
        ViewCompat.replaceAccessibilityAction(this, AccessibilityActionCompat.ACTION_CLICK, null) { _, _ ->
            onOpen()
            true
        }
        ViewCompat.addAccessibilityAction(this, context.getString(R.string.feddy_close)) { _, _ ->
            onClose()
            true
        }
    }

    /** "Yu replied" with the name in bold; the sentence is a localized format so each language puts the name where it belongs. */
    private fun heading(): CharSequence {
        val name = pick.authorName?.takeIf { it.isNotEmpty() }
            ?: return SpannableStringBuilder(newReply).apply { setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        val parts = replied.split("%1\$s")
        if (parts.size != 2) {
            return SpannableStringBuilder(name).apply { setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        val out = SpannableStringBuilder(parts[0])
        val start = out.length
        out.append(name)
        out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.append(parts[1])
        return out
    }

    /** Compact, like a system banner: "now", then 5m, 3h, 2d. */
    private fun compactTime(): String {
        val minutes = ((System.currentTimeMillis() - pick.sentAt) / 60_000).toInt()
        return when {
            minutes < 1 -> android.text.format.DateUtils.getRelativeTimeSpanString(
                System.currentTimeMillis(), System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
            ).toString()
            minutes < 60 -> "${minutes}m"
            minutes < 24 * 60 -> "${minutes / 60}h"
            else -> "${minutes / (24 * 60)}d"
        }
    }

    /** The same mark the panel uses: the project logo, or its initial on the brand colour. */
    private fun buildMark(): View {
        val config = FeddyCore.config.value
        val markRadius = dp(10f)
        val frame = FrameLayout(context).apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, markRadius)
                }
            }
        }
        val logo = config?.brand?.logoUrl
        val brand = Rgb.parse(config?.brand?.color)
        if (logo != null) {
            frame.setBackgroundColor((textColor and 0x00FFFFFF) or (0x14 shl 24))
            val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            frame.addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            ImageStore.cached(ImageSource.Url(logo))?.let { image.setImageBitmap(it) } ?: FeddyCore.scope.launch {
                runCatching { ImageStore.load(ImageSource.Url(logo), 512) }.getOrNull()?.let { image.setImageBitmap(it) }
            }
        } else {
            val accent = brand?.argb ?: Color.rgb(0x4F, 0x5A, 0xFA)
            frame.setBackgroundColor(accent)
            frame.addView(
                TextView(context).apply {
                    text = brandInitial(config?.brand?.name)
                    gravity = Gravity.CENTER
                    setTypeface(typeface, Typeface.BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    setTextColor(if (brand?.needsDarkText == true) Color.BLACK else Color.WHITE)
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
        }
        return frame
    }

    // MARK: Placement and motion

    fun attach(content: ViewGroup) {
        val params = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        val side = dpi(10f)
        params.leftMargin = side
        params.rightMargin = side
        params.topMargin = topInset(content) + dpi(6f)
        // Phones get the full width; on a tablet the card stays banner-sized.
        val maxWidth = dpi(500f)
        val available = content.width - 2 * side
        if (available > maxWidth) params.width = maxWidth
        content.addView(this, params)
        alpha = 0f
        post {
            if (animationsOn) translationY = offscreen
            animate().alpha(1f).translationY(0f).setDuration(450).setInterpolator(OvershootInterpolator(0.8f)).start()
        }
    }

    /** Below the status bar and any cutout, whether or not the host draws edge to edge. */
    private fun topInset(content: ViewGroup): Int {
        val insets = ViewCompat.getRootWindowInsets(content) ?: return 0
        val top = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).top
        val location = IntArray(2)
        content.getLocationInWindow(location)
        return maxOf(0, top - location[1])
    }

    private val offscreen: Float get() = -(top + height + dp(24f))

    fun dismiss(animated: Boolean) {
        val parent = parent as? ViewGroup ?: return
        if (!animated) {
            parent.removeView(this)
            return
        }
        animate().cancel()
        animate()
            .alpha(0f)
            .translationY(if (animationsOn) offscreen else 0f)
            .setDuration(300)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { parent.removeView(this) }
            .start()
    }

    // MARK: Gestures

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downY = 0f
    private var dragging = false
    private var tracker: VelocityTracker? = null

    /** Up dismisses, the way a system banner does; down only gives a little. */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.rawY
                dragging = false
                tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                animate().cancel()
                onInteracting(true)
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(event)
                val dy = event.rawY - downY
                if (!dragging && abs(dy) > touchSlop) dragging = true
                if (dragging) translationY = if (dy < 0) dy else dy / 6
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker?.addMovement(event)
                tracker?.computeCurrentVelocity(1000)
                val velocity = tracker?.yVelocity ?: 0f
                tracker?.recycle()
                tracker = null
                onInteracting(false)
                val dy = event.rawY - downY
                when {
                    event.actionMasked == MotionEvent.ACTION_UP && dragging && (dy < -dp(30f) || velocity < -dp(500f)) -> onClose()
                    event.actionMasked == MotionEvent.ACTION_UP && !dragging -> onOpen()
                    else -> animate().translationY(0f).setDuration(300).setInterpolator(OvershootInterpolator(0.6f)).start()
                }
            }
        }
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = dpi(500f)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val spec = if (width > maxWidth) MeasureSpec.makeMeasureSpec(min(width, maxWidth), MeasureSpec.EXACTLY) else widthMeasureSpec
        super.onMeasure(spec, heightMeasureSpec)
    }
}
