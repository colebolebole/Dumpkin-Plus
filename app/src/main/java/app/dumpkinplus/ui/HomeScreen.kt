package app.dumpkinplus.ui

import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import app.dumpkinplus.MainActivity
import app.dumpkinplus.data.DEFAULT_BEDROCK_PORT
import app.dumpkinplus.data.DistancePreset
import app.dumpkinplus.data.GameMode
import app.dumpkinplus.data.ProfileRules
import app.dumpkinplus.data.ServerProfile
import app.dumpkinplus.data.Status
import app.dumpkinplus.data.deviceMemory
import app.dumpkinplus.server.ServerManager
import app.dumpkinplus.server.ServerRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The server list. Each card follows its own server's state, so only the card that changed is touched. */
class HomeScreen(act: MainActivity) : Screen(act) {
    private lateinit var subtitle: android.widget.TextView
    private lateinit var list: LinearLayout
    private var cardScope: CoroutineScope? = null

    override fun build(): View {
        val page = act.vbox(12).apply { setPadding(act.dp(16), act.dp(20), act.dp(16), act.dp(24)) }
        val head = act.vbox(2)
        head.add(act.text("Dumpkin+", 28f, bold = true))
        subtitle = act.text("Host Pumpkin servers from your phone", 14f, Pal.dim)
        head.add(subtitle)
        page.add(head)
        list = act.vbox(12)
        page.add(list)
        page.add(act.button("New server") { showCreate() })
        return ScrollView(act).apply { isFillViewport = false; addView(page) }
    }

    override fun onStart() {
        scope.launch {
            combine(app.manager.profiles, app.engines.state, app.manager.active) { p, e, a -> Triple(p, e, a) }
                .collect { (profiles, engine, active) ->
                    val ip = if (active.isNotEmpty()) ServerManager.localAddresses().firstOrNull() else null
                    subtitle.text = if (active.isNotEmpty()) "${active.size} online" + (ip?.let { " · $it" } ?: "")
                    else "Host Pumpkin servers from your phone"
                    rebuild(profiles, engine.installed.isEmpty())
                }
        }
    }

    override fun onStop() {
        cardScope?.cancel()
        cardScope = null
    }

    private fun rebuild(profiles: List<ServerProfile>, noEngine: Boolean) {
        cardScope?.cancel()
        val cs = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        cardScope = cs
        list.removeAllViews()
        if (noEngine) list.add(engineBanner())
        if (profiles.isEmpty()) {
            val empty = act.vbox(6).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, act.dp(32), 0, act.dp(32))
            }
            empty.add(act.text("No servers yet", 20f, bold = true).apply { gravity = Gravity.CENTER })
            empty.add(act.text("Create one and your phone becomes a Minecraft host.", 14f, Pal.dim).apply { gravity = Gravity.CENTER })
            list.add(empty)
        }
        for (p in profiles) {
            val rt = app.manager.runtime(p.id) ?: continue
            list.add(serverCard(p, rt, cs))
        }
    }

    private fun engineBanner(): View {
        val c = act.card(16, 8, Pal.surfaceHi)
        val supported = app.engines.supported
        val official = app.engines.officialBuildAvailable
        c.add(act.text(if (supported) "Get the Pumpkin engine" else "This phone can't run Pumpkin", 17f, bold = true))
        c.add(
            act.text(
                when {
                    official -> "Servers need the Pumpkin server binary. It's a one-time download from the official GitHub releases."
                    app.engines.needsNewerAndroid -> "Pumpkin's official build needs Android 7.0 or newer, and this phone has an older Android. " +
                        "You can still run a server if you import an engine built for older Android."
                    supported -> "Pumpkin only publishes engines for arm64 phones, and this one is ${app.engines.arch.label}. " +
                        "You can still run a server if you build or find an engine for ${app.engines.arch.label} and import it."
                    else -> "This phone's CPU isn't one Pumpkin can run on."
                },
                13.5f, Pal.dim,
            )
        )
        if (supported) c.add(act.button(if (official) "Open Engine" else "Open Engine to import") { act.setTab(1) }, WRAP, WRAP)
        return c
    }

    private fun serverCard(p: ServerProfile, rt: ServerRuntime, cs: CoroutineScope): View {
        val card = act.hbox(14).apply {
            background = ripple(rounded(Pal.surface, act.dp(14).toFloat()))
            setPadding(act.dp(14), act.dp(14), act.dp(14), act.dp(14))
            isClickable = true
            setOnClickListener { act.openServer(p.id) }
        }
        val dot = StatusDot(act, 22)
        card.addView(dot)
        val col = act.vbox(1)
        col.add(act.text(p.name, 17f, bold = true).apply { setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END })
        val status = col.add(act.text("", 13.5f, Pal.dim))
        col.add(act.text("Port ${p.port} · ${p.gameMode.label} · ${p.difficulty.label}", 12.5f, Pal.dim))
        card.add(col, 0, WRAP, 1f)
        val slot = act.hbox().apply { minimumWidth = act.dp(76) ; gravity = Gravity.CENTER }
        card.add(slot, WRAP, WRAP)

        cs.launch {
            rt.state.collect { st ->
                dot.set(st.displayStatus)
                val running = st.status == Status.Running
                status.text = statusLabel(st) + if (running) " · ${st.online}/${st.max} players" else ""
                status.setTextColor(if (running) Pal.ok else Pal.dim)
                slot.removeAllViews()
                when (st.displayStatus) {
                    Status.Starting, Status.Stopping ->
                        slot.addView(ProgressBar(act).apply { isIndeterminate = true }, LinearLayout.LayoutParams(act.dp(32), act.dp(32)))
                    else -> slot.addView(
                        act.button(if (st.isActive) "Stop" else "Start", if (st.isActive) BtnKind.Tonal else BtnKind.Primary) {
                            if (rt.state.value.isActive) app.manager.stop(p.id)
                            else app.manager.start(p.id)?.let { act.toast(it) }
                        },
                        LinearLayout.LayoutParams(WRAP, WRAP),
                    )
                }
            }
        }
        return card
    }

    private fun showCreate() {
        val existing = app.manager.profiles.value
        val claimed = ProfileRules.claimedPorts(existing)
        var name = "My Pumpkin Server"
        var portText = ProfileRules.nextFreePort(claimed).toString()
        var mode = GameMode.Survival
        var bedrock = false
        var bedrockText = ProfileRules.nextFreePort(claimed + (portText.toIntOrNull() ?: 0), DEFAULT_BEDROCK_PORT).toString()

        val body = act.vbox(10).apply { setPadding(act.dp(20), act.dp(8), act.dp(20), 0) }
        lateinit var dialog: AlertDialog
        lateinit var portField: Field
        lateinit var bedrockField: Field

        fun validate() {
            val candidate = ServerProfile(
                id = "new", name = name.trim(), port = portText.toIntOrNull() ?: -1,
                bedrock = bedrock, bedrockPort = bedrockText.toIntOrNull() ?: -1,
            )
            val portError = ProfileRules.javaPortError(candidate, existing)
            val bedrockError = ProfileRules.bedrockPortError(candidate, existing)
            portField.note(portError, "Players connect to your-ip:$portText")
            bedrockField.note(bedrockError, "Bedrock players connect to your-ip:$bedrockText")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.enable(name.isNotBlank() && portError == null && bedrockError == null)
        }

        body.add(Field(act, "Name", name, maxLength = 40) { name = it; validate() }.view)
        portField = Field(act, "Port", portText, number = true, maxLength = 5) { portText = it; validate() }
        body.add(portField.view)
        body.add(act.text("Game mode", 12.5f, Pal.dim))
        body.add(ChipGroup(act, GameMode.entries.map { it.label }, mode.ordinal) { mode = GameMode.entries[it] }.view)
        val bedrockRow = act.switchRow(
            "Bedrock support", "Experimental. Lets Bedrock players join on their own port.", false,
        ) { on ->
            bedrock = on
            bedrockField.view.visibility = if (on) View.VISIBLE else View.GONE
            validate()
        }
        body.add(bedrockRow)
        bedrockField = Field(act, "Bedrock port", bedrockText, number = true, maxLength = 5) { bedrockText = it; validate() }
        bedrockField.view.visibility = View.GONE
        body.add(bedrockField.view)

        dialog = act.dialogBuilder()
            .setTitle("New server")
            .setView(ScrollView(act).apply { addView(body) })
            .setPositiveButton("Create", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            validate()
            // Set here (not in the builder) so a click on an invalid form doesn't close the dialog.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val mem = deviceMemory(act).totalMb
                val profile = ServerProfile.forDevice(mem).copy(
                    name = name.trim(), port = portText.toInt(), gameMode = mode,
                    bedrock = bedrock, bedrockPort = bedrockText.toIntOrNull() ?: DEFAULT_BEDROCK_PORT,
                )
                app.manager.create(profile)
                dialog.dismiss()
                act.openServer(profile.id)
            }
        }
        dialog.show()
    }
}
