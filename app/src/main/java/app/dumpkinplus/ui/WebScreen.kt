package app.dumpkinplus.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import app.dumpkinplus.MainActivity
import app.dumpkinplus.server.ServerManager
import app.dumpkinplus.web.WebState
import kotlinx.coroutines.launch

/** Turns the web dashboard on and off and manages its password and port. */
class WebScreen(act: MainActivity) : Screen(act) {
    private val web get() = app.web

    private lateinit var statusTitle: TextView
    private lateinit var statusBody: TextView
    private lateinit var switch: Switch
    private lateinit var addresses: LinearLayout
    private lateinit var passwordText: TextView
    private lateinit var showHide: View
    private lateinit var portField: Field
    private lateinit var sessionsText: TextView
    private lateinit var signOut: View
    private var reveal = false
    private var urlsKey: List<String>? = null
    private var quiet = false

    override fun build(): View {
        val page = act.vbox(12).apply { setPadding(act.dp(16), act.dp(20), act.dp(16), act.dp(24)) }
        page.add(act.text("Dashboard", 28f, bold = true))
        page.add(act.text("Manage servers from any browser", 14f, Pal.dim))

        val status = act.card(16, 8, Pal.surfaceHi)
        val top = act.hbox(12)
        val col = act.vbox(2)
        statusTitle = act.text("", 17f, bold = true)
        statusBody = act.text("", 13.5f, Pal.dim)
        col.add(statusTitle)
        col.add(statusBody)
        top.add(col, 0, WRAP, 1f)
        switch = Switch(act).apply {
            setOnCheckedChangeListener { _, on -> if (!quiet) web.setEnabled(on) }
        }
        top.add(switch, WRAP, WRAP)
        status.add(top)
        page.add(status)

        page.add(act.section("Open it at"))
        addresses = act.vbox(8)
        page.add(addresses)
        page.add(act.button("Open on this phone", BtnKind.Outline) {
            runCatching {
                act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:${web.state.value.port}")))
            }.onFailure { act.toast("No browser found on this phone.") }
        }, WRAP, WRAP)

        page.add(act.section("Password"))
        val pw = act.card(16, 10)
        passwordText = act.text("", 20f, bold = true, mono = true)
        pw.add(passwordText)
        val pwRow = act.hbox(8)
        showHide = act.button("Show", BtnKind.Tonal) {
            reveal = !reveal
            showPassword()
            (showHide as android.widget.Button).text = if (reveal) "Hide" else "Show"
        }
        pwRow.add(showHide, WRAP, WRAP)
        pwRow.add(act.button("Copy", BtnKind.Tonal) {
            act.copyToClipboard("Dashboard password", web.password())
            act.toast("Password copied")
        }, WRAP, WRAP)
        pw.add(pwRow)
        val pwRow2 = act.hbox(8)
        pwRow2.add(act.button("New password", BtnKind.Tonal) {
            act.confirm("Create a new password?", "Every browser that's signed in will be signed out.", "New password") {
                web.regeneratePassword()
                showPassword()
                act.toast("New password created")
            }
        }, WRAP, WRAP)
        pwRow2.add(act.button("Set your own", BtnKind.Outline) { showCustomPassword() }, WRAP, WRAP)
        pw.add(pwRow2)
        page.add(pw)

        page.add(act.section("Port"))
        val portCard = act.card(16, 8)
        portField = Field(act, "Dashboard port", web.state.value.port.toString(), number = true, maxLength = 5) { }
        portCard.add(portField.view)
        portCard.add(act.button("Apply", BtnKind.Tonal) {
            val v = portField.text.toIntOrNull()
            val problem = web.setPort(v ?: -1)
            if (problem != null) portField.note(problem) else {
                portField.note(null)
                act.toast("Port changed to $v")
            }
        }, WRAP, WRAP)
        page.add(portCard)

        val sessions = act.hbox(8)
        sessionsText = act.text("", 14f, Pal.dim)
        sessions.add(sessionsText, 0, WRAP, 1f)
        signOut = act.button("Sign out all", BtnKind.Text) {
            web.signOutAll()
            act.toast("Signed everyone out")
        }
        sessions.add(signOut, WRAP, WRAP)
        page.add(sessions)

        val warn = act.card(16, 4, if (Pal.dark) 0xFF4A2D12.toInt() else 0xFFFFE3C2.toInt())
        warn.add(act.text("Keep it on your home network", 15f, bold = true))
        warn.add(
            act.text(
                "The dashboard uses plain HTTP, so anyone on the same Wi-Fi who learns the password can start, stop and delete your " +
                    "servers. Don't forward its port to the internet. While it is on, Dumpkin+ stays running in the background to " +
                    "answer requests, which uses some battery.",
                13.5f,
            )
        )
        page.add(warn)

        showPassword()
        return ScrollView(act).apply { addView(page) }
    }

    override fun onStart() {
        scope.launch { web.state.collect { render(it) } }
    }

    private fun showPassword() {
        val p = web.password()
        passwordText.text = if (reveal) p else "•".repeat(minOf(p.length, 14))
    }

    private fun render(st: WebState) {
        statusTitle.text = when {
            st.error != null -> "Couldn't start"
            st.running -> "Dashboard is online"
            else -> "Dashboard is off"
        }
        statusBody.text = st.error
            ?: if (st.running) "Open it from a browser on the same Wi-Fi." else "Turn it on to create and manage servers from a computer."
        quiet = true
        switch.isChecked = st.enabled
        quiet = false
        sessionsText.text = when (st.sessions) {
            0 -> "No browsers signed in"
            1 -> "1 browser signed in"
            else -> "${st.sessions} browsers signed in"
        }
        signOut.enable(st.sessions > 0)

        val urls = if (st.running) ServerManager.localAddresses().map { "http://$it:${st.port}" } else emptyList()
        if (urls != urlsKey) {
            urlsKey = urls
            addresses.removeAllViews()
            if (urls.isEmpty()) {
                addresses.add(
                    act.text(
                        if (st.running) "No network address found. Connect to Wi-Fi, then reopen this tab."
                        else "Turn the dashboard on to see its address.",
                        13.5f, Pal.dim,
                    )
                )
            }
            urls.forEach { url ->
                val row = act.hbox(8)
                row.add(act.text(url, 16f, bold = true, mono = true), 0, WRAP, 1f)
                row.add(act.button("Copy", BtnKind.Tonal) {
                    act.copyToClipboard("Dashboard address", url)
                    act.toast("Copied $url")
                }, WRAP, WRAP)
                addresses.add(row)
            }
        }
    }

    private fun showCustomPassword() {
        val field = Field(act, "New password", "", maxLength = 64) { }
        field.note(null, "8 to 64 characters. Everyone is signed out when it changes.")
        val box = act.vbox().apply { setPadding(act.dp(20), act.dp(8), act.dp(20), 0) }
        box.add(field.view)
        val dialog = act.dialogBuilder()
            .setTitle("Set your own password")
            .setView(box)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val problem = web.setPassword(field.text)
                if (problem != null) {
                    field.note(problem)
                } else {
                    dialog.dismiss()
                    showPassword()
                    act.toast("Password changed")
                }
            }
        }
        dialog.show()
    }
}
