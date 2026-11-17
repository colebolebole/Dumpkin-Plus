package app.dumpkinplus.ui

import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import app.dumpkinplus.MainActivity
import app.dumpkinplus.engine.EngineUiState
import app.dumpkinplus.engine.NEEDS_NEWER_ANDROID
import app.dumpkinplus.engine.InstalledEngine
import app.dumpkinplus.engine.RemoteEngine
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Installed and downloadable Pumpkin builds, plus importing one from a file. */
class EngineScreen(act: MainActivity) : Screen(act) {
    private lateinit var list: LinearLayout

    override fun build(): View {
        list = act.vbox(12).apply { setPadding(act.dp(16), act.dp(20), act.dp(16), act.dp(24)) }
        return ScrollView(act).apply { addView(list) }
    }

    override fun onStart() {
        if (app.engines.state.value.remote.isEmpty() && app.engines.hasRemote) app.engines.checkRemote()
        scope.launch {
            combine(app.engines.state, app.manager.active) { s, a -> s to a.isNotEmpty() }
                .collect { (s, busy) -> render(s, busy) }
        }
    }

    private fun render(state: EngineUiState, serversActive: Boolean) {
        list.removeAllViews()
        val supported = app.engines.supported
        val official = app.engines.officialBuildAvailable

        val head = act.hbox(8)
        val titles = act.vbox(2)
        titles.add(act.text("Engine", 28f, bold = true))
        titles.add(act.text("Pumpkin server builds", 14f, Pal.dim))
        head.add(titles, 0, WRAP, 1f)
        if (state.checking) head.addView(ProgressBar(act).apply { isIndeterminate = true }, LinearLayout.LayoutParams(act.dp(32), act.dp(32)))
        else if (app.engines.hasRemote) head.add(act.button("Check for updates", BtnKind.Tonal) { app.engines.checkRemote() }, WRAP, WRAP)
        list.add(head)
        if (app.engines.hasRemote) list.add(checkStatus(state))
        if (state.updates.isNotEmpty()) list.add(updateBanner(state, supported))

        if (!supported) {
            list.add(info("Unsupported CPU", "Pumpkin can't run on this phone's processor (${app.engines.arch.label}).", true))
        } else if (app.engines.communityBuildAvailable) {
            list.add(
                info(
                    "Using the unofficial 32-bit build",
                    (if (app.engines.needsNewerAndroid) "Pumpkin's official build needs Android 7.0 or newer. " else "Pumpkin doesn't publish a 32-bit ARM build. ") +
                        "This phone can run 32-bit ARM programs, so it is offered an unofficial 32-bit build made for Android 5.0 and newer. " +
                        "It hasn't been tested on many phones yet; if it fails, the console says why.",
                    false,
                )
            )
        } else if (app.engines.needsNewerAndroid) {
            list.add(info("Official build needs Android 7.0+", NEEDS_NEWER_ANDROID, true))
        } else if (!official) {
            list.add(
                info(
                    "No official build for this phone",
                    "Pumpkin only publishes Android builds for arm64. This phone is ${app.engines.arch.label}, so there is nothing to " +
                        "download. If you have an engine built for ${app.engines.arch.label}, import it below.",
                    false,
                )
            )
        }
        state.message?.let { list.add(info("Heads up", it, true)) }

        list.add(act.section("Installed"))
        if (state.installed.isEmpty()) list.add(act.text("Nothing installed yet.", 14f, Pal.dim))
        state.installed.forEach { list.add(installedCard(it, serversActive)) }

        if (app.engines.hasRemote) {
            list.add(act.section("Available on GitHub"))
            state.remote.forEach { r -> list.add(remoteCard(r, state, supported)) }
            if (state.remote.isEmpty() && !state.checking && state.message == null) {
                list.add(act.text("Tap Refresh to look for builds.", 14f, Pal.dim))
            }
        }

        list.add(act.section("Import an engine file"))
        list.add(
            act.hint(
                "Use this if downloading fails (very old phones can't verify GitHub's certificate), or to run a build you made " +
                    "yourself. Copy the file to the phone, then pick it here. It is checked against this phone's CPU first."
            )
        )
        list.add(
            if (state.importing) act.text("Importing…", 14f, Pal.primary)
            else act.button("Import from file…", BtnKind.Outline) { act.pickEngineFile() }
        )

        list.add(act.section("About"))
        list.add(
            act.text(
                "Dumpkin+ is an unofficial host for Pumpkin, a Minecraft server written in Rust (GPL-3.0). Pumpkin is still under " +
                    "heavy development: not every vanilla mechanic exists yet and worlds can break between versions, so keep backups. " +
                    "Not affiliated with Mojang or Microsoft.",
                13.5f, Pal.dim,
            )
        )
        list.add(act.button("pumpkinmc.org", BtnKind.Outline) {
            runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://pumpkinmc.org/"))) }
        }, WRAP, WRAP)
    }

    private fun info(title: String, body: String, error: Boolean): View {
        val c = act.card(16, 4, if (error) (if (Pal.dark) 0xFF4A2220.toInt() else 0xFFFADAD6.toInt()) else Pal.surfaceHi)
        c.add(act.text(title, 15f, bold = true))
        c.add(act.text(body, 13.5f))
        return c
    }

    private fun installedCard(e: InstalledEngine, serversActive: Boolean): View {
        val c = act.hbox(8).apply {
            background = rounded(Pal.surfaceHi, act.dp(14).toFloat())
            setPadding(act.dp(16), act.dp(10), act.dp(8), act.dp(10))
        }
        val col = act.vbox(1)
        col.add(act.text(e.label, 15f, bold = true))
        col.add(act.text(listOf(e.channel, formatBytes(e.sizeBytes)).filter { it.isNotBlank() }.joinToString(" · "), 12.5f, Pal.dim))
        c.add(col, 0, WRAP, 1f)
        val del = act.button("Delete", BtnKind.Text) {
            act.confirm("Delete ${e.label}?", "Servers using this build will fall back to the newest installed one.", "Delete") {
                app.engines.delete(e.id)
            }
        }
        del.enable(!serversActive)
        c.add(del, WRAP, WRAP)
        return c
    }

    private fun remoteCard(r: RemoteEngine, state: EngineUiState, enabled: Boolean): View {
        val c = act.card(16, 8)
        val installed = state.installed.any { it.id == r.id }
        val isUpdate = state.updates.any { it.id == r.id }
        val downloading = state.downloadingId == r.id
        val busy = state.downloadingId != null || state.importing
        c.add(act.text("${r.channel.label} · ${r.tag}" + if (isUpdate) "  ·  update" else "", 15f, if (isUpdate) Pal.primary else Pal.text, bold = true))
        c.add(act.text((if (r.prerelease) "Pre-release. " else "") + r.channel.blurb, 12.5f, Pal.dim))
        val meta = listOfNotNull(r.updated.take(10).takeIf { it.isNotBlank() }, r.size.takeIf { it > 0 }?.let { formatBytes(it) })
        if (meta.isNotEmpty()) c.add(act.text(meta.joinToString(" · "), 12.5f, Pal.dim))
        if (downloading) {
            c.add(ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progress = (state.progress * 1000).toInt()
            }, MATCH, act.dp(10))
        } else {
            val b = act.button(
                if (isUpdate) "Update" else if (installed) "Download again" else "Download",
                if (installed && !isUpdate) BtnKind.Outline else BtnKind.Primary,
            ) {
                app.engines.download(r)
            }
            b.enable(enabled && !busy)
            c.add(b, WRAP, WRAP, 0f)
        }
        return c
    }

    /** "Last checked 3 h ago" plus the switch for checking by itself. */
    private fun checkStatus(state: EngineUiState): View {
        val box = act.vbox(6)
        box.add(act.hint(if (state.lastChecked == 0L) "Not checked yet." else "Last checked " + ago(System.currentTimeMillis() - state.lastChecked) + "."))
        box.add(act.switchRow("Check for updates automatically", "When the app starts, at most every 6 hours.", app.engines.autoCheck) { app.engines.autoCheck = it })
        return box
    }

    /** Names what is newer than what is installed, with a one-tap update for it. */
    private fun updateBanner(state: EngineUiState, enabled: Boolean): View {
        val c = act.card(16, 6, if (Pal.dark) 0xFF4A2D12.toInt() else 0xFFFFE3C2.toInt())
        c.add(act.text(if (state.updates.size == 1) "Update available" else "Updates available", 17f, bold = true))
        val busy = state.downloadingId != null || state.importing
        for (r in state.updates) {
            val have = state.installed.firstOrNull { it.channel == r.channel.label }?.tag
            val what = r.label + if (have != null && have != r.tag) "  (you have $have)" else if (have == r.tag) "  (rebuilt: new file, same version)" else ""
            c.add(act.text(what, 14f))
            if (r.prerelease) c.add(act.hint("This is a pre-release."))
            val b = act.button(if (state.downloadingId == r.id) "Downloading…" else "Update", BtnKind.Primary) { app.engines.download(r) }
            b.enable(enabled && !busy)
            c.add(b, WRAP, WRAP)
        }
        return c
    }

    private fun ago(ms: Long): String {
        val m = ms / 60_000
        return when {
            m < 1 -> "just now"
            m < 60 -> "$m min ago"
            m < 48 * 60 -> "${m / 60} h ago"
            else -> "${m / (24 * 60)} days ago"
        }
    }
}
