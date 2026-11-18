package app.dumpkinplus.ui

import android.app.AlertDialog
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import app.dumpkinplus.MainActivity
import app.dumpkinplus.backup.Backup
import app.dumpkinplus.backup.BackupManager
import app.dumpkinplus.dumpkin
import app.dumpkinplus.files.FileAccessException
import app.dumpkinplus.files.FileEntry
import app.dumpkinplus.files.ServerFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun stamp(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

/** A tappable row: a title, a smaller line under it, and buttons on the right. */
private fun MainActivity.itemRow(title: String, sub: String, onClick: (() -> Unit)?, vararg actions: View): View {
    val row = hbox(8).apply {
        background = if (onClick != null) ripple(rounded(Pal.surfaceHi, dp(14).toFloat())) else rounded(Pal.surfaceHi, dp(14).toFloat())
        setPadding(dp(14), dp(8), dp(8), dp(8))
        if (onClick != null) {
            isClickable = true
            setOnClickListener { onClick() }
        }
    }
    val col = vbox(1)
    col.add(text(title, 14.5f, bold = true).apply { ellipsize = TextUtils.TruncateAt.MIDDLE; setSingleLine(true) })
    col.add(text(sub, 12f, Pal.dim).apply { ellipsize = TextUtils.TruncateAt.MIDDLE; setSingleLine(true) })
    row.add(col, 0, WRAP, 1f)
    actions.forEach { row.add(it, WRAP, WRAP) }
    return row
}

/** The Backups tab of a server: back up now, automatic backups, and the saved backups with restore, export and delete. */
class BackupsPane(private val act: MainActivity, private val id: String, private val isRunning: () -> Boolean) {
    val view: LinearLayout = act.vbox(14)
    private val app = act.dumpkin
    private val statusBox = act.card(14, 6, Pal.surfaceHi)
    private val statusText = act.text("", 14f)
    private val spinner = ProgressBar(act).apply { isIndeterminate = true }
    private val list = act.vbox(8)
    private lateinit var backBtn: View
    private lateinit var importBtn: View

    init {
        val top = act.card(16, 10)
        top.add(act.text("Backups", 17f, bold = true))
        top.add(
            act.hint(
                "Zip copies of this server's whole folder (world and settings), kept on this phone. Stop the server first " +
                    "for a clean copy, and export one to a computer to keep it safe."
            )
        )
        backBtn = act.button("Back up now") { backupNow() }
        top.add(backBtn, WRAP, WRAP)
        val profile = app.manager.profiles.value.firstOrNull { it.id == id }
        top.add(
            act.switchRow(
                "Back up automatically when the server stops",
                "Keeps the newest ${BackupManager.AUTO_KEEP} automatic backups. Manual and imported backups are never deleted for you.",
                profile?.autoBackup == true,
            ) { on -> app.manager.profiles.value.firstOrNull { it.id == id }?.let { app.manager.update(it.copy(autoBackup = on)) } }
        )
        view.add(top)

        val row = act.hbox(10)
        row.add(spinner, act.dp(24), act.dp(24))
        row.add(statusText, 0, WRAP, 1f)
        statusBox.add(row)
        view.add(statusBox)

        view.add(act.section("Saved backups"))
        view.add(list)
        importBtn = act.button("Import a backup…", BtnKind.Outline) { importBackup() }
        view.add(importBtn, WRAP, WRAP)
        view.add(
            act.hint("Backups are stored in this app's folder, so uninstalling the app deletes them. Export the ones you care about.")
        )
        render()
    }

    fun start(scope: CoroutineScope) {
        scope.launch { app.manager.backups.status.collect { render() } }
    }

    fun refresh() = render()

    private fun render() {
        val st = app.manager.backups.statusOf(id)
        val busy = st.busy != null
        statusBox.visibility = if (busy || st.message != null) View.VISIBLE else View.GONE
        spinner.visibility = if (busy) View.VISIBLE else View.GONE
        statusText.text = st.busy ?: st.message.orEmpty()
        statusText.setTextColor(if (!busy && st.error) Pal.error else Pal.text)
        backBtn.enable(!busy)
        importBtn.enable(!busy)

        list.removeAllViews()
        val backups = app.manager.backups.list(id)
        if (backups.isEmpty()) list.add(act.text("No backups yet.", 14f, Pal.dim))
        for (b in backups) {
            val restore = act.button("Restore", BtnKind.Tonal) { restore(b) }
            restore.enable(!busy)
            val export = act.button("Export", BtnKind.Text) { export(b) }
            val del = act.button("Delete", BtnKind.Text) { delete(b) }
            del.enable(!busy)
            list.add(act.itemRow(stamp(b.createdAt) + " · " + b.kind.label, formatBytes(b.sizeBytes), null, restore, export, del))
        }
    }

    private fun backupNow() {
        if (isRunning()) {
            act.confirm(
                "Back up a running server?",
                "The world may be written to while it is copied, so the backup could be slightly inconsistent. " +
                    "Stopping the server first is safer.",
                "Back up anyway",
            ) { app.manager.backupNow(id, allowRunning = true)?.let { act.toast(it) } }
        } else {
            app.manager.backupNow(id)?.let { act.toast(it) }
        }
    }

    private fun restore(b: Backup) {
        if (isRunning()) {
            act.toast("Stop the server before restoring a backup.")
            return
        }
        act.confirm(
            "Restore this backup?",
            "The server's files are replaced with the backup from ${stamp(b.createdAt)}. A safety backup of the current files is made first.",
            "Restore",
        ) { app.manager.restoreBackup(id, b.name)?.let { act.toast(it) } }
    }

    private fun delete(b: Backup) {
        act.confirm("Delete this backup?", "${b.name} will be deleted from this phone. This can't be undone.", "Delete") {
            app.manager.backups.delete(id, b.name)
            render()
        }
    }

    private fun export(b: Backup) {
        act.createDocument(b.name, "application/zip") { uri ->
            app.manager.backups.runExport(id, b.name) {
                act.contentResolver.openOutputStream(uri) ?: throw java.io.IOException("Couldn't write there.")
            }
        }
    }

    private fun importBackup() {
        act.pickDocument { uri ->
            app.manager.importBackup(id) { act.contentResolver.openInputStream(uri) }?.let { act.toast(it) }
        }
    }
}

/** The Files tab of a server: browse, open text files, export, add and delete. */
class FilesPane(private val act: MainActivity, private val id: String, private val isRunning: () -> Boolean) {
    val view: LinearLayout = act.vbox(10)
    private val app = act.dumpkin
    private val crumbs = FlowLayout(act, act.dp(2), act.dp(2))
    private val list = act.vbox(8)
    private var path = ""
    private var scope: CoroutineScope? = null

    private fun files(): ServerFiles? = app.manager.files(id)

    init {
        view.add(act.text("Files", 17f, Pal.primary, bold = true))
        view.add(
            act.hint(
                "Everything in this server's folder. Tap a file for options. Deleting needs the server to be stopped."
            )
        )
        val bar = FlowLayout(act, act.dp(8), act.dp(8))
        bar.addView(act.button("Add file…", BtnKind.Outline) { addFile() })
        bar.addView(act.button("New folder", BtnKind.Outline) { newFolder() })
        bar.addView(act.button("Refresh", BtnKind.Text) { render() })
        view.add(bar)
        view.add(crumbs)
        view.add(list)
        render()
    }

    fun start(scope: CoroutineScope) {
        this.scope = scope
    }

    /** Re-reads the folder, e.g. when the tab is opened: the server may have written files since. */
    fun refresh() = render()

    private fun open(rel: String) {
        path = rel
        render()
    }

    private fun render() {
        val f = files() ?: return
        val entries = try {
            f.list(path)
        } catch (e: FileAccessException) {
            if (path.isNotEmpty()) { path = ""; render() } else act.toast(e.message ?: "Couldn't read the folder.")
            return
        }
        // Breadcrumbs: server / world / region
        crumbs.removeAllViews()
        crumbs.addView(crumb("server", "") )
        var acc = ""
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            acc = if (acc.isEmpty()) part else "$acc/$part"
            crumbs.addView(act.text("/", 14f, Pal.dim))
            crumbs.addView(crumb(part, acc))
        }

        list.removeAllViews()
        if (entries.isEmpty()) list.add(act.text("This folder is empty.", 14f, Pal.dim))
        // A world can have thousands of region files; building a row for each would stall an old phone.
        for (e in entries.take(MAX_ROWS)) list.add(row(e))
        if (entries.size > MAX_ROWS) {
            list.add(act.hint("Showing the first $MAX_ROWS of ${entries.size} items. Use the web dashboard for the rest."))
        }
    }

    private fun crumb(label: String, rel: String): View =
        act.text(label, 14.5f, Pal.primary, bold = true).apply {
            setPadding(act.dp(8), act.dp(8), act.dp(8), act.dp(8))
            isClickable = true
            setOnClickListener { open(rel) }
        }

    private fun row(e: FileEntry): View {
        val sub = (if (e.isDir) "Folder" else formatBytes(e.size)) + " · " + stamp(e.modified)
        return act.itemRow(if (e.isDir) e.name + "/" else e.name, sub, { if (e.isDir) open(e.path) else fileMenu(e) })
    }

    private fun fileMenu(e: FileEntry) {
        val f = files() ?: return
        val file = runCatching { f.resolve(e.path) }.getOrNull() ?: return
        val editable = e.size <= ServerFiles.MAX_TEXT_BYTES && f.isText(file)
        val items = buildList {
            if (editable) add("Open / edit")
            add("Export…")
            add("Delete")
        }
        act.dialogBuilder().setTitle(e.name).setItems(items.toTypedArray()) { _, which ->
            when (items[which]) {
                "Open / edit" -> edit(e)
                "Export…" -> export(e)
                else -> delete(e)
            }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun edit(e: FileEntry) {
        val f = files() ?: return
        val text = try {
            f.readText(e.path)
        } catch (x: FileAccessException) {
            act.toast(x.message ?: "Couldn't open that file.")
            return
        }
        val edit = EditText(act).apply {
            setText(text)
            setTextColor(Pal.text)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 10
            maxLines = 18
            isVerticalScrollBarEnabled = true
        }
        act.dialogBuilder()
            .setTitle(e.name)
            .setView(LinearLayout(act).apply {
                setPadding(act.dp(20), act.dp(8), act.dp(20), 0)
                addView(edit, LinearLayout.LayoutParams(MATCH, WRAP))
            })
            .setPositiveButton("Save") { _, _ ->
                try {
                    f.writeText(e.path, edit.text.toString())
                    act.toast(if (isRunning()) "Saved. Restart the server to apply." else "Saved")
                    render()
                } catch (x: FileAccessException) {
                    act.toast(x.message ?: "Couldn't save.")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun delete(e: FileEntry) {
        if (isRunning()) {
            act.toast("Stop the server before deleting its files.")
            return
        }
        act.confirm(
            "Delete ${e.name}?",
            (if (e.isDir) "The folder and everything in it" else "The file") + " will be deleted from this phone. This can't be undone.",
            "Delete",
        ) {
            try {
                files()?.delete(e.path)
                render()
            } catch (x: FileAccessException) {
                act.toast(x.message ?: "Couldn't delete that.")
            }
        }
    }

    private fun export(e: FileEntry) {
        val f = files() ?: return
        val file = runCatching { f.resolve(e.path) }.getOrNull() ?: return
        if (!file.isFile) {
            act.toast("Only files can be exported. Back the server up to export folders.")
            return
        }
        act.createDocument(e.name, "application/octet-stream") { uri ->
            scope?.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        (act.contentResolver.openOutputStream(uri) ?: throw java.io.IOException("Couldn't write there.")).use { out ->
                            file.inputStream().use { it.copyTo(out, 32 * 1024) }
                        }
                    }
                }
                act.toast(if (result.isSuccess) "Exported ${e.name}" else "Export failed: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    private fun addFile() {
        act.pickDocument { uri ->
            val name = displayName(uri) ?: "file"
            scope?.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val input = act.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Couldn't open that file.")
                        input.use { files()?.write(path, name, it) }
                    }
                }
                if (result.isSuccess) render() else act.toast("Couldn't add it: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    private fun displayName(uri: android.net.Uri): String? = runCatching {
        act.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    private fun newFolder() {
        val field = Field(act, "Folder name", "", maxLength = 100) { }
        val box = act.vbox().apply { setPadding(act.dp(20), act.dp(8), act.dp(20), 0) }
        box.add(field.view)
        val dialog = act.dialogBuilder().setTitle("New folder").setView(box)
            .setPositiveButton("Create", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    files()?.mkdir(path, field.text.trim())
                    dialog.dismiss()
                    render()
                } catch (x: FileAccessException) {
                    field.note(x.message)
                }
            }
        }
        dialog.show()
    }

    companion object {
        private const val MAX_ROWS = 300
    }
}
