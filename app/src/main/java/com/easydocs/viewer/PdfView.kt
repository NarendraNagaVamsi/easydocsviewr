package com.easydocs.viewer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.AttributeSet
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An open PDF. All PdfRenderer calls run on [exec], a single thread, because PdfRenderer
 * allows only one open page at a time and is not thread-safe.
 */
class PdfDoc(
    private val pfd: ParcelFileDescriptor,
    val renderer: PdfRenderer,
    val pageW: FloatArray,
    val pageH: FloatArray,
    private val tempFile: File?,
) {
    val exec = Executors.newSingleThreadExecutor()
    val count get() = pageW.size
    @Volatile var closed = false

    fun close() {
        if (closed) return
        closed = true
        exec.execute {
            try { renderer.close() } catch (_: Exception) {}
            try { pfd.close() } catch (_: Exception) {}
            tempFile?.delete()
        }
        exec.shutdown()
    }
}

/**
 * Vertically scrolling, pinch-zoomable PDF page view.
 *
 * Pages are rendered once at "fit width" resolution into an LRU cache bounded by bytes, so
 * memory stays flat regardless of page count. When zoomed in, a single screen-sized
 * "detail" bitmap of only the visible area is rendered at full resolution once the user
 * stops moving, so text stays sharp at any zoom without allocating huge bitmaps.
 */
class PdfView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    fun interface PageListener { fun onPage(page: Int, count: Int) }
    var pageListener: PageListener? = null

    private var doc: PdfDoc? = null
    private val density = resources.displayMetrics.density
    private val gap = 8 * density

    // Layout at zoom 1, in view pixels.
    private var fit = FloatArray(0)      // per page: pixels per PDF point at zoom 1 (fit to width)
    private var tops = FloatArray(0)     // top of each page
    private var contentH = 0f
    private var maxPagePixels = 1        // cap for page-cache bitmaps

    private var zoom = 1f
    private var sx = 0f
    private var sy = 0f
    private val minZoom = 1f
    private val maxZoom = 8f

    var currentPage = 0
        private set
    private var pendingPage = -1

    // Rendered pages. Accessed on the UI thread only.
    private class Rendered(val bmp: Bitmap, val scale: Float)
    private val cache = object : LruCache<Int, Rendered>(cacheBytes()) {
        override fun sizeOf(key: Int, value: Rendered) = value.bmp.byteCount
    }
    private val inFlight = HashSet<Int>()
    @Volatile private var visFirst = 0
    @Volatile private var visLast = -1

    // High-resolution tile for the visible area when zoomed.
    private var tileFront: Bitmap? = null
    private var tileBack: Bitmap? = null
    private var tileZoom = 0f
    private var tileSx = 0f
    private var tileSy = 0f
    private var tileGen = 0
    private var tileBusy = false

    private val pagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val whitePaint = Paint().apply { color = Color.WHITE }
    private val edgePaint = Paint().apply { color = 0x22000000; style = Paint.Style.STROKE; strokeWidth = density }
    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB0B4BC.toInt(); textSize = 18 * density; textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    private val scroller = OverScroller(context)
    private var zoomAnim: ValueAnimator? = null
    private var scaling = false

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scrollTo(sx + dx, sy + dy)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(sx.toInt(), sy.toInt(), -vx.toInt(), -vy.toInt(), 0, maxSx().toInt(), 0, maxSy().toInt())
            postInvalidateOnAnimation()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            animateZoom(if (zoom < 1.8f) 2.5f else 1f, e.x, e.y)
            return true
        }
    })

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            zoomAnim?.cancel()
            scaling = true
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomAround(zoom * d.scaleFactor, d.focusX, d.focusY)
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) {
            scaling = false
            viewportChanged()
        }
    })

    private val tileRunnable = Runnable { renderTile() }

    // ---- public API ----

    fun setDocument(d: PdfDoc, startPage: Int) {
        doc = d
        cache.evictAll()
        inFlight.clear()
        lastReported = -1
        zoom = 1f; sx = 0f; sy = 0f
        pendingPage = startPage
        relayout()
        invalidate()
    }

    val pageCount get() = doc?.count ?: 0

    fun goToPage(page: Int) {
        if (doc == null) return
        if (width == 0 || tops.isEmpty()) {
            pendingPage = page
            return
        }
        val p = page.coerceIn(0, tops.size - 1)
        scroller.forceFinished(true)
        scrollTo(sx, (tops[p] - gap) * zoom)
    }

    fun zoomBy(factor: Float) = animateZoom(zoom * factor, width / 2f, height / 2f)

    // ---- layout ----

    private fun cacheBytes(): Int {
        val max = Runtime.getRuntime().maxMemory()
        return min(max / 6, 64L * 1024 * 1024).toInt()
    }

    private fun relayout() {
        val d = doc ?: return
        if (width == 0) return
        fit = FloatArray(d.count) { (width - 2 * gap) / d.pageW[it] }
        tops = FloatArray(d.count)
        var y = gap
        for (i in 0 until d.count) {
            tops[i] = y
            y += d.pageH[i] * fit[i] + gap
        }
        contentH = y
        // A page bitmap never exceeds ~1.5 screens of pixels or 1/6 of the cache.
        maxPagePixels = min(width * height * 3 / 2, cacheBytes() / 4 / 6).coerceAtLeast(256 * 256)
        if (pendingPage >= 0) {
            val p = pendingPage
            pendingPage = -1
            goToPage(p)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val keep = currentPage
        val changedWidth = w != oldw
        if (changedWidth) {
            cache.evictAll()
            tileFront = null; tileBack = null; tileZoom = 0f
        }
        if (oldw != 0 && pendingPage < 0) pendingPage = keep
        relayout()
        scrollTo(sx, sy)
    }

    private fun maxSx() = max(0f, width * zoom - width)
    private fun maxSy() = max(0f, contentH * zoom - height)

    private fun scrollTo(x: Float, y: Float) {
        sx = x.coerceIn(0f, maxSx())
        sy = y.coerceIn(0f, maxSy())
        viewportChanged()
        invalidate()
    }

    private fun zoomAround(newZoom: Float, fx: Float, fy: Float) {
        val z = newZoom.coerceIn(minZoom, maxZoom)
        val r = z / zoom
        val oy = offsetY()
        zoom = z
        sx = (sx + fx) * r - fx
        sy = (sy + fy - oy) * r - fy + offsetY()
        scrollTo(sx, sy)
    }

    private fun animateZoom(target: Float, fx: Float, fy: Float) {
        zoomAnim?.cancel()
        val t = target.coerceIn(minZoom, maxZoom)
        zoomAnim = ValueAnimator.ofFloat(zoom, t).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { zoomAround(it.animatedValue as Float, fx, fy) }
            start()
        }
    }

    /** Vertical offset that centres short documents. */
    private fun offsetY(): Float {
        val h = contentH * zoom
        return if (h < height) (height - h) / 2 else 0f
    }

    private fun pageRect(i: Int, out: RectF) {
        val d = doc!!
        val w = d.pageW[i] * fit[i]
        val left = (width - w) / 2f
        out.set(
            left * zoom - sx,
            tops[i] * zoom - sy + offsetY(),
            (left + w) * zoom - sx,
            (tops[i] + d.pageH[i] * fit[i]) * zoom - sy + offsetY(),
        )
    }

    /** First page whose bottom is below content-y [y] (zoom-1 units). */
    private fun pageAt(y: Float): Int {
        val d = doc ?: return 0
        var lo = 0
        var hi = tops.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (tops[mid] + d.pageH[mid] * fit[mid] < y) lo = mid + 1 else hi = mid
        }
        return lo
    }

    // ---- input ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (doc == null) return false
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestures.onTouchEvent(event)
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            sx = scroller.currX.toFloat()
            sy = scroller.currY.toFloat()
            viewportChanged()
            postInvalidateOnAnimation()
        }
    }

    // ---- drawing ----

    override fun onDraw(canvas: Canvas) {
        val d = doc ?: return
        if (tops.isEmpty() || d.closed) return

        val first = pageAt((sy - offsetY()) / zoom)
        var last = first
        while (last + 1 < d.count && tops[last + 1] * zoom - sy + offsetY() < height) last++
        visFirst = first
        visLast = last

        val cur = pageAt((sy + height / 3f) / zoom)
        if (cur != lastReported) {
            currentPage = cur
            lastReported = cur
            pageListener?.onPage(cur, d.count)
        }

        for (i in first..last) {
            pageRect(i, rect)
            canvas.drawRect(rect, whitePaint)
            val r = cache.get(i)
            if (r != null) {
                canvas.drawBitmap(r.bmp, null, rect, pagePaint)
            } else {
                canvas.drawText((i + 1).toString(), rect.centerX(), rect.centerY(), numPaint)
            }
            canvas.drawRect(rect, edgePaint)
            val want = pageScale(i)
            if (r == null || (!scaling && r.scale < want * 0.95f)) requestPage(i, want)
        }

        // Prefetch neighbours so scrolling never shows blank pages.
        if (!scaling) {
            if (last + 1 < d.count && cache.get(last + 1) == null) requestPage(last + 1, pageScale(last + 1))
            if (first - 1 >= 0 && cache.get(first - 1) == null) requestPage(first - 1, pageScale(first - 1))
        }

        val tile = tileFront
        if (tile != null && tileZoom == zoom) {
            canvas.drawBitmap(tile, tileSx - sx, tileSy - sy, null)
        }
    }

    private var lastReported = -1

    /** Resolution for the page cache: fit-width, capped by [maxPagePixels]. */
    private fun pageScale(i: Int): Float {
        val d = doc!!
        val px = d.pageW[i] * d.pageH[i] * fit[i] * fit[i]
        return if (px <= maxPagePixels) fit[i] else fit[i] * kotlin.math.sqrt(maxPagePixels / px)
    }

    private fun requestPage(i: Int, scale: Float) {
        val d = doc ?: return
        if (!inFlight.add(i)) return
        d.exec.execute {
            var bmp: Bitmap? = null
            if (!d.closed && i in (visFirst - 2)..(visLast + 2)) {
                try {
                    val w = (d.pageW[i] * scale).roundToInt().coerceAtLeast(1)
                    val h = (d.pageH[i] * scale).roundToInt().coerceAtLeast(1)
                    bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    d.renderer.openPage(i).use { it.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                } catch (_: OutOfMemoryError) {
                    bmp = null
                    Docs.main.post { cache.trimToSize(cache.maxSize() / 2) }
                } catch (_: Exception) {
                    bmp = null
                }
            }
            val result = bmp
            Docs.main.post {
                inFlight.remove(i)
                if (doc === d && result != null) {
                    cache.put(i, Rendered(result, scale))
                    invalidate()
                }
            }
        }
    }

    // ---- sharp tile when zoomed ----

    private fun viewportChanged() {
        removeCallbacks(tileRunnable)
        if (doc != null && zoom > 1.05f && !scaling) postDelayed(tileRunnable, 160)
    }

    private fun renderTile() {
        val d = doc ?: return
        if (zoom <= 1.05f || width == 0 || height == 0) return
        if (tileBusy) {
            postDelayed(tileRunnable, 80)
            return
        }
        val back = tileBack?.takeIf { it.width == width && it.height == height } ?: try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            return
        }
        tileBack = back

        // Snapshot the visible pages' screen rectangles on the UI thread.
        val pages = ArrayList<Triple<Int, RectF, Float>>()
        for (i in visFirst..visLast) {
            if (i < 0 || i >= d.count) continue
            val r = RectF(); pageRect(i, r)
            if (r.intersects(0f, 0f, width.toFloat(), height.toFloat())) pages.add(Triple(i, r, fit[i] * zoom))
        }
        val gen = ++tileGen
        val z = zoom; val tx = sx; val ty = sy
        tileBusy = true

        d.exec.execute {
            var ok = false
            if (!d.closed) {
                try {
                    back.eraseColor(Color.TRANSPARENT)
                    val c = Canvas(back)
                    for ((i, r, ps) in pages) {
                        val clip = Rect(
                            max(0, r.left.toInt()), max(0, r.top.toInt()),
                            min(back.width, r.right.roundToInt()), min(back.height, r.bottom.roundToInt()),
                        )
                        if (clip.isEmpty) continue
                        c.drawRect(clip, whitePaint)
                        val m = Matrix().apply { setScale(ps, ps); postTranslate(r.left, r.top) }
                        d.renderer.openPage(i).use { it.render(back, clip, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                    }
                    ok = true
                } catch (_: Exception) {
                }
            }
            Docs.main.post {
                tileBusy = false
                if (ok && doc === d && gen == tileGen) {
                    tileBack = tileFront
                    tileFront = back
                    tileZoom = z; tileSx = tx; tileSy = ty
                    invalidate()
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(tileRunnable)
        zoomAnim?.cancel()
    }
}
