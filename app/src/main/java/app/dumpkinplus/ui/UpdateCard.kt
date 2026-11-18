package app.dumpkinplus.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import app.dumpkinplus.MainActivity
import app.dumpkinplus.dumpkin
import app.dumpkinplus.update.AppUpdateState

/** The "App updates" card: version, check now, the newest release with its notes, and the update button. */
class UpdateCard(private val act: MainActivity) {
    val view: LinearLayout = act.card(16, 8)
    private val updater = act.dumpkin.updater

    fun bind(st: AppUpdateState) {
        view.removeAllViews()
        view.add(act.text("Dumpkin+ ${st.current}", 17f, bold = true))

        val r = st.available
        when {
            st.checking -> view.add(act.text("Checking for updates…", 14f, Pal.dim))
            r != null -> {
                view.add(
                    act.text(
                        "Update available: ${r.version}" + if (r.prerelease) " (pre-release)" else "",
                        15f, Pal.primary, bold = true,
                    )
                )
                val meta = listOfNotNull(r.published.take(10).takeIf { it.isNotBlank() }, r.size.takeIf { it > 0 }?.let { formatBytes(it) })
                if (meta.isNotEmpty()) view.add(act.hint(meta.joinToString(" · ")))
                if (r.notes.isNotBlank()) view.add(act.text(r.notes.lines().take(12).joinToString("\n").take(700), 13f, Pal.dim))
            }
            else -> view.add(
                act.text(
                    st.message ?: if (st.lastChecked == 0L) "Not checked yet." else "You have the newest version.",
                    14f, if (st.message != null && r == null && st.lastChecked == 0L) Pal.error else Pal.dim,
                )
            )
        }
        if (r != null) st.message?.let { view.add(act.text(it, 13.5f, Pal.error)) }

        val progress = st.progress
        if (progress != null) {
            view.add(
                ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000
                    this.progress = (progress * 1000).toInt()
                },
                MATCH, act.dp(10),
            )
            view.add(act.text("Downloading ${(progress * 100).toInt()}%", 13f, Pal.dim))
        } else if (st.installing) {
            view.add(act.text("Waiting for Android's installer. Confirm the update on the next screen.", 13.5f, Pal.dim))
        }

        val busy = st.checking || progress != null || st.installing
        val row = FlowLayout(act, act.dp(8), act.dp(8))
        if (r != null) {
            val update = act.button("Update", BtnKind.Primary) { updater.downloadAndInstall() }
            update.enable(!busy)
            row.addView(update)
        }
        if (st.needsInstallPermission) {
            row.addView(act.button("Allow installs", BtnKind.Outline) {
                runCatching { act.startActivity(updater.installPermissionIntent()) }
                    .onFailure { act.toast("Open Android settings and allow Dumpkin+ to install apps.") }
            })
        }
        val check = act.button("Check for updates", BtnKind.Tonal) { updater.check() }
        check.enable(!busy)
        row.addView(check)
        view.add(row)

        view.add(
            act.switchRow(
                "Check automatically",
                "When the app starts, at most every 12 hours. Only GitHub's release list is read.",
                updater.autoCheck,
            ) { updater.autoCheck = it }
        )
        view.add(
            act.switchRow(
                "Include pre-releases",
                "Dumpkin+ is in alpha, so its releases are pre-releases. Turn off to wait for stable versions.",
                updater.includePrereleases,
            ) { updater.includePrereleases = it }
        )
        view.add(
            act.hint(
                "Updates install over this app and keep your servers. They must be signed with the same key; if not, " +
                    "Android refuses and nothing changes."
            )
        )
    }
}

/** A one-line "update available" banner for the server list. */
fun MainActivity.updateBanner(st: AppUpdateState): View? {
    val r = st.available ?: return null
    val c = card(14, 4, if (Pal.dark) 0xFF4A2D12.toInt() else 0xFFFFE3C2.toInt())
    c.add(text("Dumpkin+ ${r.version} is available", 15f, bold = true))
    c.add(text("You have ${st.current}. Open Tuning to update.", 13f, Pal.dim))
    c.add(button("Open Tuning", BtnKind.Primary) { setTab(2) }, WRAP, WRAP)
    return c
}
