package app.dumpkinplus.ui

import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import app.dumpkinplus.MainActivity
import app.dumpkinplus.data.Compression
import app.dumpkinplus.data.DeviceTuning
import app.dumpkinplus.data.Difficulty
import app.dumpkinplus.data.DistancePreset
import app.dumpkinplus.data.GameMode
import app.dumpkinplus.data.MemoryAction
import app.dumpkinplus.data.ProfileRules
import app.dumpkinplus.data.RuntimeState
import app.dumpkinplus.data.ServerProfile
import app.dumpkinplus.data.Status
import app.dumpkinplus.data.deviceMemory
import app.dumpkinplus.server.ServerManager
import app.dumpkinplus.server.ServerRuntime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One server: status and controls on top, then a Console tab and a Settings tab. */
class ServerScreen(act: MainActivity, private val id: String) : Screen(act) {
    private val rt: ServerRuntime? = app.manager.runtime(id)

    private lateinit var root: LinearLayout
    private lateinit var headerBar: View
    private lateinit var nameText: TextView
    private lateinit var topBlock: LinearLayout
    private lateinit var tabsRow: View
    private lateinit var consoleSlot: FrameLayout
    private lateinit var settingsColumn: LinearLayout
    private lateinit var settingsSlot: LinearLayout
    private lateinit var body: FrameLayout
    private lateinit var settingsScroll: ScrollView

    private lateinit var hero: Hero
    private lateinit var console: Console

    private var tab = 0
    private var keyboardUp = false

    private fun profile(): ServerProfile? = app.manager.profiles.value.firstOrNull { it.id == id }

    override fun build(): View {
        root = act.vbox()
        val p = profile()

        // Header
        headerBar = act.hbox(4).apply { setPadding(act.dp(4), act.dp(4), act.dp(16), act.dp(4)) }
        (headerBar as LinearLayout).add(
            act.text("←", 26f, Pal.primary, bold = true).apply {
                setPadding(act.dp(14), act.dp(8), act.dp(14), act.dp(8))
                isClickable = true
                background = ripple(null)
                setOnClickListener { act.closeServer() }
            },
            WRAP, WRAP,
        )
        nameText = act.text(p?.name.orEmpty(), 20f, bold = true).apply {
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        (headerBar as LinearLayout).add(nameText, 0, WRAP, 1f)
        root.add(headerBar)

        // Hero card and tab switcher live in one block that moves between the two tabs.
        hero = Hero()
        val tabGroup = ChipGroup(act, listOf("Console", "Settings"), 0) { showTab(it) }
        tabsRow = tabGroup.view
        topBlock = act.vbox(12)
        topBlock.add(hero.view)
        topBlock.add(tabsRow)

        consoleSlot = FrameLayout(act).apply { setPadding(act.dp(16), act.dp(4), act.dp(16), act.dp(12)) }
        root.add(consoleSlot)

        console = Console()
        body = FrameLayout(act)
        root.add(body, MATCH, 0, 1f)

        settingsColumn = act.vbox(14).apply { setPadding(act.dp(16), 0, act.dp(16), act.dp(24)) }
        settingsSlot = act.vbox().apply { setPadding(0, act.dp(4), 0, 0) }
        settingsColumn.add(settingsSlot)
        settingsScroll = ScrollView(act).apply { addView(settingsColumn) }
        if (p != null) buildSettings()

        showTab(0)
        return root
    }

    private val keyboardListener = ViewTreeObserver.OnGlobalLayoutListener {
        val r = Rect()
        view.getWindowVisibleDisplayFrame(r)
        val full = view.rootView.height
        val up = full > 0 && (full - r.bottom) > full * 0.15
        if (up != keyboardUp) {
            keyboardUp = up
            applyKeyboard()
        }
    }

    /**
     * Typing a command gives the console the whole screen: the keyboard hides everything else and closing it brings
     * it back. Typing in a settings field only hides the status card.
     */
    private fun applyKeyboard() {
        hero.view.visibility = if (keyboardUp) View.GONE else View.VISIBLE
        val full = keyboardUp && tab == 0
        tabsRow.visibility = if (full) View.GONE else View.VISIBLE
        consoleSlot.visibility = if (full) View.GONE else View.VISIBLE
        headerBar.visibility = if (full) View.GONE else View.VISIBLE
    }

    private fun showTab(i: Int) {
        tab = i
        (topBlock.parent as? ViewGroup)?.removeView(topBlock)
        body.removeAllViews()
        if (i == 0) {
            consoleSlot.addView(topBlock)
            body.addView(console.view, FrameLayout.LayoutParams(MATCH, MATCH))
        } else {
            settingsSlot.addView(topBlock)
            body.addView(settingsScroll, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        applyKeyboard()
    }

    override fun onStart() {
        val runtime = rt
        if (runtime == null || profile() == null) {
            view.post { act.closeServer() }
            return
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(keyboardListener)
        scope.launch {
            combine(app.manager.profiles, runtime.state) { list, st -> list.firstOrNull { it.id == id } to st }
                .collect { (p, st) ->
                    if (p == null) return@collect
                    nameText.text = p.name
                    hero.bind(p, st)
                    console.bind(st)
                }
        }
        scope.launch { runtime.logs.collect { console.setLogs(it) } }
        // Uptime ticks once a second, but only while the server is up.
        scope.launch {
            while (isActive) {
                delay(1000)
                val st = runtime.state.value
                val p = profile()
                if (st.status == Status.Running && p != null) hero.tick(st)
            }
        }
    }

    override fun onStop() {
        runCatching { view.viewTreeObserver.removeOnGlobalLayoutListener(keyboardListener) }
    }

    // ------------------------------------------------------------------ hero

    private inner class Hero {
        val view: LinearLayout = act.card(16, 10, Pal.surfaceHi)
        private val dot = StatusDot(act, 26)
        private val statusText = act.text("", 22f, bold = true)
        private val subText = act.text("", 13.5f, Pal.dim)
        private val addressText = act.text("", 16f, bold = true, mono = true)
        private val versionText = act.hint("")
        private val memLabel = act.text("", 13.5f, Pal.dim)
        private val memValue = act.text("", 13.5f, Pal.dim, bold = true)
        private val memBar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        private val memBox = act.vbox(4)
        private val actions = act.hbox(8)
        private var actionKey: Any? = null
        private var ip = "this-phone"
        private var lastStatus: Status? = null
        private var current: ServerProfile? = null

        init {
            val top = act.hbox(14)
            top.addView(dot)
            val col = act.vbox(1)
            col.add(statusText)
            col.add(subText)
            top.add(col, 0, WRAP, 1f)
            view.add(top)

            val addrRow = act.hbox(8)
            addrRow.add(addressText, 0, WRAP, 1f)
            addrRow.add(act.button("Copy", BtnKind.Tonal) {
                val a = addressText.text.toString()
                act.copyToClipboard("Server address", a)
                act.toast("Copied $a")
            }, WRAP, WRAP)
            view.add(addrRow)
            view.add(versionText)

            val memHead = act.hbox()
            memHead.add(memLabel, 0, WRAP, 1f)
            memHead.add(memValue, WRAP, WRAP)
            memBox.add(memHead)
            memBox.add(memBar, MATCH, act.dp(10))
            view.add(memBox)
            view.add(actions)
        }

        fun bind(p: ServerProfile, st: RuntimeState) {
            current = p
            if (st.status != lastStatus) {
                lastStatus = st.status
                ip = ServerManager.localAddresses().firstOrNull() ?: "this-phone"
            }
            dot.set(st.displayStatus)
            statusText.text = statusLabel(st)
            addressText.text = "$ip:${p.port}"
            versionText.visibility = if (st.status == Status.Running && !st.version.isNullOrBlank()) View.VISIBLE else View.GONE
            versionText.text = "Reports as ${st.version}"
            subText.text = when {
                st.restarting -> "Back in a few seconds, with your latest settings"
                st.status == Status.Running -> "${st.online}/${st.max} players · up ${formatDuration(System.currentTimeMillis() - st.startedAt)}"
                st.status == Status.Crashed -> st.error ?: "The server stopped unexpectedly"
                else -> "Players join at"
            }
            val showMem = st.isActive && st.rssMb > 0
            memBox.visibility = if (showMem) View.VISIBLE else View.GONE
            if (showMem) {
                val limit = p.memoryLimitMb
                memLabel.text = "Memory"
                memValue.text = if (limit > 0) "${formatMb(st.rssMb)} / ${formatMb(limit)}" else formatMb(st.rssMb)
                memBar.visibility = if (limit > 0) View.VISIBLE else View.GONE
                if (limit > 0) memBar.progress = (st.rssMb * 1000L / limit).toInt().coerceIn(0, 1000)
            }
            bindActions(p, st)
        }

        /** Once-a-second refresh of the uptime line. */
        fun tick(st: RuntimeState) {
            subText.text = "${st.online}/${st.max} players · up ${formatDuration(System.currentTimeMillis() - st.startedAt)}"
        }

        private fun bindActions(p: ServerProfile, st: RuntimeState) {
            val key = Triple(st.displayStatus, st.status == Status.Running, st.isActive)
            if (key == actionKey) return
            actionKey = key
            actions.removeAllViews()
            when (st.displayStatus) {
                Status.Starting, Status.Stopping -> {
                    actions.gravity = Gravity.CENTER
                    actions.addView(ProgressBar(act).apply { isIndeterminate = true }, LinearLayout.LayoutParams(act.dp(40), act.dp(40)))
                }
                else -> {
                    actions.gravity = Gravity.CENTER_VERTICAL
                    if (st.status == Status.Running) {
                        actions.add(act.button("Restart", BtnKind.Outline) {
                            val now = rt?.state?.value
                            // Restarting disconnects everyone, so check first if anyone is playing.
                            if (now != null && now.online > 0) {
                                val who = if (now.online == 1) "1 player is" else "${now.online} players are"
                                act.confirm(
                                    "Restart ${p.name}?",
                                    "$who online and will be disconnected. The server comes back in a few seconds.",
                                    "Restart",
                                ) { app.manager.restart(id)?.let { act.toast(it) } }
                            } else {
                                app.manager.restart(id)?.let { act.toast(it) }
                            }
                        }, 0, WRAP, 1f)
                    }
                    actions.add(
                        act.button(if (st.isActive) "Stop" else "Start server", if (st.isActive) BtnKind.Danger else BtnKind.Primary) {
                            if (rt?.state?.value?.isActive == true) app.manager.stop(id)
                            else app.manager.start(id)?.let { act.toast(it) }
                        },
                        0, WRAP, 1f,
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ console

    private inner class Console {
        val view: LinearLayout = act.vbox(8).apply { setPadding(act.dp(16), 0, act.dp(16), act.dp(8)) }
        private val lines = ArrayList<String>()
        private val list = ListView(act)
        private val empty = act.text("Server output will show up here.", 13.5f, Pal.dim).apply { setPadding(act.dp(14), act.dp(12), act.dp(14), 0) }
        private val input = EditText(act)
        private val send: View
        private val helpBtn: View
        private val listBtn: View

        private val adapter = object : BaseAdapter() {
            override fun getCount() = lines.size
            override fun getItem(position: Int) = lines[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val tv = (convertView as? TextView) ?: act.text("", 12f, mono = true).apply {
                    setPadding(0, act.dp(1), 0, act.dp(1))
                }
                val line = lines[position]
                tv.setTextColor(colorFor(line))
                tv.text = consoleSafe(line)
                return tv
            }
        }

        private fun colorFor(line: String): Int = when {
            line.startsWith(">") -> Pal.primary
            line.startsWith("✖") || line.contains("ERROR", true) || line.contains("panicked", true) -> Pal.error
            line.contains("WARN", true) -> Pal.warn
            line.startsWith("▶") || line.startsWith("■") -> Pal.dim
            else -> Pal.text
        }

        init {
            val box = FrameLayout(act).apply {
                background = rounded(Pal.console, act.dp(14).toFloat())
                setPadding(act.dp(12), act.dp(8), act.dp(12), act.dp(8))
            }
            list.apply {
                divider = null
                dividerHeight = 0
                cacheColorHint = Color.TRANSPARENT
                isStackFromBottom = true // short logs sit at the bottom, like a terminal
                transcriptMode = ListView.TRANSCRIPT_MODE_NORMAL // follows new lines only while you're at the bottom
                this.adapter = this@Console.adapter
            }
            box.addView(list, FrameLayout.LayoutParams(MATCH, MATCH))
            box.addView(empty, FrameLayout.LayoutParams(MATCH, WRAP))
            view.add(box, MATCH, 0, 1f)

            val chips = act.hbox(8)
            helpBtn = act.button("help", BtnKind.Tonal) { app.manager.command(id, "help") }
            listBtn = act.button("list", BtnKind.Tonal) { app.manager.command(id, "list") }
            chips.add(helpBtn, WRAP, WRAP)
            chips.add(listBtn, WRAP, WRAP)
            chips.add(View(act), 0, 1, 1f)
            chips.add(act.button("Clear", BtnKind.Text) { rt?.clearLogs() }, WRAP, WRAP)
            view.add(chips)

            val row = act.hbox(8)
            input.apply {
                setSingleLine(true)
                setTextColor(Pal.text)
                setHintTextColor(Pal.dim)
                imeOptions = EditorInfo.IME_ACTION_SEND
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setOnEditorActionListener { _, action, _ ->
                    if (action == EditorInfo.IME_ACTION_SEND) { submit(); true } else false
                }
            }
            row.add(input, 0, WRAP, 1f)
            send = act.button("Send") { submit() }
            row.add(send, WRAP, WRAP)
            view.add(row)
        }

        private fun submit() {
            val c = input.text.toString()
            if (c.isNotBlank()) {
                app.manager.command(id, c)
                input.setText("")
            }
        }

        fun bind(st: RuntimeState) {
            val active = st.isActive
            input.isEnabled = active
            input.hint = if (active) "Type a command (no slash)" else "Start the server to send commands"
            send.enable(active)
            helpBtn.enable(active)
            listBtn.enable(active)
        }

        fun setLogs(newLines: List<String>) {
            lines.clear()
            lines.addAll(newLines)
            empty.visibility = if (lines.isEmpty()) View.VISIBLE else View.GONE
            adapter.notifyDataSetChanged()
        }
    }

    // ------------------------------------------------------------------ settings

    private lateinit var baseline: ServerProfile
    private lateinit var draft: ServerProfile
    private var portText = ""
    private var bedrockText = ""
    private var maxText = ""
    private lateinit var portField: Field
    private lateinit var maxField: Field
    private lateinit var bedrockField: Field
    private lateinit var saveButton: View
    private lateinit var presetChips: ChipGroup
    private lateinit var viewSlider: Slider
    private lateinit var simSlider: Slider

    private fun candidate(): ServerProfile = draft.copy(
        port = portText.toIntOrNull() ?: -1,
        bedrockPort = bedrockText.toIntOrNull() ?: -1,
        maxPlayers = maxText.toIntOrNull() ?: -1,
    )

    /** Re-checks the form with the same rules the web dashboard enforces, and enables Save only when it's valid. */
    private fun refreshSave() {
        val c = candidate()
        val all = app.manager.profiles.value
        val maxError = if (c.maxPlayers !in 1..1000) "1 to 1000" else null
        val portError = ProfileRules.javaPortError(c, all)
        val bedrockError = ProfileRules.bedrockPortError(c, all)
        portField.note(portError)
        maxField.note(maxError)
        bedrockField.note(bedrockError, "Bedrock players connect to your-ip:$bedrockText")
        val valid = portError == null && bedrockError == null && maxError == null && draft.name.isNotBlank()
        saveButton.enable(c != baseline && valid)
    }

    private fun syncPresets() {
        val i = DistancePreset.entries.indexOfFirst { it.view == draft.viewDistance && it.simulation == draft.simulationDistance }
        presetChips.set(i)
    }

    private fun rebuildSettings() {
        (topBlock.parent as? ViewGroup)?.removeView(topBlock)
        settingsColumn.removeAllViews()
        settingsSlot = act.vbox().apply { setPadding(0, act.dp(4), 0, 0) }
        settingsColumn.add(settingsSlot)
        buildSettings()
        showTab(tab) // puts the status block back where it belongs
    }

    private fun buildSettings() {
        val p0 = profile() ?: return
        baseline = app.manager.pullConfig(p0)
        draft = baseline
        portText = baseline.port.toString()
        bedrockText = baseline.bedrockPort.toString()
        maxText = baseline.maxPlayers.toString()
        val col = settingsColumn
        val engines = app.engines.state.value

        col.add(act.section("General"))
        col.add(Field(act, "Server name", draft.name, maxLength = 40) { draft = draft.copy(name = it); refreshSave() }.view)
        col.add(Field(act, "Message of the day", draft.motd, maxLength = 120) { draft = draft.copy(motd = it); refreshSave() }.view)
        val row = act.hbox(12)
        portField = Field(act, "Port", portText, number = true, maxLength = 5) { portText = it; refreshSave() }
        maxField = Field(act, "Max players", maxText, number = true, maxLength = 4) { maxText = it; refreshSave() }
        row.add(portField.view, 0, WRAP, 1f)
        row.add(maxField.view, 0, WRAP, 1f)
        row.gravity = Gravity.TOP
        col.add(row)

        col.add(act.section("Gameplay"))
        col.add(act.text("Game mode", 12.5f, Pal.dim))
        col.add(ChipGroup(act, GameMode.entries.map { it.label }, draft.gameMode.ordinal) {
            draft = draft.copy(gameMode = GameMode.entries[it]); refreshSave()
        }.view)
        col.add(act.text("Difficulty", 12.5f, Pal.dim))
        col.add(ChipGroup(act, Difficulty.entries.map { it.label }, draft.difficulty.ordinal) {
            draft = draft.copy(difficulty = Difficulty.entries[it]); refreshSave()
        }.view)

        col.add(act.section("Performance"))
        col.add(act.hint("Phones are small servers. Lower distances keep things smooth, and older phones need them low."))
        col.add(act.text("Quick pick", 12.5f, Pal.dim))
        presetChips = ChipGroup(act, DistancePreset.entries.map { it.label }, -1) { i ->
            val preset = DistancePreset.entries[i]
            draft = draft.copy(viewDistance = preset.view, simulationDistance = preset.simulation)
            viewSlider.set(preset.view)
            simSlider.set(preset.simulation)
            refreshSave()
        }
        col.add(presetChips.view)
        col.add(act.hint("Joining sends every chunk in view distance, so fewer chunks means a much faster join."))
        viewSlider = Slider(act, "View distance", 2, 24, 1, draft.viewDistance, { "$it chunks" }) {
            draft = draft.copy(viewDistance = it); syncPresets(); refreshSave()
        }
        simSlider = Slider(act, "Simulation distance", 2, 16, 1, draft.simulationDistance, { "$it chunks" }) {
            draft = draft.copy(simulationDistance = it); syncPresets(); refreshSave()
        }
        col.add(viewSlider.view)
        col.add(simSlider.view)
        syncPresets()

        col.add(act.section("Resources"))
        val mem = deviceMemory(act)
        val cores = Runtime.getRuntime().availableProcessors()
        val maxLimit = DeviceTuning.memoryAdvice(mem.totalMb).maxMb
        col.add(act.hint("This phone has ${formatMb(mem.totalMb)} of RAM, ${formatMb(mem.availMb)} free right now."))
        val limitBox = act.vbox(10)
        val limitSlider = Slider(
            act, "Memory limit", 256, maxLimit.coerceAtLeast(384), 128,
            draft.memoryLimitMb.coerceIn(256, maxLimit.coerceAtLeast(384)), { formatMb(it) },
        ) { draft = draft.copy(memoryLimitMb = it); refreshSave() }
        limitBox.add(limitSlider.view)
        limitBox.add(act.text("When the limit is exceeded", 12.5f, Pal.dim))
        limitBox.add(ChipGroup(act, MemoryAction.entries.map { it.label }, draft.memoryAction.ordinal) {
            draft = draft.copy(memoryAction = MemoryAction.entries[it]); refreshSave()
        }.view)
        limitBox.visibility = if (draft.memoryLimitMb > 0) View.VISIBLE else View.GONE
        col.add(act.switchRow(
            "Limit memory",
            "Pumpkin is native code with no heap setting, so Dumpkin+ watches the server's real RAM use and reacts when it goes over.",
            draft.memoryLimitMb > 0,
        ) { on ->
            val start = 1024.coerceAtMost(maxLimit).coerceAtLeast(256)
            draft = draft.copy(memoryLimitMb = if (on) start else 0)
            if (on) limitSlider.set(start)
            limitBox.visibility = if (on) View.VISIBLE else View.GONE
            refreshSave()
        })
        col.add(limitBox)
        col.add(act.text("CPU threads", 12.5f, Pal.dim))
        val threadChoices = listOf("Auto") + (1..cores.coerceAtMost(16)).map { it.toString() }
        col.add(ChipGroup(act, threadChoices, draft.cpuThreads.coerceIn(0, threadChoices.size - 1)) {
            draft = draft.copy(cpuThreads = it); refreshSave()
        }.view)
        col.add(act.switchRow(
            "Restart after a crash",
            "Tries again up to ${ServerRuntime.MAX_AUTO_RESTARTS} times within 5 minutes.",
            draft.autoRestart,
        ) { draft = draft.copy(autoRestart = it); refreshSave() })

        col.add(act.section("Network"))
        col.add(act.text("Packet compression", 12.5f, Pal.dim))
        val compressionHint = act.hint("")
        fun hintFor(c: Compression) = c.hint + ". Compressing chunks is the main CPU cost when someone joins."
        compressionHint.text = hintFor(draft.compression)
        col.add(ChipGroup(act, Compression.entries.map { it.label }, draft.compression.ordinal) {
            draft = draft.copy(compression = Compression.entries[it])
            compressionHint.text = hintFor(draft.compression)
            refreshSave()
        }.view)
        col.add(compressionHint)
        col.add(act.switchRow(
            "Online mode",
            "Verify players with Microsoft accounts. Each join waits on a Mojang lookup; offline mode skips it but lets anyone use any name.",
            draft.onlineMode,
        ) { draft = draft.copy(onlineMode = it); refreshSave() })
        col.add(act.switchRow(
            "Fetch skins and capes", "Turn off to trim work during joins. Players may show default skins.", draft.fetchSkins,
        ) { draft = draft.copy(fetchSkins = it); refreshSave() })
        col.add(act.switchRow(
            "Lightweight server-list ping",
            "Leaves the server icon out of the multiplayer list response so it loads and pings faster.",
            draft.lightPing,
        ) { draft = draft.copy(lightPing = it); refreshSave() })
        bedrockField = Field(act, "Bedrock port", bedrockText, number = true, maxLength = 5) { bedrockText = it; refreshSave() }
        bedrockField.view.visibility = if (draft.bedrock) View.VISIBLE else View.GONE
        col.add(act.switchRow(
            "Bedrock support (experimental)",
            "Lets Bedrock players join on their own port. Give each server a different Bedrock port to run several at once.",
            draft.bedrock,
        ) { on ->
            draft = draft.copy(bedrock = on)
            bedrockField.view.visibility = if (on) View.VISIBLE else View.GONE
            refreshSave()
        })
        col.add(bedrockField.view)

        col.add(act.section("Engine"))
        if (engines.installed.isEmpty()) {
            col.add(act.text("No engine installed yet. See the Engine tab.", 14f))
        } else {
            val labels = listOf("Newest") + engines.installed.map { it.label }
            val sel = draft.engineId?.let { id -> engines.installed.indexOfFirst { it.id == id } + 1 } ?: 0
            col.add(ChipGroup(act, labels, sel.coerceAtLeast(0)) { i ->
                draft = draft.copy(engineId = if (i == 0) null else engines.installed[i - 1].id)
                refreshSave()
            }.view)
        }

        saveButton = act.button("Save changes") {
            val c = candidate()
            app.manager.update(c)
            baseline = c
            refreshSave()
            act.toast(if (rt?.state?.value?.isActive == true) "Saved. Restart the server to apply." else "Saved")
        }
        col.add(saveButton)
        refreshSave()

        col.add(act.section("Advanced"))
        col.add(act.button("Edit pumpkin.toml", BtnKind.Outline) { showRaw() })
        col.add(act.button("Reset configuration", BtnKind.Outline) {
            if (rt?.state?.value?.isActive == true) {
                act.toast("Stop the server first.")
            } else {
                act.confirm(
                    "Reset configuration?",
                    "pumpkin.toml is rewritten from the settings above. Your world is not touched.",
                    "Reset",
                ) {
                    app.manager.resetConfig(baseline)
                    act.toast("Configuration reset")
                }
            }
        })
        col.add(act.button("Delete server and world", BtnKind.Danger) {
            if (rt?.state?.value?.isActive == true) {
                act.toast("Stop the server first.")
            } else {
                act.confirm(
                    "Delete \"${baseline.name}\"?",
                    "The server, its world and all of its files will be permanently removed from this phone. This can't be undone.",
                    "Delete",
                ) {
                    app.manager.delete(id)
                    act.closeServer()
                }
            }
        })
    }

    private fun showRaw() {
        val edit = EditText(act).apply {
            setText(app.manager.readRawConfig(this@ServerScreen.id))
            hint = "The file is created the first time the server starts."
            setTextColor(Pal.text)
            setHintTextColor(Pal.dim)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 10
            maxLines = 18
            isVerticalScrollBarEnabled = true
        }
        act.dialogBuilder()
            .setTitle("pumpkin.toml")
            .setView(LinearLayout(act).apply {
                setPadding(act.dp(20), act.dp(8), act.dp(20), 0)
                addView(edit, LinearLayout.LayoutParams(MATCH, WRAP))
            })
            .setPositiveButton("Save") { _, _ ->
                app.manager.writeRawConfig(id, edit.text.toString())
                val pulled = app.manager.pullConfig(profile() ?: return@setPositiveButton)
                app.manager.update(pulled)
                rebuildSettings()
                act.toast("Configuration saved")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
