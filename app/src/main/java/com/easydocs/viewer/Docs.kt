package com.easydocs.viewer

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.widget.TextView
import android.widget.Toolbar
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

enum class DocKind { PDF, EXCEL }

/** File-type helpers. */
object Docs {
    val PDF_MIMES = arrayOf("application/pdf")
    val EXCEL_MIMES = arrayOf(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-excel",
        "application/vnd.ms-excel.sheet.macroEnabled.12",
    )

    /** Small pool for one-off background work (file probing, PDF open). */
    val bg = Executors.newFixedThreadPool(2)
    val main = Handler(Looper.getMainLooper())

    fun kindForMime(mime: String?): DocKind? = when {
        mime == null -> null
        mime in PDF_MIMES -> DocKind.PDF
        mime in EXCEL_MIMES -> DocKind.EXCEL
        else -> null
    }

    fun kindForName(name: String): DocKind? {
        val n = name.lowercase()
        return when {
            n.endsWith(".pdf") -> DocKind.PDF
            n.endsWith(".xlsx") || n.endsWith(".xls") || n.endsWith(".xlsm") -> DocKind.EXCEL
            else -> null
        }
    }

    fun displayName(ctx: Context, uri: Uri): String {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            try {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getString(0)
                        if (!n.isNullOrBlank()) return n
                    }
                }
            } catch (_: Exception) {
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Document"
    }

    /** Works out the document type from MIME type, file name, then file signature. Blocking. */
    fun detectKind(ctx: Context, uri: Uri, mimeHint: String? = null): DocKind? {
        kindForMime(mimeHint)?.let { return it }
        val mime = try { ctx.contentResolver.getType(uri) } catch (_: Exception) { null }
        kindForMime(mime)?.let { return it }
        kindForName(displayName(ctx, uri))?.let { return it }
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { s ->
                val b = ByteArray(1024)
                var n = 0
                while (n < b.size) {
                    val r = s.read(b, n, b.size - n)
                    if (r <= 0) break
                    n += r
                }
                when {
                    n >= 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() -> DocKind.EXCEL
                    n >= 4 && b[0] == 0xD0.toByte() && b[1] == 0xCF.toByte() && b[2] == 0x11.toByte() && b[3] == 0xE0.toByte() -> DocKind.EXCEL
                    String(b, 0, n, Charsets.ISO_8859_1).contains("%PDF-") -> DocKind.PDF
                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Gets the document URI from a VIEW or SEND intent. */
    fun uriFrom(intent: Intent?): Uri? {
        if (intent == null) return null
        intent.data?.let { return it }
        if (intent.action == Intent.ACTION_SEND) {
            val u: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (u != null) return u
            intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri?.let { return it }
        }
        return null
    }

    fun viewerIntent(ctx: Context, uri: Uri, kind: DocKind): Intent =
        Intent(ctx, if (kind == DocKind.PDF) PdfActivity::class.java else ExcelActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(uri)
    // No FLAG_GRANT_READ_URI_PERMISSION: the viewers run in this app, and URI grants are
    // per app, so it isn't needed. Adding it crashes when a recent entry's grant has expired.

    /** Scratch copies of shared files whose streams can't be read randomly. */
    fun tempDir(ctx: Context): File = File(ctx.cacheDir, "docs").apply {
        mkdirs()
        // Remove copies left behind if the app was killed while a document was open.
        val cutoff = System.currentTimeMillis() - 6 * 60 * 60 * 1000L
        listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }
}

/** Recently opened documents, kept in private SharedPreferences. Nothing leaves the device. */
object Recents {
    data class Entry(val uri: Uri, val name: String, val kind: DocKind, val time: Long)

    private const val MAX = 30
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("recents", Context.MODE_PRIVATE)

    fun list(ctx: Context): List<Entry> {
        val raw = prefs(ctx).getString("list", null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val kind = runCatching { DocKind.valueOf(o.getString("k")) }.getOrNull() ?: return@mapNotNull null
                Entry(Uri.parse(o.getString("u")), o.getString("n"), kind, o.getLong("t"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(ctx: Context, list: List<Entry>) {
        val a = JSONArray()
        list.take(MAX).forEach {
            a.put(JSONObject().put("u", it.uri.toString()).put("n", it.name).put("k", it.kind.name).put("t", it.time))
        }
        prefs(ctx).edit().putString("list", a.toString()).apply()
    }

    fun add(ctx: Context, uri: Uri, name: String, kind: DocKind) {
        // Keep access across restarts when the source allows it (Storage Access Framework picks).
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        // The same file can arrive through different URIs (picker, share, "Open with"): keep one entry.
        save(ctx, listOf(Entry(uri, name, kind, System.currentTimeMillis())) +
            list(ctx).filter { it.uri != uri && !(it.name == name && it.kind == kind) })
    }

    fun remove(ctx: Context, uri: Uri) {
        try {
            ctx.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        save(ctx, list(ctx).filter { it.uri != uri })
    }

    fun clear(ctx: Context) {
        list(ctx).forEach {
            try {
                ctx.contentResolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
        prefs(ctx).edit().clear().apply()
    }
}

/** Base for all screens: in-app light/dark choice, edge-to-edge insets, toolbar. */
abstract class BaseActivity : Activity() {

    companion object {
        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2

        fun themeMode(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("theme", THEME_SYSTEM)
        fun setThemeMode(ctx: Context, mode: Int) =
            ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putInt("theme", mode).apply()
    }

    override fun attachBaseContext(newBase: Context) {
        val mode = themeMode(newBase)
        if (mode == THEME_SYSTEM) {
            super.attachBaseContext(newBase)
            return
        }
        val cfg = Configuration(newBase.resources.configuration)
        val night = if (mode == THEME_DARK) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        super.attachBaseContext(newBase.createConfigurationContext(cfg))
    }

    val isDark: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    lateinit var toolbar: Toolbar

    /** Sets up the toolbar and pads [root] for system bars (Android 15+ always draws edge-to-edge). */
    protected fun setupChrome(root: View, title: CharSequence, showBack: Boolean) {
        toolbar = findViewById(R.id.toolbar)
        toolbar.title = title
        if (showBack) {
            toolbar.setNavigationIcon(R.drawable.ic_back)
            toolbar.setNavigationContentDescription(android.R.string.cancel)
            toolbar.setNavigationOnClickListener { finish() }
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            var l = insets.systemWindowInsetLeft
            @Suppress("DEPRECATION")
            var r = insets.systemWindowInsetRight
            if (Build.VERSION.SDK_INT >= 28) {
                insets.displayCutout?.let {
                    l = maxOf(l, it.safeInsetLeft); r = maxOf(r, it.safeInsetRight)
                }
            }
            @Suppress("DEPRECATION")
            v.setPadding(l, insets.systemWindowInsetTop, r, insets.systemWindowInsetBottom)
            insets
        }
        root.requestApplyInsets()
    }

    protected fun showError(message: CharSequence) {
        findViewById<View>(R.id.overlay).visibility = View.VISIBLE
        findViewById<View>(R.id.loading).visibility = View.GONE
        findViewById<TextView>(R.id.error).apply {
            text = message
            visibility = View.VISIBLE
        }
    }

    protected fun hideOverlay() {
        findViewById<View>(R.id.overlay).visibility = View.GONE
    }

    @Suppress("DEPRECATION")
    fun color(id: Int) = resources.getColor(id)
}
