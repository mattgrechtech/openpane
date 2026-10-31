package io.github.mattgrechtech.openpane.web

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Jump between buttons": the arrow keys move a highlight to the nearest clickable item in that
 * direction and OK taps it, so a dashboard built for touch works with a remote without changes.
 *
 * Clickable items are found by a script run in the page ([FIND_TARGETS]): buttons, links, ARIA
 * controls and anything styled with the pointer cursor, inside shadow DOM too. While a dialog is
 * open only its items count. Pressing past the last item scrolls the page.
 */
class TargetNavigator(context: Context, private val webView: () -> WebView?) {

    /** An item on screen, in view pixels. */
    data class Target(val rect: RectF)

    enum class Direction { LEFT, RIGHT, UP, DOWN }

    val view = HighlightView(context)
    private val main = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private var targets: List<Target> = emptyList()
    private var targetsAt = 0L
    private var current: RectF? = null
    private var pending = false

    private val fade = Runnable {
        main.removeCallbacks(check)
        view.show(null)
    }

    /** True when a highlighted item is ready for OK. */
    val hasSelection get() = current != null && view.isShowingTarget

    /** Forget everything, e.g. when a new page starts loading. */
    fun reset() {
        main.removeCallbacks(check)
        main.removeCallbacks(fade)
        targets = emptyList()
        targetsAt = 0
        current = null
        view.show(null)
    }

    /** Which keys the page wants for itself right now; see [PageKeys]. */
    enum class PageKeys { NONE, SIDEWAYS, ALL }

    /**
     * Asks the page which keys it wants: [PageKeys.ALL] when the focused element sits inside
     * something marked `data-openpane-keys="page"` (the page runs its own remote navigation),
     * [PageKeys.SIDEWAYS] when a slider, select or text field has focus (Left and Right adjust
     * it, Up and Down still move between items), else [PageKeys.NONE].
     */
    fun pageKeys(then: (PageKeys) -> Unit) {
        val page = webView() ?: return then(PageKeys.NONE)
        page.evaluateJavascript(PAGE_KEYS) { raw ->
            then(when (raw?.trim('"')) {
                "all" -> PageKeys.ALL
                "sideways" -> PageKeys.SIDEWAYS
                else -> PageKeys.NONE
            })
        }
    }

    /** The page changed under us (a tap, a scroll): look again on the next key. */
    fun invalidate() {
        targetsAt = 0
    }

    /** Handles the arrow keys; returns true when the key was used. */
    fun onKey(event: KeyEvent): Boolean {
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> Direction.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Direction.RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> Direction.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Direction.DOWN
            else -> return false
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        if (pending) return true
        // A highlight that had faded comes back where it was before moving.
        if (!view.isShowingTarget && current != null) {
            withTargets { show(current) }
            return true
        }
        withTargets { move(direction) }
        return true
    }

    /**
     * Closes the page's open dialog, if there is one (like Escape would), and tells [then] whether
     * it did, so Back can close a pop-up before leaving the page.
     */
    fun closeDialog(then: (Boolean) -> Unit) {
        val page = webView() ?: return then(false)
        page.evaluateJavascript(CLOSE_DIALOG) { raw ->
            val closed = raw == "true"
            if (closed) {
                invalidate()
                // The highlight was probably inside the dialog: start again from the page.
                current = null
                view.show(null)
            }
            then(closed)
        }
    }

    /**
     * Taps the highlighted item, after checking it is still there: dashboards change by themselves
     * (views rotate, cards appear), and a tap on the wrong thing is worse than none. If it has
     * gone, the nearest item is highlighted instead. Returns false when nothing is highlighted.
     */
    fun click(): Boolean {
        val rect = current ?: return false
        if (!view.isShowingTarget || pending) return view.isShowingTarget
        invalidate()
        withTargets {
            val still = targets.firstOrNull { near(it.rect, rect) }?.rect
            if (still != null) {
                webView()?.let { TouchInjector.tap(it, still.centerX(), still.centerY()) }
                invalidate()
                show(still)
            } else {
                val nearest = targets.minByOrNull { distance(it.rect.centerX(), it.rect.centerY(), rect.centerX(), rect.centerY()) }?.rect
                show(nearest)
            }
        }
        return true
    }

    private fun move(direction: Direction) {
        val from = current
        val next = if (from == null || targets.none { near(it.rect, from) }) {
            // Start from the item nearest the middle of the screen (or of the old highlight).
            val cx = from?.centerX() ?: (view.width / 2f)
            val cy = from?.centerY() ?: (view.height / 2f)
            if (from == null) targets.minByOrNull { distance(it.rect.centerX(), it.rect.centerY(), cx, cy) }?.rect
            else best(from, direction)
        } else {
            best(from, direction)
        }
        if (next != null) {
            show(next)
        } else {
            scroll(direction)
        }
    }

    /** The nearest item in [direction] from [from]: distance along the way plus twice the sideways offset. */
    private fun best(from: RectF, direction: Direction): RectF? = targets.map { it.rect }.filter { candidate ->
        !near(candidate, from) && when (direction) {
            Direction.RIGHT -> candidate.centerX() > from.centerX() + 2 && candidate.right > from.right
            Direction.LEFT -> candidate.centerX() < from.centerX() - 2 && candidate.left < from.left
            Direction.DOWN -> candidate.centerY() > from.centerY() + 2 && candidate.bottom > from.bottom
            Direction.UP -> candidate.centerY() < from.centerY() - 2 && candidate.top < from.top
        }
    }.minByOrNull { candidate ->
        val (along, across) = when (direction) {
            Direction.LEFT, Direction.RIGHT -> Math.abs(candidate.centerX() - from.centerX()) to
                gap(candidate.top, candidate.bottom, from.top, from.bottom)
            Direction.UP, Direction.DOWN -> Math.abs(candidate.centerY() - from.centerY()) to
                gap(candidate.left, candidate.right, from.left, from.right)
        }
        along + 2 * across
    }

    /** Distance between two ranges on one axis; 0 when they overlap. */
    private fun gap(aStart: Float, aEnd: Float, bStart: Float, bEnd: Float) = when {
        aEnd < bStart -> bStart - aEnd
        bEnd < aStart -> aStart - bEnd
        else -> 0f
    }

    private fun scroll(direction: Direction) {
        val target = webView() ?: return
        val amount = target.height * 0.4f
        val (dx, dy) = when (direction) {
            Direction.DOWN -> 0f to -amount
            Direction.UP -> 0f to amount
            Direction.RIGHT -> -amount to 0f
            Direction.LEFT -> amount to 0f
        }
        val anchor = current ?: RectF(0f, 0f, target.width.toFloat(), target.height.toFloat())
        val x = anchor.centerX().coerceIn(40 * density, target.width - 40 * density)
        val y = anchor.centerY().coerceIn(40 * density, target.height - 40 * density)
        TouchInjector.swipe(target, x, y, dx, dy)
        invalidate()
        // Once the page has moved, pick the nearest item that has come into view.
        main.postDelayed({
            withTargets {
                val from = current
                val next = if (from != null) best(shifted(from, -dx, -dy), direction) ?: targets.firstOrNull()?.rect
                else targets.firstOrNull()?.rect
                if (next != null) show(next)
            }
        }, 350)
    }

    private fun shifted(rect: RectF, dx: Float, dy: Float) = RectF(rect).apply { offset(dx, dy) }

    private fun show(rect: RectF?) {
        current = rect
        view.show(rect)
        keepVisible()
    }

    private fun keepVisible() {
        main.removeCallbacks(fade)
        main.postDelayed(fade, FADE_MILLIS)
        main.removeCallbacks(check)
        main.postDelayed(check, CHECK_MILLIS)
    }

    /** While the highlight shows, make sure its item is still on screen; hide it if not. */
    private val check: Runnable = Runnable {
        val rect = current
        if (rect == null || !view.isShowingTarget) return@Runnable
        invalidate()
        withTargets {
            if (targets.none { near(it.rect, rect) }) {
                view.show(null)
            } else {
                main.postDelayed(check, CHECK_MILLIS)
            }
        }
    }

    /** Runs [then] with a fresh enough list of items, asking the page if needed. */
    private fun withTargets(then: () -> Unit) {
        if (SystemClock.uptimeMillis() - targetsAt < CACHE_MILLIS && targets.isNotEmpty()) {
            then()
            return
        }
        val page = webView() ?: return
        pending = true
        page.evaluateJavascript(FIND_TARGETS) { raw ->
            pending = false
            targets = parse(raw)
            targetsAt = SystemClock.uptimeMillis()
            then()
        }
    }

    private fun parse(raw: String?): List<Target> {
        if (raw.isNullOrEmpty() || raw == "null") return emptyList()
        return try {
            // evaluateJavascript hands back a JSON-encoded string.
            val json = JSONObject(JSONArray("[$raw]").getString(0))
            val scale = json.optDouble("dpr", 1.0).toFloat()
            val list = json.optJSONArray("targets") ?: return emptyList()
            val screenArea = view.width.toFloat() * view.height.toFloat()
            val result = mutableListOf<Target>()
            for (i in 0 until list.length()) {
                val r = list.getJSONArray(i)
                val rect = RectF(
                    (r.getDouble(0) * scale).toFloat(),
                    (r.getDouble(1) * scale).toFloat(),
                    ((r.getDouble(0) + r.getDouble(2)) * scale).toFloat(),
                    ((r.getDouble(1) + r.getDouble(3)) * scale).toFloat(),
                )
                // Skip page-sized backgrounds and duplicates of the same box.
                if (screenArea > 0 && rect.width() * rect.height() > screenArea * 0.5f) continue
                if (result.any { near(it.rect, rect) }) continue
                result += Target(rect)
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun near(a: RectF, b: RectF): Boolean {
        val tolerance = 4 * density
        return Math.abs(a.left - b.left) < tolerance && Math.abs(a.top - b.top) < tolerance &&
            Math.abs(a.right - b.right) < tolerance && Math.abs(a.bottom - b.bottom) < tolerance
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float) =
        Math.hypot((x1 - x2).toDouble(), (y1 - y2).toDouble()).toFloat()

    /** Draws the highlight ring. */
    class HighlightView(context: Context) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6 * density
            color = 0xFF0288D1.toInt()
        }
        private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
            color = 0xFFFFFFFF.toInt()
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x220288D1
        }
        private var rect: RectF? = null

        init {
            isFocusable = false
            setWillNotDraw(false)
        }

        val isShowingTarget get() = rect != null

        fun show(target: RectF?) {
            rect = target?.let { RectF(it).apply { inset(-5 * density, -5 * density) } }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val r = rect ?: return
            val radius = 12 * density
            canvas.drawRoundRect(r, radius, radius, fill)
            canvas.drawRoundRect(r, radius, radius, outer)
            canvas.drawRoundRect(r, radius, radius, inner)
        }
    }

    private companion object {
        const val CACHE_MILLIS = 800L
        const val FADE_MILLIS = 15_000L
        const val CHECK_MILLIS = 2_000L

        /** Closes the topmost open dialog; returns true if there was one. */
        const val PAGE_KEYS = """
(function () {
  var a = document.activeElement;
  while (a && a.shadowRoot && a.shadowRoot.activeElement) a = a.shadowRoot.activeElement;
  if (!a || a === document.body || a === document.documentElement) return '';
  for (var n = a; n; ) {
    /* data-tvkiosk-keys: the name before the rename, still on some ha-tv-kit panels. */
    if (n.getAttribute && (n.getAttribute('data-openpane-keys') === 'page' || n.getAttribute('data-tvkiosk-keys') === 'page')) return 'all';
    n = n.parentNode;
    if (n && n.nodeType === 11) n = n.host;
  }
  var tag = (a.tagName || '').toLowerCase(), type = (a.type || '').toLowerCase();
  var role = a.getAttribute && a.getAttribute('role');
  if (tag === 'select' || tag === 'textarea' || a.isContentEditable || role === 'slider') return 'sideways';
  if (tag === 'input' && ['button', 'submit', 'reset', 'checkbox', 'radio', 'image', 'file', 'color'].indexOf(type) < 0) return 'sideways';
  return '';
})()
"""

        const val CLOSE_DIALOG = """
(function () {
  var DIALOG = 'dialog[open],[role=dialog],[role=alertdialog],ha-dialog[open],ha-md-dialog[open]';
  var found = null;
  (function walk(root) {
    var all = root.querySelectorAll('*');
    for (var i = 0; i < all.length; i++) {
      try {
        if (all[i].matches(DIALOG)) {
          var r = all[i].getBoundingClientRect(), cs = getComputedStyle(all[i]);
          if (r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none' &&
              Number(cs.opacity) > 0.05 && cs.pointerEvents !== 'none') found = all[i];
        }
      } catch (e) {}
      if (all[i].shadowRoot) walk(all[i].shadowRoot);
    }
  })(document);
  if (!found) return false;
  found.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, bubbles: true, composed: true }));
  if (typeof found.close === 'function') { try { found.close(); } catch (e) {} }
  return true;
})()
"""

        /** Finds visible, clickable items; returns {dpr, targets: [[x, y, w, h], ...]} in CSS pixels. */
        const val FIND_TARGETS = """
(function () {
  var INTERACTIVE = 'a[href],button,input,select,textarea,[role=button],[role=link],[role=switch],' +
    '[role=checkbox],[role=tab],[role=menuitem],[role=option],[role=slider],[tabindex]:not([tabindex="-1"])';
  var DIALOG = 'dialog[open],[role=dialog],[role=alertdialog],ha-dialog[open],ha-md-dialog[open]';
  var vw = window.innerWidth, vh = window.innerHeight;
  function walk(root, fn) {
    var all = root.querySelectorAll('*');
    for (var i = 0; i < all.length; i++) { fn(all[i]); if (all[i].shadowRoot) walk(all[i].shadowRoot, fn); }
  }
  function parentOf(el) { return el.parentElement || (el.parentNode && el.parentNode.host) || null; }
  // Really on screen: sized, not hidden, not transparent, able to take clicks (pages often keep
  // closed dialogs in the DOM at opacity 0).
  function shown(el) {
    var r = el.getBoundingClientRect(), cs = getComputedStyle(el);
    return r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none' &&
      Number(cs.opacity) > 0.05 && cs.pointerEvents !== 'none';
  }
  function within(el, ancestor) { for (var n = el; n; n = n.parentNode || n.host) { if (n === ancestor) return true; } return false; }
  function deepHit(x, y) {
    var el = document.elementFromPoint(x, y);
    while (el && el.shadowRoot) { var inner = el.shadowRoot.elementFromPoint(x, y); if (!inner || inner === el) break; el = inner; }
    return el;
  }
  var dialog = null;
  walk(document, function (el) {
    try { if (el.matches(DIALOG) && shown(el)) dialog = el; } catch (e) {}
  });
  var out = [];
  walk(document, function (el) {
    var interactive = false;
    try { interactive = el.matches(INTERACTIVE); } catch (e) {}
    if (!interactive) {
      if (getComputedStyle(el).cursor !== 'pointer') return;
      var p = parentOf(el);
      if (p && p.nodeType === 1 && getComputedStyle(p).cursor === 'pointer') return;
    }
    if (el.disabled || el.getAttribute('aria-disabled') === 'true' || el.getAttribute('aria-hidden') === 'true') return;
    var r = el.getBoundingClientRect();
    if (r.width < 8 || r.height < 8 || r.bottom <= 0 || r.right <= 0 || r.top >= vh || r.left >= vw) return;
    var cs = getComputedStyle(el);
    if (cs.visibility === 'hidden' || cs.display === 'none' || Number(cs.opacity) < 0.05 || cs.pointerEvents === 'none') return;
    if (dialog && !within(el, dialog)) return;
    var cx = Math.min(Math.max(r.left + r.width / 2, 0), vw - 1), cy = Math.min(Math.max(r.top + r.height / 2, 0), vh - 1);
    var hit = deepHit(cx, cy);
    if (hit && hit !== el && !within(hit, el) && !within(el, hit)) return;
    out.push([r.left, r.top, r.width, r.height]);
  });
  return JSON.stringify({ dpr: window.devicePixelRatio || 1, targets: out });
})()
"""
    }
}
