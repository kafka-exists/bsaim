package com.venice.bsaim

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

object G {
    const val T = "BSAim"

    inline fun <T> safe(tag: String, def: T, block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        Log.w(T, "$tag: ${t.javaClass.simpleName} ${t.message}")
        def
    }

    fun v(tag: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(T, "$tag: ${t.javaClass.simpleName} ${t.message}")
        }
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    companion object {
        fun seedIfNeeded(ctx: Context) {
            G.v("seed") {
                val sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                if (sp.getBoolean("seeded", false)) return@v
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                val gb = mi.totalMem / (1024.0 * 1024.0 * 1024.0)
                val low = gb < 5.0
                sp.edit()
                    .putBoolean("seeded", true)
                    .putBoolean("low", low)
                    .putInt("st", if (low) 3 else 2)
                    .putInt("fov", if (low) 210 else 300)
                    .putInt("sm", if (low) 55 else 65)
                    .putInt("mp", if (low) 4 else 3)
                    .apply()
            }
        }
    }

    var lowMode: Boolean
        get() = G.safe("getLow", false) { sp.getBoolean("low", false) }
        set(v) = G.v("setLow") { sp.edit().putBoolean("low", v).apply() }

    var enabled: Boolean
        get() = G.safe("getEn", false) { sp.getBoolean("en", false) }
        set(v) = G.v("setEn") { sp.edit().putBoolean("en", v).apply() }

    var autoTap: Boolean
        get() = G.safe("getAt", false) { sp.getBoolean("at", false) }
        set(v) = G.v("setAt") { sp.edit().putBoolean("at", v).apply() }

    var dragMode: Boolean
        get() = G.safe("getDm", true) { sp.getBoolean("dm", true) }
        set(v) = G.v("setDm") { sp.edit().putBoolean("dm", v).apply() }

    var fov: Int
        get() = G.safe("getFov", 300) { sp.getInt("fov", 300) }
        set(v) = G.v("setFov") { sp.edit().putInt("fov", v).apply() }

    var smooth: Int
        get() = G.safe("getSm", 65) { sp.getInt("sm", 65) }
        set(v) = G.v("setSm") { sp.edit().putInt("sm", v).apply() }

    var minPx: Int
        get() = G.safe("getMp", 3) { sp.getInt("mp", 3) }
        set(v) = G.v("setMp") { sp.edit().putInt("mp", v).apply() }

    var stride: Int
        get() = G.safe("getSt", 2) { sp.getInt("st", 2) }
        set(v) = G.v("setSt") { sp.edit().putInt("st", v).apply() }
}

data class Target(val x: Int, val y: Int, val w: Int, val h: Int)

object Detector {
    fun detect(px: IntArray, w: Int, h: Int, strideRaw: Int, fov: Float, minPxRaw: Int): Target? {
        val stride = strideRaw.coerceIn(1, 8)
        val minPx = minPxRaw.coerceIn(1, 200)
        if (w < 16 || h < 16) return null
        if (px.size < w * h) return null

        val gw = w / stride
        val gh = h / stride
        if (gw < 4 || gh < 4) return null

        val mask = BooleanArray(gw * gh)
        for (gy in 0 until gh) {
            val rowBase = gy * stride * w
            for (gx in 0 until gw) {
                val c = px[rowBase + gx * stride]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r >= 105 && r - g >= 40 && r - b >= 40 && g <= 160) {
                    mask[gy * gw + gx] = true
                }
            }
        }

        val cx = w / 2f / stride
        val cy = h / 2f / stride
        var best: Target? = null
        var bestD = Float.MAX_VALUE
        val seen = BooleanArray(gw * gh)
        val stack = IntArray(gw * gh)

        for (i in mask.indices) {
            if (!mask[i] || seen[i]) continue
            var sp = 0
            stack[sp++] = i
            seen[i] = true
            var minX = Int.MAX_VALUE
            var maxX = -1
            var minY = Int.MAX_VALUE
            var maxY = -1
            var count = 0

            while (sp > 0) {
                val cur = stack[--sp]
                val x = cur % gw
                val y = cur / gw
                count++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue
                        val ni = ny * gw + nx
                        if (mask[ni] && !seen[ni]) {
                            seen[ni] = true
                            stack[sp++] = ni
                        }
                    }
                }
            }

            if (count < minPx) continue
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (bw < 2 || bh < 1) continue
            val ar = bw.toFloat() / bh.toFloat()
            if (ar < 1.2f || ar > 14f) continue

            val tcx = (minX + maxX) / 2f
            val tcy = (minY + maxY) / 2f
            val d = hypot(tcx - cx, tcy - cy)
            if (d > fov) continue

            if (d < bestD) {
                bestD = d
                best = Target((tcx * stride).toInt(), (tcy * stride).toInt(), bw * stride, bh * stride)
            }
        }
        return best
    }
}

class AimbotA11yService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: AimbotA11yService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun tap(x: Float, y: Float) {
        G.v("tap") {
            val p = Path().apply { moveTo(x, y) }
            dispatch(GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0L, 30L))
                .build())
        }
    }

    fun drag(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        G.v("drag") {
            val p = Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }
            dispatch(GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0L, ms.coerceIn(20L, 400L)))
                .build())
        }
    }

    private fun dispatch(g: GestureDescription) {
        G.v("dispatch") { dispatchGesture(g, null, null) }
    }
}

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var panel: LinearLayout? = null
    private var overlay: AimView? = null

    private var projection: MediaProjection? = null
    private var projCb: MediaProjection.Callback? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var bmp: Bitmap? = null
    private var px: IntArray = IntArray(0)

    private var capW = 480
    private var capH = 270
    private var scale = 1f

    @Volatile private var running = false
    @Volatile private var overlayAttached = false
    @Volatile private var panelAttached = false
    private var lastFire = 0L
    private var lastAim = 0L
    private var lastFrameAt = 0L
    private var overlayTries = 0
    private var panelTries = 0

    private val main get() = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        G.v("onCreate") {
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val dm = resources.displayMetrics
            val safeW = if (dm.widthPixels > 0) dm.widthPixels else 1080
            val safeH = if (dm.heightPixels > 0) dm.heightPixels else 1920
            val p = Prefs(this)
            capW = if (p.lowMode) 480 else 640
            capH = (capW.toFloat() * safeH / safeW).roundToInt().coerceIn(120, 2000)
            scale = safeW.toFloat() / capW.toFloat()
            startFg()
            addOverlay()
            addPanel()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        G.v("onStartCommand") {
            val data = intent?.getParcelableExtra<Intent>("data")
            if (data != null && projection == null) startCapture(data)
            if (projection == null && data == null) {
                toast("No capture permission - open the app and press Start")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun toast(msg: String) {
        G.v("toast") {
            main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
        }
    }

    private fun startFg() {
        G.v("startFg") {
            val id = "bsaim"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(id) == null) {
                    nm.createNotificationChannel(NotificationChannel(id, "Aim", NotificationManager.IMPORTANCE_LOW))
                }
            }
            val n = NotificationCompat.Builder(this, id)
                .setContentTitle("BS Aim running")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(1, n)
            }
        }
    }

    private fun startCapture(data: Intent) {
        G.v("startCapture") {
            if (!Settings.canDrawOverlays(this)) {
                toast("Overlay permission missing")
                stopSelf()
                return
            }
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(RESULT_OK, data) ?: run {
                toast("Capture permission denied")
                stopSelf()
                return
            }
            projection = proj

            thread = HandlerThread("capture").also { it.start() }
            handler = Handler(thread!!.looper)

            val cb = object : MediaProjection.Callback() {
                override fun onStop() {
                    running = false
                    main.post {
                        overlay?.target = null
                        G.v("invalidate") { overlay?.invalidate() }
                        toast("Capture stopped - press Start again")
                    }
                }
            }
            projCb = cb
            proj.registerCallback(cb, main)

            bmp = Bitmap.createBitmap(capW, capH, Bitmap.Config.ARGB_8888)
            px = IntArray(capW * capH)

            val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
            reader = r
            vd = proj.createVirtualDisplay(
                "bsaim", capW, capH, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, null
            )
            running = true
            r.setOnImageAvailableListener({ ir -> onFrame(ir) }, handler)
        }
    }

    private fun onFrame(r: ImageReader) {
        if (!running) return
        val p = Prefs(this)
        val gap = if (p.lowMode) 130L else 70L
        val now = SystemClock.uptimeMillis()
        if (now - lastFrameAt < gap) {
            G.v("drop") { r.acquireLatestImage()?.close() }
            return
        }
        lastFrameAt = now

        val img = G.safe<android.media.Image?>("acquire", null) { r.acquireLatestImage() } ?: return
        try {
            val b = bmp ?: return
            val buf = img.planes[0].buffer
            buf.rewind()
            if (buf.remaining() < capW * capH * 4) return
            b.copyPixelsFromBuffer(buf)

            if (!p.enabled) {
                main.post {
                    overlay?.target = null
                    G.v("invalidate") { overlay?.invalidate() }
                }
                return
            }

            if (px.size < capW * capH) px = IntArray(capW * capH)
            b.getPixels(px, 0, capW, 0, 0, capW, capH)

            val fovCap = (p.fov / scale).coerceAtLeast(20f)
            val t = Detector.detect(px, capW, capH, p.stride, fovCap, p.minPx)

            main.post {
                overlay?.fov = p.fov.toFloat()
                overlay?.target = t
                G.v("invalidate") { overlay?.invalidate() }
            }
            aim(t, p)
        } catch (t: Throwable) {
            Log.w(G.T, "frame: ${t.javaClass.simpleName} ${t.message}")
        } finally {
            G.v("close") { img.close() }
        }
    }

    private fun aim(t: Target?, p: Prefs) {
        G.v("aim") {
            val svc = AimbotA11yService.instance ?: return@v
            if (t == null) return@v

            val now = SystemClock.uptimeMillis()
            val sx = t.x * scale
            val sy = t.y * scale
            val dm = resources.displayMetrics
            val cx = dm.widthPixels / 2f
            val cy = dm.heightPixels / 2f

            if (p.dragMode) {
                if (now - lastAim < 90) return@v
                lastAim = now
                val k = 1f - (p.smooth.coerceIn(0, 95) / 100f)
                val dx = cx + (sx - cx) * k
                val dy = cy + (sy - cy) * k
                if (abs(dx - cx) < 3f && abs(dy - cy) < 3f) return@v
                svc.drag(cx, cy, dx, dy, 60L)
            }

            if (p.autoTap && now - lastFire > 260) {
                lastFire = now
                svc.tap(sx, sy)
            }
        }
    }

    private fun addOverlay() {
        if (overlayAttached) return
        if (!Settings.canDrawOverlays(this)) {
            if (overlayTries++ < 10) main.postDelayed({ addOverlay() }, 600)
            return
        }
        val v = AimView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        val ok = G.safe("addOverlay", false) {
            wm.addView(v, lp)
            true
        }
        if (ok) {
            overlay = v
            overlayAttached = true
        } else if (overlayTries++ < 10) {
            main.postDelayed({ addOverlay() }, 600)
        }
    }

    class AimView(c: Context) : View(c) {
        var target: Target? = null
        var fov: Float = 210f

        private val box = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = false
            color = Color.parseColor("#00E5FF")
        }
        private val ring = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = false
            color = Color.parseColor("#66FFFFFF")
        }
        private val dot = Paint().apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#00E5FF")
        }

        override fun onDraw(canvas: Canvas) {
            G.v("onDraw") {
                val cx = width / 2f
                val cy = height / 2f
                if (cx <= 0f || cy <= 0f) return@v
                canvas.drawCircle(cx, cy, fov.coerceAtLeast(20f), ring)
                canvas.drawCircle(cx, cy, 5f, dot)
                target?.let {
                    canvas.drawRect(
                        it.x - it.w / 2f, it.y - it.h / 2f,
                        it.x + it.w / 2f, it.y + it.h / 2f, box
                    )
                }
            }
        }
    }

    private fun addPanel() {
        if (panelAttached) return
        if (!Settings.canDrawOverlays(this)) {
            if (panelTries++ < 10) main.postDelayed({ addPanel() }, 600)
            return
        }
        val built = G.safe<LinearLayout?>("buildPanel", null) { buildPanel() }
        if (built == null) return

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 160
        }

        val ok = G.safe("addPanel", false) {
            wm.addView(built, lp)
            true
        }
        if (ok) {
            panel = built
            panelAttached = true
        } else if (panelTries++ < 10) {
            main.postDelayed({ addPanel() }, 600)
        }
    }

    private fun buildPanel(): LinearLayout {
        val p = Prefs(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(26, 18, 26, 18)
            background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(Color.parseColor("#EE0B0B10"))
                setStroke(2, Color.parseColor("#00E5FF"))
            }
        }

        val head = TextView(this).apply {
            text = "BS AIM   [drag]"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 13f
            setPadding(0, 0, 0, 8)
        }
        box.addView(head)

        box.addView(switchRow("Master", p.enabled) { p.enabled = it })
        box.addView(switchRow("Auto fire tap", p.autoTap) { p.autoTap = it })
        box.addView(switchRow("Drag mode", p.dragMode) { p.dragMode = it })
        box.addView(switchRow("Low-spec mode", p.lowMode) { p.lowMode = it })
        box.addView(slider("FOV", 80, 900, p.fov) { p.fov = it })
        box.addView(slider("Smooth %", 0, 95, p.smooth) { p.smooth = it })
        box.addView(slider("Min size", 1, 25, p.minPx) { p.minPx = it })
        box.addView(slider("Scan step", 1, 6, p.stride) { p.stride = it })

        val rearm = Button(this).apply {
            text = "Re-arm"
            textSize = 11f
            setTextColor(Color.parseColor("#00E5FF"))
            setBackgroundColor(Color.parseColor("#1A1A22"))
            setOnClickListener {
                G.v("rearm") {
                    startActivity(
                        Intent(this@OverlayService, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
        box.addView(rearm)

        head.setOnTouchListener(object : View.OnTouchListener {
            var ox = 0
            var oy = 0
            var sx = 0
            var sy = 0
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                if (!panelAttached) return true
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        ox = lpOf(box)?.x ?: 0
                        oy = lpOf(box)?.y ?: 0
                        sx = e.rawX.toInt()
                        sy = e.rawY.toInt()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val lp = lpOf(box) ?: return true
                        lp.x = ox + (e.rawX.toInt() - sx)
                        lp.y = oy + (e.rawY.toInt() - sy)
                        G.v("move") { if (box.isAttachedToWindow) wm.updateViewLayout(box, lp) }
                    }
                }
                return true
            }
        })
        return box
    }

    private fun lpOf(v: View): WindowManager.LayoutParams? {
        val a = v.layoutParams
        return if (a is WindowManager.LayoutParams) a else null
    }

    private fun switchRow(name: String, cur: Boolean, onChange: (Boolean) -> Unit): View {
        val sw = Switch(this)
        G.v("switch") {
            sw.text = name
            sw.textSize = 12f
            sw.setTextColor(Color.WHITE)
            sw.isChecked = cur
            sw.setPadding(0, 4, 0, 4)
            sw.setOnCheckedChangeListener { _, v -> G.v("swChange") { onChange(v) } }
        }
        return sw
    }

    private fun slider(name: String, min: Int, max: Int, cur: Int, onChange: (Int) -> Unit): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val label = TextView(this).apply {
            text = "$name: $cur"
            setTextColor(Color.parseColor("#C8C8D0"))
            textSize = 10f
        }
        val sb = SeekBar(this)
        G.v("slider") {
            sb.max = (max - min).coerceAtLeast(1)
            sb.progress = (cur - min).coerceIn(0, sb.max)
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, pr: Int, fromUser: Boolean) {
                    G.v("slChange") {
                        val v = (pr + min).coerceIn(min, max)
                        label.text = "$name: $v"
                        onChange(v)
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        wrap.addView(label)
        wrap.addView(sb)
        return wrap
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        G.v("trim") {
            if (level >= TRIM_MEMORY_RUNNING_LOW) {
                val p = Prefs(this)
                if (p.stride < 4) p.stride = p.stride + 1
            }
        }
    }

    override fun onDestroy() {
        running = false
        G.v("stopCapture") {
            reader?.setOnImageAvailableListener(null, null)
            reader?.close()
            reader = null
        }
        G.v("release") { vd?.release(); vd = null }
        G.v("unreg") { projCb?.let { projection?.unregisterCallback(it) }; projCb = null }
        G.v("projStop") { projection?.stop(); projection = null }
        G.v("threadQuit") { thread?.quitSafely(); thread = null }
        G.v("rmOverlay") {
            overlay?.let { if (it.isAttachedToWindow) wm.removeView(it) }
            overlay = null
            overlayAttached = false
        }
        G.v("rmPanel") {
            panel?.let { if (it.isAttachedToWindow) wm.removeView(it) }
            panel = null
            panelAttached = false
        }
        G.v("recycle") { bmp?.recycle(); bmp = null }
        super.onDestroy()
    }
}

class MainActivity : AppCompatActivity() {

    private val REQ_PROJ = 1001
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.seedIfNeeded(this)

        if (Build.VERSION.SDK_INT >= 33) {
            G.v("perm") { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 77) }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 120, 56, 56)
            setBackgroundColor(Color.parseColor("#0B0B10"))
        }

        root.addView(TextView(this).apply {
            text = "BLOOD STRIKE AIM ASSIST"
            textSize = 19f
            setTextColor(Color.WHITE)
        })

        status = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#9AA0A6"))
            setPadding(0, 24, 0, 36)
        }
        root.addView(status)

        root.addView(btn("1. Grant overlay permission") {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(btn("2. Enable accessibility service") {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(btn("3. Ignore battery optimization") { battery() })
        root.addView(btn("4. Start aim assist") { begin() })
        root.addView(btn("Stop") {
            G.v("stop") { stopService(Intent(this, OverlayService::class.java)) }
            refresh()
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        G.v("refresh") {
            val ov = Settings.canDrawOverlays(this)
            val a11y = AimbotA11yService.instance != null
            status.text = "Overlay: " + (if (ov) "OK" else "missing") +
                "\nAccessibility: " + (if (a11y) "OK" else "missing") +
                "\nDo 1 and 2 until both read OK, then press 4."
        }
    }

    private fun begin() {
        G.v("begin") {
            if (!Settings.canDrawOverlays(this)) {
                toast("Grant overlay permission first")
                return@v
            }
            if (AimbotA11yService.instance == null) {
                toast("Enable the accessibility service first")
                return@v
            }
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJ)
        }
    }

    private fun battery() {
        G.v("battery") {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                toast("Already exempt")
                return@v
            }
            open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    private fun open(i: Intent) {
        G.v("open") {
            try {
                startActivity(i)
            } catch (e: ActivityNotFoundException) {
                toast("Settings page not found")
            }
        }
    }

    private fun toast(m: String) {
        G.v("toast") { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJ) return
        if (resultCode != RESULT_OK || data == null) {
            toast("Capture denied")
            return
        }
        G.v("startSvc") {
            try {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, OverlayService::class.java).putExtra("data", data)
                )
                toast("Started - switch to Blood Strike")
                finish()
            } catch (t: Throwable) {
                toast("Could not start: ${t.javaClass.simpleName}")
            }
        }
    }

    private fun btn(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 14f
            setOnClickListener { G.v("click") { onClick() } }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        }
    }
}
