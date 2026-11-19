package com.easydocs.viewer

import android.app.AlertDialog
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

class PdfActivity : BaseActivity() {

    private lateinit var pdfView: PdfView
    private lateinit var pill: TextView
    private var doc: PdfDoc? = null
    private val hidePill = Runnable { pill.animate().alpha(0f).setDuration(300).start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf)
        val uri = Docs.uriFrom(intent)
        setupChrome(findViewById(R.id.root), "PDF", showBack = true)
        pdfView = findViewById(R.id.pdf)
        pill = findViewById(R.id.page_pill)

        toolbar.inflateMenu(R.menu.pdf)
        toolbar.setOnMenuItemClickListener {
            if (doc != null) when (it.itemId) {
                R.id.zoom_in -> pdfView.zoomBy(1.5f)
                R.id.zoom_out -> pdfView.zoomBy(1 / 1.5f)
                R.id.go_to -> askPage()
            }
            true
        }
        toolbar.setOnClickListener { if (doc != null) askPage() }

        pdfView.pageListener = PdfView.PageListener { page, count ->
            val label = getString(R.string.page_of, page + 1, count)
            toolbar.subtitle = label
            pill.text = label
            pill.animate().cancel()
            pill.alpha = 1f
            pill.removeCallbacks(hidePill)
            pill.postDelayed(hidePill, 1500)
        }

        if (uri == null) {
            showError(getString(R.string.err_no_file))
            return
        }
        val startPage = savedInstanceState?.getInt("page") ?: 0
        open(uri, startPage)
    }

    private fun open(uri: Uri, startPage: Int) {
        val app = applicationContext
        Docs.bg.execute {
            val name = Docs.displayName(app, uri)
            var error: Int? = null
            var opened: PdfDoc? = null
            try {
                opened = openDoc(uri)
            } catch (_: SecurityException) {
                error = R.string.err_pdf_password
            } catch (_: FileNotFoundException) {
                error = R.string.err_access
            } catch (_: OutOfMemoryError) {
                error = R.string.err_out_of_memory
            } catch (_: Exception) {
                error = R.string.err_pdf_corrupt
            }
            // SecurityException is also thrown when a content URI grant has expired.
            if (error == R.string.err_pdf_password && !canRead(uri)) error = R.string.err_access

            Docs.main.post {
                toolbar.title = name
                if (isDestroyed) {
                    opened?.close()
                    return@post
                }
                if (opened == null) {
                    if (error == R.string.err_access) Recents.remove(app, uri)
                    showError(getString(error ?: R.string.err_pdf_corrupt))
                    return@post
                }
                doc = opened
                Recents.add(app, uri, name, DocKind.PDF)
                pdfView.setDocument(opened, startPage)
                hideOverlay()
            }
        }
    }

    private fun canRead(uri: Uri) = try {
        contentResolver.openInputStream(uri)?.close(); true
    } catch (_: Exception) {
        false
    }

    /**
     * PdfRenderer needs a seekable file descriptor. Files from Downloads / the picker give one
     * directly; some share sources (mail, chat apps) give a pipe, so those are copied to
     * the app's private cache first and the copy is deleted when the viewer closes.
     */
    private fun openDoc(uri: Uri): PdfDoc {
        val direct = try {
            contentResolver.openFileDescriptor(uri, "r")
        } catch (e: FileNotFoundException) {
            throw e
        } catch (e: SecurityException) {
            throw FileNotFoundException(e.message)
        } catch (_: Exception) {
            null
        }
        if (direct != null) {
            try {
                return build(direct, null)
            } catch (e: SecurityException) {
                direct.close(); throw e
            } catch (_: Exception) {
                try { direct.close() } catch (_: Exception) {}
            }
        }
        val tmp = File.createTempFile("pdf", ".pdf", Docs.tempDir(this))
        try {
            val input = contentResolver.openInputStream(uri) ?: throw FileNotFoundException()
            input.use { i -> FileOutputStream(tmp).use { o -> i.copyTo(o, 64 * 1024) } }
            return build(ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY), tmp)
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    private fun build(pfd: ParcelFileDescriptor, tmp: File?): PdfDoc {
        val renderer = try {
            PdfRenderer(pfd)
        } catch (e: Throwable) {
            pfd.close(); throw e
        }
        val n = renderer.pageCount
        if (n <= 0) {
            renderer.close(); pfd.close()
            throw IllegalStateException("empty")
        }
        val w = FloatArray(n)
        val h = FloatArray(n)
        for (i in 0 until n) {
            renderer.openPage(i).use { p ->
                w[i] = p.width.toFloat().coerceAtLeast(1f)
                h[i] = p.height.toFloat().coerceAtLeast(1f)
            }
        }
        return PdfDoc(pfd, renderer, w, h, tmp)
    }

    private fun askPage() {
        val count = pdfView.pageCount
        if (count <= 0) return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.page_number_hint, count)
            imeOptions = EditorInfo.IME_ACTION_GO
            setSingleLine()
        }
        val box = FrameLayout(this).apply {
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.go_to_page).setView(box)
            .setPositiveButton(R.string.go) { _, _ -> jump(input.text.toString(), count) }
            .setNegativeButton(R.string.cancel, null)
            .create()
        input.setOnEditorActionListener { _, _, _ ->
            jump(input.text.toString(), count); dialog.dismiss(); true
        }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    private fun jump(text: String, count: Int) {
        val n = text.trim().toIntOrNull() ?: return
        pdfView.goToPage((n - 1).coerceIn(0, count - 1))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", pdfView.currentPage)
    }

    override fun onDestroy() {
        super.onDestroy()
        pill.removeCallbacks(hidePill)
        doc?.close()
        doc = null
    }
}
