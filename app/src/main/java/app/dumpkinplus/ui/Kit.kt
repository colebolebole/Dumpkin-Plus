package app.dumpkinplus.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import app.dumpkinplus.R
import app.dumpkinplus.data.RuntimeState
import app.dumpkinplus.data.Status
import java.util.Locale

/*
 * A tiny view toolkit built on the platform widgets only (no AndroidX, no Compose, no Material library).
 * Everything here works on Android 5.0 (API 21) and costs far less memory and start-up time than a Compose UI.
 */

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** The colour scheme, switched between dark and light in [app.dumpkinplus.MainActivity] before any view is built. */
object Pal {
    @JvmStatic var dark = true

    val bg get() = if (dark) 0xFF1A110C.toInt() else 0xFFFFF8F4.toInt()
    val surface get() = if (dark) 0xFF2A1D15.toInt() else 0xFFF6EBE2.toInt()
    val surfaceHi get() = if (dark) 0xFF3A2A1F.toInt() else 0xFFEBDCCF.toInt()
    val text get() = if (dark) 0xFFF2E6DC.toInt() else 0xFF241A12.toInt()
    val dim get() = if (dark) 0xFFB9A89A.toInt() else 0xFF6B5A4D.toInt()
    val primary get() = if (dark) 0xFFF58220.toInt() else 0xFFB85800.toInt()
    val onPrimary get() = if (dark) 0xFF2A1A10.toInt() else 0xFFFFFFFF.toInt()
    val ok get() = if (dark) 0xFF7CC66A.toInt() else 0xFF2E7D32.toInt()
    val error get() = if (dark) 0xFFFF7A6B.toInt() else 0xFFB3261E.toInt()
    val warn get() = if (dark) 0xFFFFC857.toInt() else 0xFF8A5A00.toInt()
    val ripple get() = if (dark) 0x33FFFFFF else 0x22000000
    val console get() = if (dark) 0xFF120B07.toInt() else 0xFFFFFFFF.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun View.dp(v: Int): Int = context.dp(v)

fun rounded(color: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

fun oval(color: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
}

fun ripple(content: Drawable?): Drawable = RippleDrawable(ColorStateList.valueOf(Pal.ripple), content, null)

// ---------------------------------------------------------------- text

fun Context.text(
    s: CharSequence,
    size: Float = 14f,
    color: Int = Pal.text,
    bold: Boolean = false,
    mono: Boolean = false,
): TextView = TextView(this).apply {
    text = s
    setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    setTextColor(color)
    if (mono) typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
    else if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
}

fun Context.title(s: CharSequence) = text(s, 22f, bold = true)
fun Context.section(s: CharSequence) = text(s, 15f, Pal.primary, bold = true).apply { setPadding(0, dp(8), 0, 0) }
fun Context.hint(s: CharSequence) = text(s, 12.5f, Pal.dim)

// ---------------------------------------------------------------- layout

fun Context.vbox(gapDp: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    if (gapDp > 0) gap(gapDp)
}

fun Context.hbox(gapDp: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    if (gapDp > 0) gap(gapDp)
}

/** Even spacing between children, without a margin on every one (hidden children then leave no stray gap). */
fun LinearLayout.gap(dpValue: Int) {
    showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
    dividerDrawable = GradientDrawable().apply {
        setSize(context.dp(dpValue), context.dp(dpValue))
        setColor(Color.TRANSPARENT)
    }
}

fun <T : View> LinearLayout.add(v: T, w: Int = MATCH, h: Int = WRAP, weight: Float = 0f): T {
    addView(v, LinearLayout.LayoutParams(w, h, weight))
    return v
}

fun Context.card(pad: Int = 16, gap: Int = 8, bg: Int = Pal.surface): LinearLayout = vbox(gap).apply {
    background = rounded(bg, dp(14).toFloat())
    setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
}

fun View.enable(on: Boolean) {
    isEnabled = on
    alpha = if (on) 1f else 0.4f
}

// ---------------------------------------------------------------- controls

enum class BtnKind { Primary, Tonal, Outline, Danger, Text }

fun Context.button(label: String, kind: BtnKind = BtnKind.Primary, onClick: () -> Unit): Button = Button(this).apply {
    text = label
    setAllCaps(false)
    stateListAnimator = null // no drop shadow: cheaper to draw and flatter, which suits the rest of the UI
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
    minHeight = dp(44)
    minimumHeight = dp(44)
    minWidth = 0
    minimumWidth = 0
    setPadding(dp(16), 0, dp(16), 0)
    val r = dp(22).toFloat()
    val (bg, fg) = when (kind) {
        BtnKind.Primary -> rounded(Pal.primary, r) to Pal.onPrimary
        BtnKind.Tonal -> rounded(Pal.surfaceHi, r) to Pal.text
        BtnKind.Outline -> rounded(Color.TRANSPARENT, r, Pal.dim, dp(1)) to Pal.text
        BtnKind.Danger -> rounded(Pal.error, r) to (if (Pal.dark) 0xFF2A0A06.toInt() else 0xFFFFFFFF.toInt())
        BtnKind.Text -> rounded(Color.TRANSPARENT, r) to Pal.primary
    }
    background = ripple(bg)
    setTextColor(fg)
    setOnClickListener { onClick() }
}

fun Context.switchRow(title: String, body: String?, checked: Boolean, onChange: (Boolean) -> Unit): View =
    hbox(12).apply {
        val col = vbox(2)
        col.add(text(title, 15f))
        if (body != null) col.add(hint(body))
        add(col, 0, WRAP, 1f)
        add(Switch(context).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }, WRAP, WRAP)
    }

/** A label above a text box, with a line underneath for validation messages. */
class Field(
    ctx: Context,
    label: String,
    value: String,
    number: Boolean = false,
    maxLength: Int = 120,
    private val onChange: (String) -> Unit,
) {
    val edit: EditText = EditText(ctx)
    private val note: TextView = ctx.text("", 12f, Pal.dim)
    private var quiet = false
    val view: LinearLayout = ctx.vbox(2)

    init {
        view.add(ctx.text(label, 12.5f, Pal.dim))
        edit.apply {
            setText(value)
            setTextColor(Pal.text)
            setHintTextColor(Pal.dim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setSingleLine(true)
            inputType = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            filters = arrayOf(InputFilter.LengthFilter(maxLength))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (!quiet) onChange(s?.toString().orEmpty())
                }
            })
        }
        view.add(edit)
        view.add(note)
        note.visibility = View.GONE
    }

    var text: String
        get() = edit.text.toString()
        set(v) {
            quiet = true
            edit.setText(v)
            quiet = false
        }

    /** Shows [message] in red, or [neutral] in grey when there is no problem; hides the line if both are null. */
    fun note(message: String?, neutral: String? = null) {
        val shown = message ?: neutral
        note.visibility = if (shown == null) View.GONE else View.VISIBLE
        note.text = shown.orEmpty()
        note.setTextColor(if (message != null) Pal.error else Pal.dim)
    }
}

/** A row of mutually exclusive choices. Wraps onto more lines on narrow screens. */
class ChipGroup(
    private val ctx: Context,
    labels: List<String>,
    selected: Int,
    private val onSelect: (Int) -> Unit,
) {
    val view = FlowLayout(ctx, ctx.dp(8), ctx.dp(8))
    private val chips = labels.mapIndexed { i, label ->
        ctx.text(label, 14f).apply {
            gravity = Gravity.CENTER
            minHeight = ctx.dp(40)
            minimumHeight = ctx.dp(40)
            setPadding(ctx.dp(16), 0, ctx.dp(16), 0)
            setOnClickListener {
                set(i)
                onSelect(i)
            }
        }
    }
    var selected: Int = selected
        private set

    init {
        chips.forEach { view.addView(it) }
        set(selected)
    }

    fun set(i: Int) {
        selected = i
        chips.forEachIndexed { idx, c ->
            val on = idx == i
            c.background = ripple(rounded(if (on) Pal.primary else Pal.surfaceHi, ctx.dp(20).toFloat()))
            c.setTextColor(if (on) Pal.onPrimary else Pal.text)
        }
    }
}

/** An integer slider with a title and a live value, built on SeekBar (which has no minimum before Android 8). */
class Slider(
    ctx: Context,
    title: String,
    private val min: Int,
    private val max: Int,
    private val step: Int,
    value: Int,
    private val fmt: (Int) -> String,
    private val onChange: (Int) -> Unit,
) {
    val view: LinearLayout = ctx.vbox(0)
    private val valueText = ctx.text(fmt(value), 13.5f, Pal.dim, bold = true)
    private val bar = SeekBar(ctx)
    private var quiet = false

    init {
        val head = ctx.hbox()
        head.add(ctx.text(title, 15f), 0, WRAP, 1f)
        head.add(valueText, WRAP, WRAP)
        view.add(head)
        bar.max = (max - min) / step
        bar.progress = (value.coerceIn(min, max) - min) / step
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = min + p * step
                valueText.text = fmt(v)
                if (fromUser && !quiet) onChange(v)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        view.add(bar)
    }

    fun set(v: Int) {
        quiet = true
        bar.progress = (v.coerceIn(min, max) - min) / step
        valueText.text = fmt(v)
        quiet = false
    }
}

/** A coloured dot that shows a server's state. */
class StatusDot(ctx: Context, sizeDp: Int) : View(ctx) {
    init {
        layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
    }

    fun set(status: Status) {
        val (fill, ring) = when (status) {
            Status.Running -> Pal.ok to true
            Status.Starting, Status.Stopping -> Pal.primary to true
            Status.Crashed -> Pal.error to true
            Status.Stopped -> Pal.dim to false
        }
        background = (oval(fill) as GradientDrawable).apply {
            if (ring) setStroke(context.dp(3), (fill and 0x00FFFFFF) or 0x55000000)
        }
    }
}

/** Lays children out left to right and wraps to a new line when the row is full. */
class FlowLayout(ctx: Context, private val hGap: Int, private val vGap: Int) : ViewGroup(ctx) {
    private fun arrange(maxW: Int, place: ((View, Int, Int) -> Unit)?): Int {
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            c.measure(
                MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            val w = c.measuredWidth
            val h = c.measuredHeight
            if (x > 0 && x + w > maxW) {
                x = 0
                y += rowH + vGap
                rowH = 0
            }
            place?.invoke(c, x, y)
            x += w + hGap
            rowH = maxOf(rowH, h)
        }
        return y + rowH
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val maxW = MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight
        val h = arrange(maxW, null)
        setMeasuredDimension(
            resolveSize(MeasureSpec.getSize(widthSpec), widthSpec),
            resolveSize(h + paddingTop + paddingBottom, heightSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        arrange(r - l - paddingLeft - paddingRight) { c, x, y ->
            val left = paddingLeft + x
            val top = paddingTop + y
            c.layout(left, top, left + c.measuredWidth, top + c.measuredHeight)
        }
    }
}

// ---------------------------------------------------------------- dialogs and toasts

fun Context.dialogBuilder(): AlertDialog.Builder =
    AlertDialog.Builder(this, if (Pal.dark) R.style.Dialog_Dark else R.style.Dialog_Light)

fun Context.confirm(title: String, body: String, confirmLabel: String, onConfirm: () -> Unit) {
    dialogBuilder()
        .setTitle(title)
        .setMessage(body)
        .setPositiveButton(confirmLabel) { _, _ -> onConfirm() }
        .setNegativeButton("Cancel", null)
        .show()
}

fun Context.toast(msg: String) {
    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

fun Context.copyToClipboard(label: String, value: String) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, value))
}

// ---------------------------------------------------------------- formatting

fun statusLabel(s: Status) = when (s) {
    Status.Stopped -> "Offline"
    Status.Starting -> "Starting…"
    Status.Running -> "Online"
    Status.Stopping -> "Stopping…"
    Status.Crashed -> "Crashed"
}

/** What to show for a server: a restart in progress reads as one "starting" state, not stopping, offline, starting. */
val RuntimeState.displayStatus: Status get() = if (restarting) Status.Starting else status

fun statusLabel(st: RuntimeState) = if (st.restarting) "Restarting…" else statusLabel(st.status)

fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return when {
        h > 0 -> String.format(Locale.US, "%dh %02dm", h, m)
        m > 0 -> String.format(Locale.US, "%dm %02ds", m, sec)
        else -> "${sec}s"
    }
}

fun formatMb(mb: Int): String =
    if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024.0) else "$mb MB"

fun formatBytes(b: Long): String = when {
    b >= 1_048_576 -> String.format(Locale.US, "%.1f MB", b / 1_048_576.0)
    b >= 1024 -> String.format(Locale.US, "%.0f KB", b / 1024.0)
    else -> "$b B"
}

/** Console markers (▶ ■ ✖ ⚠ ↻ ℹ) are missing from the fonts of many older phones and show as boxes there. */
fun consoleSafe(line: String): String {
    if (Build.VERSION.SDK_INT >= 26) return line
    return line.replace('▶', '>').replace('■', '#').replace('✖', 'x').replace('⚠', '!').replace('↻', '~').replace('ℹ', 'i')
}
