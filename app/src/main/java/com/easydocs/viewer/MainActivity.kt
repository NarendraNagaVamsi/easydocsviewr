package com.easydocs.viewer

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

class MainActivity : BaseActivity() {

    private val reqPick = 1
    private lateinit var adapter: RecentAdapter
    private lateinit var empty: View
    private lateinit var clear: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupChrome(findViewById(R.id.root), getString(R.string.app_name), showBack = false)

        toolbar.inflateMenu(R.menu.main)
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.theme -> chooseTheme()
                R.id.about -> AlertDialog.Builder(this).setTitle(R.string.app_name)
                    .setMessage(R.string.about_text).setPositiveButton(android.R.string.ok, null).show()
            }
            true
        }

        val list = findViewById<ListView>(R.id.list)
        val header = layoutInflater.inflate(R.layout.home_header, list, false)
        list.addHeaderView(header, null, false)
        adapter = RecentAdapter()
        list.adapter = adapter

        empty = header.findViewById(R.id.empty)
        clear = header.findViewById(R.id.clear)
        header.findViewById<View>(R.id.cat_pdf).setOnClickListener { pick(Docs.PDF_MIMES) }
        header.findViewById<View>(R.id.cat_excel).setOnClickListener { pick(Docs.EXCEL_MIMES) }
        header.findViewById<View>(R.id.browse).setOnClickListener { pick(Docs.PDF_MIMES + Docs.EXCEL_MIMES) }
        clear.setOnClickListener {
            Recents.clear(this)
            refresh()
        }

        list.setOnItemClickListener { _, _, pos, _ ->
            val e = adapter.getItem(pos - list.headerViewsCount)
            try {
                startActivity(Docs.viewerIntent(this, e.uri, e.kind))
            } catch (_: Exception) {
                Recents.remove(this, e.uri)
                refresh()
                Toast.makeText(this, R.string.err_access, Toast.LENGTH_LONG).show()
            }
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val e = adapter.getItem(pos - list.headerViewsCount)
            AlertDialog.Builder(this).setTitle(e.name)
                .setItems(arrayOf(getString(R.string.remove))) { _, _ ->
                    Recents.remove(this, e.uri)
                    refresh()
                }.show()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        adapter.items = Recents.list(this)
        adapter.notifyDataSetChanged()
        empty.visibility = if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
        clear.visibility = if (adapter.items.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Storage Access Framework picker: no storage permission needed. */
    private fun pick(mimes: Array<String>) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(if (mimes.size == 1) mimes[0] else "*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, mimes)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, reqPick)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_picker, Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Framework Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != reqPick || resultCode != RESULT_OK || uri == null) return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        val mime = data.type
        Docs.bg.execute {
            val kind = Docs.detectKind(this, uri, mime)
            Docs.main.post {
                if (isFinishing) return@post
                if (kind == null) Toast.makeText(this, R.string.err_unsupported, Toast.LENGTH_LONG).show()
                else startActivity(Docs.viewerIntent(this, uri, kind))
            }
        }
    }

    private fun chooseTheme() {
        val labels = arrayOf(getString(R.string.theme_system), getString(R.string.theme_light), getString(R.string.theme_dark))
        AlertDialog.Builder(this).setTitle(R.string.theme)
            .setSingleChoiceItems(labels, themeMode(this)) { d, which ->
                d.dismiss()
                if (which != themeMode(this)) {
                    setThemeMode(this, which)
                    recreate()
                }
            }.show()
    }

    private inner class RecentAdapter : BaseAdapter() {
        var items: List<Recents.Entry> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_recent, parent, false)
            val e = items[position]
            val pdf = e.kind == DocKind.PDF
            v.findViewById<ImageView>(R.id.icon).apply {
                setImageResource(if (pdf) R.drawable.ic_pdf else R.drawable.ic_excel)
                setColorFilter(color(if (pdf) R.color.pdf else R.color.excel))
                setBackgroundResource(if (pdf) R.drawable.icon_bg_pdf else R.drawable.icon_bg_excel)
            }
            v.findViewById<TextView>(R.id.name).text = e.name
            val type = if (pdf) "PDF" else "Excel"
            val time = DateUtils.getRelativeTimeSpanString(e.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            v.findViewById<TextView>(R.id.meta).text = "$type · $time"
            return v
        }
    }
}
