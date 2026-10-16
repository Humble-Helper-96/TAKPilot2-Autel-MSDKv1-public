package com.autel.sdksample.tak

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.autel.common.CallbackWithNoParam
import com.autel.common.CallbackWithOneParam
import com.autel.common.camera.XT706.DisplayMode
import com.autel.common.camera.base.MediaMode
import com.autel.common.camera.XT706.IrColor
import com.autel.common.error.AutelError
import com.autel.sdk.camera.AutelBaseCamera
import com.autel.sdk.widget.AutelCodecView
import com.autel.sdksample.R
import com.taklite.client.tak.TakManager
import com.taklite.util.AppLog
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * FlightActivity — TAKPilot2's flight screen for Autel.
 *
 * The Autel SDK has no ready-made flight-screen widget framework, so this screen is built
 * from primitives: fullscreen live video ([AutelCodecView]), telemetry HUD, TAK/video status,
 * an expandable TAK map with inbound markers + drop pins ([TakMapMarkers] /
 * [TakDropMarkers]), a drop-pin-at-camera-look-point action, and the video-stream toggle.
 *
 * Flying is done with the Smart Controller's physical sticks (stick/RTH input rides the
 * Skylink link, not this app). Camera/gimbal control beyond what's on the RC hardware is
 * NOT provided here yet.
 */
class FlightActivity : AppCompatActivity(), TakDropMarkers.Ui {

    private lateinit var fpvClock: TextView
    private lateinit var fpvOverlayText: TextView
    private lateinit var fpvGimbalPitch: TextView
    /** Camera media mode readout. See [renderMediaMode]. */
    private lateinit var fpvMediaMode: MediaModeView
    private lateinit var fpvHomeDistance: TextView
    private lateinit var fpvAntennaArc: AntennaAimView
    private lateinit var fpvFaaCeiling: TextView
    private lateinit var fpvRthAltitude: TextView
    private lateinit var lightsButton: ImageButton
    private lateinit var fpvNotice: TextView
    private lateinit var fpvWarningBanner: TextView
    private lateinit var fpvWarningBannerRow: View
    private lateinit var fpvWarningBannerClose: TextView

    /** True while the pilot holds the warning banner open. View state,
     *  thus it lives here and not in FlightWarnings. It clears whenever the banner hides, so
     *  a new set of warnings always arrives collapsed — see [renderWarning]. */
    private var warningExpanded = false

    /**
     * The set of warnings the pilot CLOSED, as the joined text of every line the banner showed
     * at the moment of the close. Null when nothing is closed.
     *
     * ⚠ A CLOSE HIDES ONE SET OF WARNINGS, NEVER THE BANNER ITSELF. The
     * signature is compared on every repaint, thus the banner returns the moment the live set
     * differs by one line: a new warning, a worse one, or one that cleared and came back. The
     * pilot cannot close the banner and then miss the next thing the aircraft says.
     */
    private var warningDismissedSignature: String? = null
    private lateinit var resourceMonitorRow: View
    private lateinit var resourceMonitorCells: List<TextView>
    private lateinit var crosshairView: CrosshairView
    private lateinit var arOverlay: ArOverlayView
    private lateinit var obstacleEdges: ObstacleEdgeView
    private lateinit var streamToggle: LiveToggleView
    private lateinit var recordToggle: RecordToggleView
    private lateinit var toolbarSignal: SignalBarsView
    private lateinit var toolbarSignalText: TextView
    private lateinit var arButton: TextView
    private lateinit var zoomButton: TextView
    private lateinit var irButton: TextView
    private lateinit var irPaletteButton: TextView
    /** The ⤢ / ⤡ on the thermal window's corner — see [renderPipSizeButton]. */
    private lateinit var pipSizeButton: android.widget.ImageButton
    private lateinit var emergencyBroadcastBanner: TextView
    /** The whole video frame's rect in view space, as last handed to the AR overlay. */
    private var lastVideoRect: android.graphics.RectF? = null

    /** Thermal state. Both are re-read from the camera on connect rather than assumed — see
     *  syncIrStateFromCamera(). The buttons must never claim a mode the camera is not in. */
    /**
     * What the camera shows: visible, thermal, or thermal in the centre of visible (PIP).
     * Setting this also tells the FOV model which lens is on screen, so the published <sensor>
     * cone and the AR projection follow it — routed through the setter so every assignment
     * site is covered, including the connect-time resync in [syncIrStateFromCamera].
     *
     * ⚠ This is not a boolean (`irOn`): a blend is neither value; see [CameraView]
     * for the questions each consumer now asks instead.
     */
    private var cameraView = CameraView.VISIBLE
        set(value) {
            field = value
            TakBridgeHolder.setActiveLens(value.lens)
            // Bare thermal is shown FIT and the other views FILL, thus the transform depends on
            // this state. A view change normally alters the frame size too and applyVideoFill
            // re-runs on its own; this covers the order where the state lands before the first
            // frame of the new view does, which is what the connect-time resync does.
            codecView?.let { v -> runOnUiThread { applyVideoFill(v) } }
        }
    private lateinit var blendSlider: BlendRatioSliderView
    /** Last time the drag wrote to the camera, to throttle the stroke. See [wireBlendSlider]. */
    private var lastBlendWriteMs = 0L

    /**
     * Re-reads the camera the moment a REAL one attaches, not at onResume.
     *
     * On a cold start the flight screen is up long before the aircraft. The onResume read then
     * runs against no camera and nothing asks again, thus the buttons claimed "visible" while
     * the aircraft streamed thermal. Held as a field so it can be removed in onPause.
     */
    private val cameraReadyObserver: () -> Unit = {
        AppLog.i(TAG, "camera ready — re-reading thermal mode and palette")
        syncIrStateFromCamera()
        seedZoomFromCamera()
    }

    /** Re-renders the exterior-lamp button once the aircraft has answered, and ARMS THE
     *  HARDWARE BUTTONS. The lamp READ is done by [AutelProductHolder] at connect; this only
     *  puts the answer on the screen. */
    private val connectionObserver: (Boolean) -> Unit = { connected ->
        if (connected) {
            // ⚠ THE HARDWARE BUTTONS ARM HERE, AND onResume IS NOT ENOUGH ON ITS OWN
            // (measured in flight). See installHardwareButtonListener: it needs a
            // remote controller and there is none until the aircraft connects, which on a cold
            // start is LONG after this screen opens — 21 seconds in the measured case. The
            // onResume call then returned silently and nothing asked again, so the zoom rocker,
            // C1 and C2 were all dead for 2 min 55 s. Arming is idempotent; a second arm
            // replaces our own listener with our own.
            installHardwareButtonListener()
            // DELAYED, for the same not-ready window the other at-connect calls hit: the
            // aircraft answers productConnected before the fly controller will serve a read.
            // The read is done HERE rather than in the holder because the answer has to reach
            // the button, and the 500 ms HUD tick does not re-render it.
            handler.postDelayed({
                AutelLights.refresh { runOnUiThread { renderLightsButton() } }
            }, LAMP_READ_DELAY_MS)
        } else {
            // A disconnect clears the lamp to null, thus the button must dim back to "unknown"
            // rather than keep claiming the last state of an aircraft that has gone.
            renderLightsButton()
        }
    }

    /** The palettes the button cycles through, in tap order. A subset of the XT706's twelve
     *  on purpose (white hot, black hot, Ironbow): three is a cycle a
     *  pilot can predict blind; twelve is a settings menu. The camera may still be IN one of
     *  the other nine (left there by Explorer) — [refreshIrButtons] shows whatever the
     *  camera truly holds, and the next tap re-enters the cycle at WHITE HOT. */
    private val irPaletteCycle = listOf(IrColor.WhiteHot, IrColor.BlackHot, IrColor.IronBow)
    private var irPalette: IrColor = IrColor.WhiteHot
    private lateinit var map: LockedMapView
    private lateinit var mapContainer: android.widget.FrameLayout
    /** Double-tap state of the mini-map. Deliberately NOT persisted — every entry to the flight
     *  screen starts at the normal size, so a pilot never arrives at a screen with the video
     *  half covered by a map they expanded on a previous flight. */
    private var mapExpanded = false
    /** Mini-map scale bar; its length follows [toggleMapSize] (short on the small map,
     *  full on the expanded one). */
    private var mapScaleBar: org.osmdroid.views.overlay.ScaleBarOverlay? = null
    private lateinit var mapZoomButton: TextView

    /** Mini-map zoom mode. Persisted, so a pilot who settles on one keeps it across a battery
     *  swap. Defaults to WIDE: it is the only level that keeps the home point on the map at the
     *  full max-distance limit, which is the safer state to be in if nobody ever touches this. */
    private var mapWide = true
    private lateinit var toolbarTakIcon: ImageView
    private lateinit var toolbarTakDot: ImageView
    private lateinit var toolbarBattery: BatteryGaugeView
    private lateinit var toolbarGps: TextView
    private lateinit var rthButton: ImageButton

    private var codecView: AutelCodecView? = null

    /** Watches the FPV frames arrive, so a stutter leaves a line behind. See its class note. */
    private val frameMonitor = VideoFrameMonitor()

    /** Aspect ratio (w/h) of the frames the camera is currently sending; 0 until the first
     *  frame. Changes when the pilot switches photo/video/IR — see [armVideoFill].
     *
     *  ⚠ WRITTEN ON THE SDK'S RENDER CALLBACK AND READ ON THE MAIN THREAD. It always was, and
     *  [verifyLensAgainstVideo] now reads it on a delay rather than only inside the posted
     *  repaint, so the volatile is explicit instead of resting on the post's happens-before. A
     *  stale read here would raise a FALSE alarm about the lens, which is the one outcome this
     *  check must not produce. */
    @Volatile private var videoAspect: Float = 0f
    private var aircraftMarker: Marker? = null
    private var homeMarker: Marker? = null
    private var homeLine: Polyline? = null
    private var lastHomeSet = false

    /** Zoom pill state, in RAW HUNDREDTHS (100 = 1x) — the units the camera itself takes, not
     *  a step number such as 1/2/4.
     *
     *  SEEDED FROM THE CAMERA, NEVER ASSUMED. This starts at 1x only because something has to
     *  be on screen before the first reading arrives; [seedZoomFromCamera] corrects it as the
     *  screen comes up, and every rocker press re-seeds. Autel Explorer can leave the camera
     *  zoomed, and an app that assumes 1x then labels a true 4x as "1X" — see the note in
     *  AutelProductHolder. See [applyZoomRaw]. */
    private var zoomRaw = ZOOM_RAW_PER_X

    /** Nearest whole step, for the pill's 1↔2 / 1↔4 toggles. */
    private val zoomStep: Int get() = Math.round(zoomRaw / ZOOM_RAW_PER_X.toDouble()).toInt()

    /** Rocker target, held ahead of the camera. The rocker repeats about every 200ms; a step
     *  moves this and repaints the pill at once, while the camera is written on a slower
     *  throttle — see [onZoomRocker]. */
    private var pendingZoomRaw = ZOOM_RAW_PER_X
    private var zoomPushScheduled = false

    /** Rocker press state — see [onZoomRocker]. [zoomHoldTicks] counts the ticks of the press
     *  in progress, which is what separates a press from a hold; the other two tell a continued
     *  hold apart from a new press or a reversal. */
    private var zoomHoldTicks = 0
    private var lastZoomTickMs = 0L
    private var lastZoomDirection = 0

    /** When this app last drove the zoom, for [seedZoomFromCamera]'s settle guard. Starts at a
     *  long time ago, so the first press of a screen seeds from the camera rather than trusting
     *  the 1x this class was constructed with. */
    private var lastZoomWriteMs = Long.MIN_VALUE / 2

    // FAA cell lookup cache — see updateFaaCeiling.
    private var lastFaaGridRow = Int.MIN_VALUE
    private var lastFaaGridCol = Int.MIN_VALUE
    /** Fires the "Video link down" notice on a CHANGE only — same rule as announcedMediaMode —
     *  never on the first read, so connecting with the video channel not yet up does not
     *  immediately show a notice for something the pilot did not just do. */
    private var lastVideoChannelDown: Boolean? = null
    private var cachedFaaCeilingFt: Int? = null
    private var cachedFaaWithinDownloadedArea = false

    private val handler = Handler(Looper.getMainLooper())
    private var hudTickCount = 0

    /** Live one-shot location request for the Home-point reset; removed as soon as a fix arrives
     *  or the request times out, and on screen destroy. Null when nothing is pending. */
    private var homeLocListener: android.location.LocationListener? = null
    private val refresh = object : Runnable {
        override fun run() {
            updateHud()
            // Polled rather than pushed: the aircraft's battery levels arrive from
            // AircraftSettingsDump a few seconds after connect, and setBands ignores a repeat.
            refreshBatteryBands()
            // Every ~5s, not every 500ms tick, so Detailed mode stays readable in flight.
            if (++hudTickCount % 10 == 0) logHudSnapshot()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.v(TAG, "onCreate")

        // OOM-restart guard. If the app is killed for memory in flight, Android restores the task
        // and recreates THIS activity directly — into a cold process where the Autel SDK was never
        // armed (install() only runs from Home). Coming up here would show a dead aircraft link and
        // a frozen HUD that looks live. The tell: we were restored (savedInstanceState != null) yet
        // this process never passed through Home. Bounce to Home, which re-arms the product listener
        // and lets the pilot re-enter the flight screen deliberately.
        if (savedInstanceState != null && !TakPilotHomeActivity.visitedThisProcess) {
            AppLog.w(TAG, "restored into a cold process (OOM restart) — routing to Home")
            startActivity(
                Intent(this, TakPilotHomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            finish()
            return
        }

        // osmdroid must be configured before the MapView inflates. Shared with Pre-Flight
        // Setup so the cache budget and paths cannot drift between the two screens.
        MapTileCache.configure(this)
        setContentView(R.layout.activity_flight)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goFullScreen()

        fpvClock = findViewById(R.id.fpvClock)
        fpvOverlayText = findViewById(R.id.fpvOverlayText)
        fpvGimbalPitch = findViewById(R.id.fpvGimbalPitch)
        fpvMediaMode = findViewById(R.id.fpvMediaMode)
        // The media-mode pill is a TOGGLE (design decision): a tap on the movie camera
        // asks for video, on the still camera for photo, taking nothing and recording nothing.
        // The lit half follows the camera's own report, so an ignored tap shows as ignored.
        fpvMediaMode.onModeRequested = { wanted -> onMediaModeRequested(wanted) }
        fpvHomeDistance = findViewById(R.id.fpvHomeDistance)
        fpvAntennaArc = findViewById(R.id.fpvAntennaArc)
        fpvFaaCeiling = findViewById(R.id.fpvFaaCeiling)
        fpvRthAltitude = findViewById(R.id.fpvRthAltitude)
        fpvNotice = findViewById(R.id.fpvNotice)
        fpvWarningBanner = findViewById(R.id.fpvWarningBanner)
        fpvWarningBannerRow = findViewById(R.id.fpvWarningBannerRow)
        fpvWarningBannerClose = findViewById(R.id.fpvWarningBannerClose)
        // Tap the banner to open it and read every warning; tap again to close the list. The
        // banner is drawn above the crosshair (see activity_flight.xml), thus it takes the
        // touch and a tap on a warning can never fall through and drop a marker.
        fpvWarningBanner.setOnClickListener {
            warningExpanded = !warningExpanded
            renderWarning()
        }
        // The ✕ closes the set of warnings now on the banner. A separate view, not a second
        // gesture on the text: the text's tap already means "open".
        fpvWarningBannerClose.setOnClickListener {
            // Only the aircraft's lines can be closed — DEBUG LOG ON stays. See renderWarning.
            warningDismissedSignature = FlightWarnings.display()?.all
                ?.let { FlightWarnings.dismissable(it).joinToString("\n") }
            warningExpanded = false
            AppLog.i(TAG, "warning banner closed by pilot: $warningDismissedSignature")
            renderWarning()
        }
        // Fresh flight screen: drop any banner hold left from the last visit. The active
        // set rebuilds from live telemetry within one frame.
        FlightWarnings.reset()
        resourceMonitorRow = findViewById(R.id.flightResourceMonitorRow)
        // The debug resource row draws OVER the warning banner in the bottom-left corner
        // (design decision): the row is temporary and the banner is not, so the banner
        // takes the corner and the row may cover its foot while the monitor is on.
        resourceMonitorRow.bringToFront()
        resourceMonitorCells = listOf(
            R.id.flightResSys, R.id.flightResApp, R.id.flightResCpu, R.id.flightResGpu, R.id.flightResTak,
        ).map { findViewById(it) }
        resourceMonitorRow.visibility = if (AppLog.resourceMonitor) View.VISIBLE else View.GONE
        crosshairView = findViewById(R.id.flightCrosshair)
        arOverlay = findViewById(R.id.flightArOverlay)
        obstacleEdges = findViewById(R.id.flightObstacleEdges)
        // Load the calibrated FOV before the overlay draws anything with it.
        ArSettings.loadFov(this)
        ArSettings.loadAimOffsets(this)
        // Chrome insets so edge arrows cannot be parked where they are invisible — the exact
        // case (aircraft directly overhead) the indicator matters most. Measured from the real
        // views after layout and re-read every pass, so a toolbar or map-size change cannot
        // silently break it. OPAQUE chrome only — see setChromeInsets.
        val toolbarView = findViewById<View>(R.id.flightToolbar)
        toolbarView.viewTreeObserver.addOnGlobalLayoutListener {
            // ⚠ THE HUD COLUMN'S WIDTH IS NOT THE RIGHT INSET.
            // It would be right if the readouts sat on translucent
            // panels; they do not, the column is outlined text over live video, and an arrow
            // behind it is perfectly visible. Such an inset would cost about 208dp — a fifth of
            // this screen — of the edge a pilot scans most.
            //
            // The MAP is still opaque, so its top-left corner goes across instead, and the
            // arrow is lifted above it rather than pushed inboard. The map MOVES: toggleMapSize
            // resizes it, and this listener re-reads it on every layout pass, thus the expanded
            // map is covered with no second value to keep in step.
            val mapOnScreen = IntArray(2).also { mapContainer.getLocationOnScreen(it) }
            val arOnScreen = IntArray(2).also { arOverlay.getLocationOnScreen(it) }
            // The actions column is the SAME 70 % fill as the band and must be handed over too:
            // without it, an edge arrow can be drawn on the AR pill (seen in flight).
            // Measured like everything else here, so a column that gains or loses a pill needs
            // no second value. The obstacle view gets the same inset — see below.
            val column = findViewById<View>(R.id.flightToolbarActions)
            // ⚠ THE SLIDER IS PART OF THE LEFT CHROME WHILE IT IS SHOWN. It sits beside the
            // column and takes touches, so an edge arrow or a proximity label drawn on it is
            // both hidden and in the way. Gone, it costs nothing — the column's edge again.
            val columnRight =
                if (::blendSlider.isInitialized && blendSlider.visibility == View.VISIBLE)
                    blendSlider.x + blendSlider.width
                else column.x + column.width
            // ⚠ THE RIGHT TAKES NO INSET, IT TAKES THE COLUMN'S OWN END PADDING. A screenshot
            // in flight caught an arrow in the glyphs of "200 ft AGL".
            // The no-inset decision above stands — the column is outlined text and an arrow behind
            // it is visible — so the arrow moves OUTBOARD into the strip the column already
            // leaves clear, and costs no width at all. Read from the view, thus a padding change
            // carries across on its own.
            val hudColumn = findViewById<View>(R.id.flightHudColumn)
            arOverlay.setChromeInsets(
                top = toolbarView.height.toFloat(),
                left = columnRight,
                rightGap = hudColumn.paddingEnd.toFloat(),
                mapLeft = (mapOnScreen[0] - arOnScreen[0]).toFloat(),
                mapTop = (mapOnScreen[1] - arOnScreen[1]).toFloat(),
            )
            // The obstacle radar needs the TOP inset for the same reason and did not have it:
            // its top-face arc and distance label drew from the view's top edge, which put them
            // under the toolbar. A proximity warning the pilot cannot see is worse than none,
            // because the display implies it would have told them.
            obstacleEdges.setTopInset(toolbarView.height.toFloat())
            // And the LEFT inset for the actions column: its right edge, so the
            // left-face label lands beside the column rather than under it.
            obstacleEdges.setLeftInset(columnRight)
            // The slider is placed FROM THE COLUMN and matched to its height, in the one pass
            // that already measures everything else here.
            if (::blendSlider.isInitialized) {
                val gap = resources.getDimension(R.dimen.hud_pill_stroke) * 8f
                val x = column.x + column.width + gap
                if (blendSlider.x != x) blendSlider.x = x
                if (blendSlider.layoutParams.height != column.height && column.height > 0) {
                    blendSlider.layoutParams.height = column.height
                    blendSlider.requestLayout()
                }
            }
        }
        blendSlider = findViewById(R.id.flightBlendSlider)
        wireBlendSlider()
        streamToggle = findViewById(R.id.flightStreamButton)
        recordToggle = findViewById(R.id.flightRecordButton)
        toolbarSignal = findViewById(R.id.toolbarSignal)
        toolbarSignalText = findViewById(R.id.toolbarSignalText)
        arButton = findViewById(R.id.flightArButton)
        zoomButton = findViewById(R.id.flightZoomButton)
        irButton = findViewById(R.id.flightIrButton)
        irPaletteButton = findViewById(R.id.flightIrPaletteButton)
        irButton.setOnClickListener { onIrTapped() }
        irPaletteButton.setOnClickListener { onIrPaletteTapped() }
        pipSizeButton = findViewById(R.id.flightPipSizeButton)
        pipSizeButton.setOnClickListener {
            val target = cameraView.maximised ?: return@setOnClickListener
            AppLog.v(TAG, "tap: PIP size control -> $target")
            selectCameraView(target)
        }
        emergencyBroadcastBanner = findViewById(R.id.flightEmergencyBroadcastBanner)
        // The running notice IS the control while a broadcast runs (design decision): a tap
        // adds 15 minutes, a touch-and-hold stops it. STARTING one is in the LIVE long-press
        // menu — see wireEmergencyBroadcastRow — and nothing for it sits in the actions column
        // (six pills, two widths: that is a rule).
        emergencyBroadcastBanner.setOnClickListener {
            // ⚠ RENEW ONLY. startOrRenewEmergencyBroadcast STARTS one when none is
            // running, and the GONE repaint after a cancel or an expiry arrives one main-loop
            // message late (the core posts it). A tap in that window would start a FRESH broadcast
            // nobody asked for. Starting belongs to the LIVE menu. The mode is read too, so a tap
            // can never start one in BASIC whatever the view state.
            val tm = TakManager.getInstance()
            if (!AppMode.emergencyBroadcastAllowed(modePrefs()) || !tm.isEmergencyBroadcastActive()) {
                AppLog.i(TAG, "tap: Emergency Broadcast notice — nothing running, not started")
                return@setOnClickListener
            }
            AppLog.i(TAG, "tap: Emergency Broadcast notice — renew")
            tm.startOrRenewEmergencyBroadcast()
        }
        emergencyBroadcastBanner.setOnLongClickListener {
            AppLog.i(TAG, "touch-and-hold: Emergency Broadcast notice — stop")
            TakManager.getInstance().cancelEmergencyBroadcast()
            true
        }
        TakManager.getInstance().addEmergencyBroadcastListener(emergencyBroadcastListener)
        paintEmergencyBroadcast(
            TakManager.getInstance().isEmergencyBroadcastActive(),
            TakManager.getInstance().emergencyBroadcastExpiresAtEpochMs(),
        )
        map = findViewById(R.id.flightMap)
        mapContainer = findViewById(R.id.flightMapContainer)
        // ROUND THE MAP ITSELF, not just its frame.
        //
        // bg_map_outline is the container's FOREGROUND: it draws the border over the map but
        // clips nothing, so rounding that drawable alone leaves square tiles filling the corners
        // the border has curved away from. The clip has to happen here.
        //
        // This works because osmdroid's MapView draws its tiles onto the ordinary view Canvas.
        // ⚠ IT WOULD NOT WORK FOR A SurfaceView-BACKED MAP — a surface is composited separately
        // and a parent's outline clip does not touch it. If the map is ever swapped for one of
        // those (MapLibre and Google Maps both use one by default), this silently stops clipping
        // and the corners come back square while the border stays rounded.
        //
        // ⚠ THE CLIP RADIUS IS THE FRAME'S PLUS HALF THE STROKE. IT IS NOT hud_pill_radius.
        //
        // A GradientDrawable strokes a path that is INSET BY HALF THE STROKE WIDTH from the
        // drawable's bounds, and the stroke straddles that path. So a frame declaring an 8dp
        // corner actually presents an OUTER edge of radius 8 + strokeWidth/2, which curves away
        // from the corner faster than an 8dp clip does. Clipping at the frame's own 8dp left a
        // sliver of map outside the border at each corner — the map was
        // correctly clipped and the border was correctly drawn; they simply were not the same
        // curve.
        //
        // ⚠ THE CUT IS PUT ON THE STROKE'S CENTRE LINE, NOT ON ITS OUTER EDGE — that is the
        // whole +stroke rather than +stroke/2 below.
        //
        // Matching the outer edge exactly was tried first and STILL left a sliver on one corner.
        // Two anti-aliased edges — the renderer's outline clip and the
        // drawable's stroke — were being asked to agree to the pixel, and they do not have to.
        // Landing the cut on the middle of the band instead gives half the stroke width of
        // tolerance on EACH side: the border covers the map with room to spare, and there is
        // still half a stroke of border inboard of the cut, so no video shows through between
        // them either.
        //
        // The obvious alternative was to thicken the frame. That is rejected on purpose:
        // hud_text_outline_width is the ONE edge weight the whole HUD shares — the readouts and
        // the EV slider draw with it too — so widening it to fix a corner would thicken every
        // glyph outline on the screen. This costs nothing outside the map.
        //
        // Both dimens are per-device (hud_text_outline_width has a values-w820dp override), thus
        // this is computed rather than written down as a third number that would drift.
        //
        // A hair of pale map can still be found at one corner under magnification. Widening the
        // frame to close it was tried and rejected — see bg_map_outline. Do not reopen it.
        val mapFrameStroke = resources.getDimension(R.dimen.hud_text_outline_width)
        val mapClipRadius = resources.getDimension(R.dimen.hud_pill_radius) + mapFrameStroke
        mapContainer.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, mapClipRadius)
            }
        }
        mapContainer.clipToOutline = true
        map.onDoubleTap = { toggleMapSize() }
        // Must be resolved before the map setup below, which calls applyMapZoom() and labels
        // this button.
        mapZoomButton = findViewById(R.id.flightMapZoomButton)
        toolbarTakIcon = findViewById(R.id.toolbarTakIcon)
        toolbarTakDot = findViewById(R.id.toolbarTakDot)
        toolbarBattery = findViewById(R.id.toolbarBattery)
        toolbarGps = findViewById(R.id.toolbarGps)

        // Live video, full screen behind everything.
        val container = findViewById<FrameLayout>(R.id.videoContainer)
        codecView = AutelCodecView(this).also { container.addView(it); armVideoFill(it) }

        // Locked TAK mini-map. Pan/fling/double-tap-zoom are blocked inside [LockedMapView];
        // these two calls kill the remaining interactive affordances (pinch-zoom and the
        // built-in +/- buttons). Orientation is never touched, so north stays up, and the only
        // things that ever move the camera are updateHud()'s recenter and the zoom toggle.
        mapWide = getSharedPreferences("takpilot2_tak", MODE_PRIVATE)
            .getBoolean(KEY_MAP_WIDE, true)
        // OpenStreetMap, fixed. There is no setting to choose a source or pre-download an
        // area (design decision). Tiles still cache as
        // they are viewed, and osmdroid trims that store itself — see MapTileCache.configure.
        map.setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(false)
        map.setBuiltInZoomControls(false)
        map.setFlingEnabled(false)
        // Zoom is clamped to exactly the two levels the toggle offers. Blocking the gestures
        // above stops a PILOT dragging the zoom around; this stops everything else — an
        // osmdroid tile fallback, a stray zoomIn/zoomOut, a restored instance state. The map
        // still only ever sits at MAP_ZOOM_WIDE or MAP_ZOOM_NEAR; nothing lands in between.
        map.minZoomLevel = MAP_ZOOM_WIDE
        map.maxZoomLevel = MAP_ZOOM_NEAR
        applyMapZoom(persist = false)
        // Center immediately, before any fix. Without this the map sits at osmdroid's (0, 0)
        // default — open ocean off West Africa — because the per-tick recenter in updateHud()
        // is gated behind hasFix. A pilot who opens the flight screen while the aircraft is
        // still acquiring satellites would otherwise see a blank blue square and reasonably
        // conclude the map is broken.
        map.controller.setCenter(GeoPoint(DEFAULT_LAT, DEFAULT_LON))

        // Home→aircraft line, added before any marker so it renders underneath them. Empty and
        // hidden until both a home point and a live fix exist (see updateHud()).
        homeLine = Polyline(map).apply {
            outlinePaint.color = androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_state_danger)
            outlinePaint.strokeWidth = 2.5f * resources.displayMetrics.density
            isVisible = false
            infoWindow = null
            map.overlays.add(this)
        }

        TakDropMarkers.ui = this
        TakMapMarkers.install(applicationContext)
        TakDropMarkers.init(applicationContext)
        refreshBatteryBands()
        TakMapMarkers.onMapReady(map)

        // Distance scale, bottom-right of the mini-map (without a
        // scale the pilot cannot judge how far map items are). osmdroid recomputes the
        // bar for each zoom level, so WIDE and NEAR each read correctly. Imperial to match
        // every other HUD distance (see Units). Half the default bar length (maxLength is
        // in on-screen centimetres) so it shares the bottom row with the zoom toggle
        // without ever reaching it — a shorter bar was preferred over a raised one.
        val density = resources.displayMetrics.density
        mapScaleBar = org.osmdroid.views.overlay.ScaleBarOverlay(map).apply {
            unitsOfMeasure = org.osmdroid.views.overlay.ScaleBarOverlay.UnitsOfMeasure.imperial
            setAlignBottom(true)
            setAlignRight(true)
            setMaxLength(SCALE_BAR_CM_SMALL)
            setScaleBarOffset((8 * density).toInt(), (8 * density).toInt())
            setTextSize(10 * density)
            map.overlays.add(this)
        }

        findViewById<ImageButton>(R.id.flightBackButton).setOnClickListener {
            AppLog.v(TAG, "Back tapped"); finish()
        }
        rthButton = findViewById(R.id.flightRthButton)
        rthButton.setOnClickListener {
            // ⚠ A TOUCH DURING A RETURN OPENS THE MENU (design decision). Asking "Return to
            // Home?" again would offer a pilot touching RTH to make sure the aircraft IS
            // returning the command it was already flying, with Cancel Return one long-press
            // away. Instead the menu, where Cancel Return is, opens.
            if (FlightWarnings.returningHome(FlightWarnings.flyMode)) {
                AppLog.v(TAG, "RTH touched during a return — menu")
                rthMenu()
                return@setOnClickListener
            }
            AppLog.v(TAG, "RTH tapped")
            confirmRth()
        }
        // Long-press opens the RTH menu rather than going straight to the home-point reset,
        // because Cancel Return needs a home too — see rthMenu. A touch during a
        // return opens it too (above).
        rthButton.setOnLongClickListener {
            AppLog.v(TAG, "RTH long-pressed — menu")
            rthMenu()
            true
        }

        // ---- Toolbar controls. Any control not yet functional on this airframe is
        // deliberately VISIBLE rather than omitted, so the toolbar a pilot learns stays the same
        // — and each says plainly what it is when
        // pressed, rather than looking broken or doing nothing. See notImplemented().
        arButton.setOnClickListener { onArToggleTapped() }
        // Same long-press idiom as RTH (reset home) and drop-pin (markers list).
        arButton.setOnLongClickListener { onArOptionsTapped(); true }
        // ON WHEN THE SCREEN OPENS (design decision). Started here rather than in onResume
        // deliberately: onDestroy stops the overlay, thus each entry to the flight screen brings
        // it up on, while a mere pause — a dialog, the Field Guide — leaves it as the pilot set
        // it. Turning it off and returning to the screen does bring it back; that is the
        // deliberate choice of "always on" over "remember my last setting".
        arOverlay.start()
        refreshArButton()
        zoomButton.setOnClickListener {
            AppLog.v(TAG, "tap: Zoom (currently ${zoomLabel(zoomRaw)})")
            // Tap cycles 1X <-> 2X. From 4X it returns to 1X rather than stepping down,
            // so one tap always gets the pilot back to the widest view. From a rocker-set
            // value it rounds to the nearest step first, so the tap is never a no-op.
            applyZoom(if (zoomStep == 1) 2 else 1)
        }
        zoomButton.setOnLongClickListener {
            AppLog.v(TAG, "long-press: Zoom (currently ${zoomLabel(zoomRaw)})")
            applyZoom(if (zoomStep == 4) 1 else 4)
            true
        }
        // Exterior LEDs. This slot is not a video re-sync button: the video is stable on
        // this airframe, and going dark at night is an operational requirement for a public-safety
        // aircraft — crews get shot at when the aircraft advertises its position.
        //
        // The button reports the AIRCRAFT's state, never our request. See AutelLights for what
        // this does and does not cover; it darkens the navigation LEDs, which is not the same
        // claim as "every exterior light is off".
        lightsButton = findViewById(R.id.flightLightsButton)
        lightsButton.setOnClickListener {
            val currentlyDark = AutelLights.isDark == true
            AppLog.v(TAG, "tap: Exterior lights (currently dark=$currentlyDark)")
            lightsButton.isEnabled = false
            AutelLights.setAllOff(!currentlyDark) { confirmed ->
                runOnUiThread {
                    lightsButton.isEnabled = true
                    renderLightsButton()
                    if (!confirmed) toast("The aircraft did not change the lights.")
                    else if (AutelLights.isDark == true) toast("Exterior lights OFF")
                    else toast("Exterior lights ON")
                }
            }
        }
        renderLightsButton()
        AutelLights.refresh { runOnUiThread { renderLightsButton() } }
        recordToggle.setOnClickListener {
            AppLog.v(TAG, "tap: REC")
            onRecordToggleTapped()
        }
        // NOTE: this must be the ONLY click listener on this view. Two setOnClickListener calls
        // on one view is silent — the last one wins — so a second handler would be dead code
        // that reads as live.
        findViewById<View>(R.id.toolbarTakButton).setOnClickListener {
            AppLog.v(TAG, "tap: TAK connection toggle")
            TakAutoConnect.toggle(applicationContext) { _, msg ->
                runOnUiThread { toast(msg) }
            }
        }
        // Same long-press idiom as the AR button and drop-pin: the short press does the common
        // thing, the long press opens what belongs to it.
        findViewById<View>(R.id.toolbarTakButton).setOnLongClickListener {
            AppLog.v(TAG, "long-press: TAK channels")
            onTakChannelsTapped()
            true
        }

        mapZoomButton.setOnClickListener {
            mapWide = !mapWide
            AppLog.v(TAG, "tap: mini-map zoom -> ${if (mapWide) "WIDE" else "NEAR"}")
            applyMapZoom(persist = true)
        }

        toolbarSignal.setOnClickListener { signalDetail() }
        toolbarSignalText.setOnClickListener { signalDetail() }
        // NOT setOnClickListener: EvSliderView consumes ACTION_DOWN and returns true without
        // calling super, so performClick() never runs and a click listener would be dead code.
        // onIndexChanged is the callback it actually invokes.
        val evSlider = findViewById<EvSliderView>(R.id.evSlider)
        evSlider.steps = AutelExposureController.sliderMax
        evSlider.index = AutelExposureController.savedSliderIndex(this)
        evSlider.onIndexChanged = { idx, fromUser ->
            if (fromUser) {
                // v() not i(): a drag fires this on every step, so keep it out of a
                // Standard-level capture.
                AppLog.v(TAG, "EV slider -> ${AutelExposureController.labelAt(idx)} (index $idx)")
                AutelExposureController.setEvAt(
                    applicationContext, AutelProductHolder.xt706, idx) {}
            }
        }

        crosshairView.onReticleTap = { onQuickMarkerAction("tap: reticle") }
        crosshairView.onReticleLongPress = { onUnknownMarkerAction("long-press: reticle") }

        VideoStreamerHolder.onStateChanged = Runnable { refreshStreamToggle() }
        refreshStreamToggle()
        streamToggle.setOnClickListener {
            if (VideoStreamerHolder.isActive) {
                AppLog.v(TAG, "LIVE tapped: stopping")
                ScreenCaptureService.stop(applicationContext)
                VideoStreamerHolder.stop()
                toast("Video stream stopped")
                refreshStreamToggle()
            } else {
                onStartStreamTapped()
            }
        }
        // Long-press picks the quality tier, same tap/long-press split the AR and drop-pin
        // buttons use: short tap does the obvious thing, long-press configures it.
        streamToggle.setOnLongClickListener { onVideoQualityTapped(); true }

        // Single drop-pin action, placed at the camera look-point. The mini-map is locked, so
        // there is no tap-the-map placement — TakBridgeHolder.lookPoint() is the cursor, giving
        // the DTED-terrain-corrected ground intersection of the camera's line of sight. The drop
        // is refused outright when it's unavailable:
        // a marker at a plausible-but-wrong position is worse for the shared picture than none.
        // Long-press the drop button to manage already-dropped pins (move / rename / retype /
        // re-send / delete / clear-all) — no map interaction needed, consistent with the
        // locked mini-map.
        findViewById<ImageButton>(R.id.flightDropPinButton).setOnLongClickListener {
            AppLog.v(TAG, "long-press: markers list")
            onMarkersListTapped()
            true
        }
        // SHORT TAP IS ALWAYS "CREATE AND DROP A MARKER" (design decision): tap makes a new
        // marker, long-press manages the existing ones.
        //
        // An earlier pass sent a GATED short tap to the markers list instead. That came from a
        // real complaint — the button used to be a dead end when the aim was poor — but it
        // over-corrected: on the ground, or at a shallow gimbal angle, the gate is the NORMAL
        // state, so in practice both gestures landed on the same list and the create flow became
        // unreachable. Two gestures that do the same thing are one gesture and a bug.
        //
        // So the picker opens unconditionally and the gate is evaluated at PLACEMENT. The pilot
        // always reaches the menu, and still cannot place a marker whose position would be wrong
        // — which was the point of the gate all along. The reason is shown in the picker's title
        // too, so it is known BEFORE choosing rather than after.
        findViewById<ImageButton>(R.id.flightDropPinButton).setOnClickListener {
            AppLog.v(TAG, "Drop Pin tapped")
            pickAffiliationThen { aff ->
                // Re-read the look-point HERE, not before the dialog: the aircraft keeps flying
                // while the picker is open, so the position captured when the button was tapped
                // may be seconds stale by the time an affiliation is chosen.
                val gp = TakBridgeHolder.lookPoint()
                when {
                    aimTooPoorToDrop() -> refuseDropForAim()
                    gp == null -> {
                        AppLog.w(TAG, "drop refused — no look-point (GPS/gimbal not ready)")
                        showNotice("Cannot place the marker. Wait for GPS and the gimbal.",
                            refused = true)
                    }
                    else -> TakDropMarkers.placeAt(aff, gp.first, gp.second, gp.third)
                }
            }
        }
    }

    /**
     * Hides the status and navigation bars for the whole flight screen.
     *
     * Two reasons, and the second is the operational one:
     *  1. The bars cost screen the FPV image should have.
     *  2. The TAK feed is a SCREEN CAPTURE, so the Android status bar was being broadcast to the
     *     whole team — clock, battery, carrier, and any notification that happened to arrive.
     *     That is both clutter and a small privacy leak on a shared picture.
     *
     * BEHAVIOUR_SHOW_TRANSIENT_BARS_BY_SWIPE, not a hard hide: a pilot must still be able to
     * reach the bars deliberately, and they slide back away on their own. A screen that cannot
     * be escaped is the wrong answer on a device that also has to do other things.
     *
     * Re-applied in [onWindowFocusChanged] because Android restores the bars after a dialog,
     * a permission prompt or a screen-off — and this screen shows plenty of dialogs.
     */
    @Suppress("DEPRECATION")
    private fun goFullScreen() {
        // Legacy systemUiVisibility, NOT WindowInsetsController. This project is on
        // androidx.appcompat 1.0.0, whose androidx.core predates WindowInsetsControllerCompat,
        // and bumping androidx across this old dependency set to hide a status bar is a much
        // bigger risk than using the deprecated call. Deprecated is not broken: these flags are
        // still honoured through API 33, which is this app's compileSdk.
        //
        // IMMERSIVE_STICKY is the deliberate choice over plain IMMERSIVE: the bars come back
        // transiently on a swipe and hide themselves again, so a pilot can always reach them
        // and never has to think about restoring the screen afterwards.
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullScreen()
    }

    override fun onResume() {
        super.onResume()
        AppLog.v(TAG, "onResume")
        AutelProductHolder.install()   // reclaim the global product listener (see holder docs)
        AutelProductHolder.addCameraReadyListener(cameraReadyObserver)
        AutelProductHolder.addListener(connectionObserver)
        // Re-read the camera's real thermal state each time this screen comes up. Autel's own
        // app can have left the camera in IR, and the buttons must reflect the camera rather
        // than a default. No-ops when no camera is attached.
        syncIrStateFromCamera()
        // Same reasoning for the zoom, and the same source of error: Autel's own app can have
        // left the camera zoomed. The pill must show what the AIRCRAFT holds.
        seedZoomFromCamera()
        map.onResume()
        installHardwareButtonListener()
        ControllerCompass.start(this)   // BVLOS antenna aim; no-op without the sensor
        // Pre-Flight Setup may have changed the two battery levels while this screen was in the
        // background, and the gauge's colours are meaningless if they lag them.
        refreshBatteryBands()
        handler.post(refresh)
    }

    /**
     * Colours the toolbar gauge from the SAME two settings Pre-Flight sends to the aircraft:
     * amber from Battery Warning, red from Battery Critical. Hard-coded edges here would drift
     * from the thresholds every time they were retuned, which is how the gauge previously ended
     * up showing amber while the aircraft was seconds from acting.
     */
    private fun refreshBatteryBands() {
        // AIRCRAFT FIRST, pref only as a stand-in. The pilot's saved value is what they INTEND;
        // it differs from the aircraft's whenever a level was edited but not applied, or an
        // apply failed. A gauge is read to judge how much flying is left, so it has to be
        // coloured from the levels the aircraft will actually act on.
        val warn = FlightLimitsController.aircraftWarningPct
            ?: FlightLimitsController.savedLowBatteryPct(this).toFloatOrNull() ?: 15f
        val crit = FlightLimitsController.aircraftCriticalPct
            ?: FlightLimitsController.savedCriticalBatteryPct(this).toFloatOrNull() ?: 10f
        toolbarBattery.setBands(crit, warn)
    }

    override fun onPause() {
        super.onPause()
        AppLog.v(TAG, "onPause")
        // Paired with the registrations in onResume. Both lists are also cleared when the link
        // is released, thus a missed removal cannot leak an activity across a product cycle.
        AutelProductHolder.removeCameraReadyListener(cameraReadyObserver)
        AutelProductHolder.removeListener(connectionObserver)
        map.onPause()
        ControllerCompass.stop()
        // Dropped while this screen is not showing: the hardware button must not
        // place markers from the home screen or with the app in the background.
        runCatching { AutelProductHolder.evo2?.remoteController?.setRemoteButtonControllerListener(null) }
        handler.removeCallbacks(refresh)
        // The lens check holds this activity for its settle window and would otherwise fire at a
        // screen that is no longer showing — see verifyLensAgainstVideo.
        lensVerify?.let { handler.removeCallbacks(it) }
        lensVerify = null
        homeVerify?.let { handler.removeCallbacks(it) }
        homeVerify = null
        zoomVerify?.let { handler.removeCallbacks(it) }
        zoomVerify = null
        zoomRestore?.let { handler.removeCallbacks(it) }
        zoomRestore = null
    }

    /**
     * Maps the CONTROLLER HARDWARE BUTTONS onto the two controls a pilot most wants without
     * taking a hand off the sticks or hunting for a touch target:
     *
     *   C2 (custom B) — the quick marker: place it, or move it to what the camera is on
     *   C1 (custom A) — thermal: short changes visible/thermal, long changes the IR colours
     *
     * Short press  -> place the quick marker ([TakDropMarkers.QUICK_NAME])
     * Long press   -> re-aim the existing one at what the camera is looking at now
     *
     * Deliberately the SAME two functions the reticle already offers, so the two routes cannot
     * drift apart — and both go through the red-reticle gate, so a hardware button cannot place
     * a marker the touch UI would have refused.
     *
     * ⚠ WHICH PHYSICAL BUTTON IS UNCONFIRMED. The SDK exposes only CUSTOM_BUTTON_{SHORT,LONG}_A
     * and _B — there is no "C1"/"C2" in its vocabulary — and nothing documents which label maps
     * to which letter. [QUICK_MARKER_BUTTON] is set to B as the working assumption for C2; EVERY
     * event received is logged, so one press tells us the truth and the constant is a one-line
     * correction. Guessing silently is how the wrong button ends up wired.
     *
     * Registered per-screen rather than globally: it is dropped in onPause so the button cannot
     * place markers from the home screen or with the app in the background.
     *
     * ⚠ **CALLED FROM onResume AND FROM [connectionObserver], AND IT NEEDS BOTH** (measured in
     * flight). There is no remote controller until the aircraft connects. On a cold
     * start the flight screen is open long before that — 21 seconds in the measured case — so
     * the onResume call found nothing, and for as long as nothing asked again EVERY hardware
     * button was dead: the zoom rocker, C1, C2, and the shutter's own event. It lasted 2 min
     * 55 s and ended only because the pilot started the video stream: the screen-capture consent
     * dialog paused the activity, and coming back re-ran onResume with the aircraft now present.
     *
     * ⚠ **AND IT SAYS SO WHEN IT CANNOT ARM.** The old `?: return` was silent, which is why a
     * dead rocker looked like a broken rocker rather than a listener that was never installed.
     * A registration that does not happen must leave a line behind — the same habit applied
     * to the SDK, applied to our own wiring.
     *
     * The camera had the same fault and got a connect-time observer of its own. Every
     * registration of that shape on this screen is covered: syncIrStateFromCamera and
     * seedZoomFromCamera by the camera-ready observer, ControllerCompass and the battery bands
     * need no aircraft, and this listener by [connectionObserver].
     */
    private fun installHardwareButtonListener() {
        val rc = AutelProductHolder.evo2?.remoteController
        if (rc == null) {
            AppLog.i(TAG, "hardware buttons NOT armed — no remote controller yet. " +
                "They arm when the aircraft connects.")
            return
        }
        runCatching {
            rc.setRemoteButtonControllerListener(
                object : com.autel.common.CallbackWithOneParam<
                    com.autel.common.remotecontroller.RemoteControllerNavigateButtonEvent> {
                    override fun onSuccess(
                        e: com.autel.common.remotecontroller.RemoteControllerNavigateButtonEvent?,
                    ) {
                        e ?: return
                        // READ keyValue HERE AND NOWHERE ELSE. The event is an ENUM CONSTANT,
                        // thus a process-wide singleton, and the SDK's packet parser mutates
                        // this field on it (RCButtonPacket.parseBody -> setKeyValue) before
                        // handing it over. Reading it after a thread hop would read whatever
                        // the NEXT packet has since written. Captured now, passed by value.
                        val keyValue = e.keyValue
                        // Logged unconditionally — this is how the button mapping gets confirmed,
                        // and how anyone later finds out what the other controls emit.
                        AppLog.i(TAG, "controller button event: $e (keyValue=$keyValue)")
                        // SDK thread — everything below shows toasts and touches views.
                        when (e.name) {
                            // C2: markers, MIRRORING the crosshair exactly — short re-aims the one
                            // quick marker, long drops a NEW stationary Unknown marker. Same idiom
                            // as C1 and the IR buttons: a pilot who has learned the on-screen
                            // control has learned the button, and both routes end in the SAME
                            // function, thus the two can never drift apart. The SDK gives short
                            // and long as separate events, thus no timer is needed here.
                            "CUSTOM_BUTTON_SHORT_$QUICK_MARKER_BUTTON" ->
                                runOnUiThread { onQuickMarkerAction("controller C2 short") }
                            "CUSTOM_BUTTON_LONG_$QUICK_MARKER_BUTTON" ->
                                runOnUiThread { onUnknownMarkerAction("controller C2 long") }

                            // C1: thermal, MIRRORING the on-screen IR buttons exactly — short
                            // changes the camera between visible and thermal, long changes the
                            // thermal colours. A pilot who has learned one control has learned
                            // the other, and both routes end in the SAME two functions, thus the
                            // button labels and the camera cannot disagree.
                            //
                            // The long press does nothing while the visible camera is live: the
                            // palette button is hidden then, and a colour change the pilot cannot
                            // see is worse than no action. It is announced, not silent.
                            "CUSTOM_BUTTON_SHORT_$IR_BUTTON" ->
                                runOnUiThread { onIrTapped() }
                            "CUSTOM_BUTTON_LONG_$IR_BUTTON" ->
                                runOnUiThread {
                                    if (cameraView.hasThermal) onIrPaletteTapped()
                                    else showNotice("The thermal camera is off.")
                                }

                            // RIGHT ZOOM ROCKER — continuous zoom, confirmed on hardware:
                            // deflecting it emits ZOOM_IN/ZOOM_OUT on THIS listener,
                            // about every 200ms. The left control is the gimbal and emits nothing here, so
                            // there is no ambiguity between the two.
                            //
                            // keyValue IS THE DEFLECTION, 0 TO 225, AND ZERO MEANS RELEASED.
                            // The rocker keeps emitting while the spring returns it to centre,
                            // and those trailing events carry keyValue 0 — that is the whole
                            // release signal, and dropping them is what stops the zoom running
                            // on after the pilot lets go. Measured over 45 presses on hardware:
                            // every one ended in two or three keyValue-0 events and
                            // NOT ONE had a zero among the held events, so the test is exact
                            // rather than a heuristic. Reading only e.name cannot tell the two
                            // apart; replaying those 45 presses that way
                            // shows the zoom running on by as much as 1.8x past the release.
                            //
                            // Deliberately NOT routed through applyZoom(step): the whole point
                            // is the values between the steps. See onZoomRocker.
                            "ZOOM_IN" -> if (keyValue > 0) runOnUiThread { onZoomRocker(+1) }
                            "ZOOM_OUT" -> if (keyValue > 0) runOnUiThread { onZoomRocker(-1) }
                            // ⚠ **THE FIRST PRESS OF THE HARDWARE RECORD BUTTON IN STILLS MODE
                            // ONLY CHANGES THE MODE. IT DOES NOT RECORD** (confirmed
                            // in the log):
                            //
                            //   14:03:06  START_VIDEO   <- no RECORD_START follows
                            //   14:03:10  START_VIDEO
                            //   14:03:10  RECORD_START  <- 97 ms later
                            //
                            // This is the SAME firmware behaviour onRecordToggleTapped works
                            // around with MODE_SWITCH_SETTLE_MS — the camera acknowledges a mode
                            // change before it can act on it. The on-screen REC pill is fine
                            // because it reads the mode, sets it, waits and then VERIFIES. This
                            // button has nothing in front of it: the camera acts on the press
                            // natively and this application only hears about it.
                            //
                            // ⚠ SO THE SECOND PRESS IS MADE FOR THE PILOT. The camera has
                            // already done the mode change by the time this event arrives, and
                            // it does NOT also start — so there is no competing start to race,
                            // only the settle the camera needs before it will act. After that
                            // settle this issues the start through the SAME verified path the
                            // REC pill uses, watchdog and all.
                            //
                            // ⚠ RE-CHECKED AFTER THE SETTLE, because the pilot may press again
                            // out of habit in that window. The camera acts on THAT press itself,
                            // so if a recording is already running this must not issue a second
                            // start. A double start on the one control that must not lie is
                            // worse than the button press it would have saved.
                            "START_VIDEO" -> {
                                val camNow = AutelProductHolder.camera
                                if (AutelProductHolder.mediaMode != MediaMode.VIDEO &&
                                    !AutelProductHolder.isRecording &&
                                    !hardwareRecordPending && camNow != null) {
                                    // No fixed wait: the start
                                    // follows the camera's own report of VIDEO — see
                                    // startRecordFromAnyMode. The pending flag stops a second
                                    // press in that window queuing a second start.
                                    hardwareRecordPending = true
                                    AppLog.i(TAG, "hardware record pressed in " +
                                        "${AutelProductHolder.mediaMode} — starting once the " +
                                        "camera reports VIDEO")
                                    runOnUiThread {
                                        startRecordFromAnyMode(camNow)
                                        handler.postDelayed({ hardwareRecordPending = false },
                                            MODE_POLL_LIMIT_MS + MODE_READY_SETTLE_MS)
                                    }
                                }
                            }
                        }
                    }
                    override fun onFailure(error: AutelError?) {
                        AppLog.w(TAG, "controller button listener error: ${error?.description}")
                    }
                })
            AppLog.i(TAG, "hardware quick-marker button armed (custom button $QUICK_MARKER_BUTTON)")
        }.onFailure { AppLog.w(TAG, "hardware button listener install failed: ${it.message}") }
    }

    /**
     * Stops the outbound stream when the flight screen is no longer on display.
     *
     * **This is a privacy control, not housekeeping.** The stream is a mirror of the whole
     * screen. Once the pilot leaves this activity — home screen, another app, Pre-Flight
     * Setup — a capture that kept running would broadcast whatever they do next to the entire
     * team. Nothing else in the app can leak like that, so it is stopped the moment this
     * screen stops being visible.
     *
     * onStop, not onPause: a dialog (markers list, RTH confirm, AR options) only pauses the
     * activity, and killing the stream every time a pilot opens a menu would make it unusable.
     * onStop fires only when the screen is genuinely no longer shown.
     *
     * The TAK connection and telemetry bridge deliberately keep running — those belong to the
     * foreground service and carry no screen contents.
     */
    override fun onStop() {
        super.onStop()
        if (VideoStreamerHolder.isActive) {
            AppLog.i(TAG, "flight screen no longer visible — stopping screen capture")
            ScreenCaptureService.stop(applicationContext)
            VideoStreamerHolder.stop()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLog.v(TAG, "onDestroy")
        // Drop every pending delayed callback on this screen's handler. onPause removes the HUD
        // refresh loop, but the one-shot postDelayed lambdas (record-verify after a mode-switch
        // settle, the transient-notice auto-hide) capture the camera/context and would otherwise
        // fire against a destroyed activity if it is torn down inside their delay window. None
        // needs to survive the screen — they touch this screen's views.
        handler.removeCallbacksAndMessages(null)
        // Stop a Home-reset location request if one is still waiting for a fix.
        runCatching {
            stopHomeLocationUpdates(
                getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
            )
        }
        runCatching { TakManager.getInstance().removeEmergencyBroadcastListener(emergencyBroadcastListener) }
        // Guarded: the cold-restore bounce in onCreate finishes before arOverlay is set, and
        // onDestroy still runs.
        if (::arOverlay.isInitialized) arOverlay.stop()
        VideoStreamerHolder.onStateChanged = null
        TakMapMarkers.onMapDestroyed()
        // Belt and braces on top of onStop() — a destroy without a stop shouldn't be possible,
        // but leaving a screen capture alive would be the one leak worth being paranoid about.
        ScreenCaptureService.stop(applicationContext)
        VideoStreamerHolder.stop()
        // NOTE: the bridge and TAK connection deliberately keep running — they belong to the
        // foreground service lifecycle, not this screen.
    }

    private fun logHudSnapshot() {
        val hud = TakBridgeHolder.hud()
        if (hud == null || !hud.hasFix) {
            AppLog.v(TAG, "hud: no GPS fix, battery=${hud?.batteryPct ?: "—"}%")
        } else {
            AppLog.v(TAG, "hud: lat=${hud.lat} lon=${hud.lon} alt=${hud.relAlt}m " +
                    "spd=${hud.speedMs}m/s hdg=${hud.headingDeg}deg bat=${hud.batteryPct}% sat=${hud.sats}")
        }
    }

    // ---- HUD ----

    /**
     * Repaints the warning banner from [FlightWarnings.display]. Polled from the HUD tick, and
     * called at once by the two taps on the banner.
     *
     * Collapsed: the worst warning and a "+N" count. Open: every warning on its own line, worst
     * first. Closed by the ✕: hidden while the live set is the SAME set that was closed, and
     * back the moment it differs — see [warningDismissedSignature].
     */
    private fun renderWarning() {
        val d = FlightWarnings.display()
        if (d == null) {
            fpvWarningBannerRow.visibility = View.GONE
            // Nothing stands, thus nothing stays open and nothing stays closed. The next set
            // of warnings gets a fresh, collapsed banner — even one whose text repeats the
            // last, because the pilot closed THAT occurrence, not the words.
            warningExpanded = false
            warningDismissedSignature = null
            return
        }
        // The closable part of the banner is the aircraft's lines. DEBUG LOG ON is never
        // closed (design decision): with the rest dismissed it stands alone, amber, until
        // the log is turned off; a banner that is ONLY that line has nothing for the ✕ to do.
        val closable = FlightWarnings.dismissable(d.all)
        val signature = closable.joinToString("\n")
        val debugLine = d.all.firstOrNull { it !in closable }
        if (closable.isEmpty() || signature == warningDismissedSignature) {
            if (debugLine == null) {
                fpvWarningBannerRow.visibility = View.GONE
                return
            }
            fpvWarningBanner.text = debugLine
            // Nothing left to close, so no ✕ — a control that does nothing teaches the pilot
            // that the banner does not mean what it says.
            findViewById<View>(R.id.fpvWarningBannerClose).visibility = View.GONE
            fpvWarningBannerRow.background?.setTint(androidx.core.content.ContextCompat.getColor(
                this, R.color.tp_warn_banner_amber))
            fpvWarningBannerRow.visibility = View.VISIBLE
            return
        }
        warningDismissedSignature = null
        findViewById<View>(R.id.fpvWarningBannerClose).visibility = View.VISIBLE
        // The arrow is the only sign that the banner opens, so it shows only when there is
        // something behind the count.
        val more = d.all.size > 1
        // The banner sits at the bottom-left and opens UPWARD: collapsed, the
        // arrow points up to say "more above"; open, the list stands above the arrow that
        // closes it, worst first from the top.
        fpvWarningBanner.text = when {
            warningExpanded -> d.all.joinToString("\n") + "\n▾"
            more -> "${d.text}  ▴"
            else -> d.text
        }
        // Severity goes on the BACKGROUND and the text stays white.
        fpvWarningBannerRow.background?.setTint(androidx.core.content.ContextCompat.getColor(
            this, if (d.red) R.color.tp_warn_banner_red else R.color.tp_warn_banner_amber))
        fpvWarningBannerRow.visibility = View.VISIBLE
    }

    private fun updateHud() {
        // Local wall clock, 24-hour with seconds. Driven from the HUD tick (500ms), which is
        // twice the rate needed for a seconds display — so it never visibly skips a second.
        // Locale.US pins the digits and separators: some locales substitute their own
        // numerals, and a viewer comparing this against their own clock to measure stream
        // delay needs it to read the same way on every device.
        fpvClock.text = clockFormat.format(java.util.Date())

        // "DEBUG LOG ON" goes through the WARNING BANNER (design decision): the file log
        // costs the screen some jitter, and a pilot who did not turn it on must be made to
        // look and go and turn it off. A quiet HUD line was tried first and was not enough of
        // a stimulus. Read on the tick so a change on the Debug screen shows within half a
        // second of coming back; renderWarning() below draws it.
        FlightWarnings.debugLogOn = AppLog.enabled

        // Obstacle arcs. Fed here, on the 500 ms HUD tick, NOT from the radar callback — the
        // sensor pushes several times a second per face and a full-screen invalidate at that
        // rate is wasted work. The view keeps each face's last real reading, so a slower
        // refresh loses nothing.
        obstacleEdges.update(AutelAvoidance.radar)

        // Aircraft warning banner. Polled on the same tick for the same reason as
        // the arcs: the source repeats at 2Hz, and FlightWarnings owns the edge/hold logic,
        // so this is just "draw what it says".
        renderWarning()

        val hud = TakBridgeHolder.hud()
        val takOk = TakManager.getInstance().isConnected
        val acOk = AutelProductHolder.isConnected

        // Instrument toolbar
        val takColor = if (takOk) androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_state_go) else androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_state_danger)
        toolbarTakIcon.alpha = if (takOk) 1.0f else 0.4f
        // setColorFilter on the SRC drawable, not the background. The dot is an ImageView with
        // android:src and no background at all, so the previous background-tinting version was
        // a no-op in both states and the dot rendered the shape's literal #FFFFFF — a white dot
        // that never went green or red. Matches FieldGuideActivity, which already does it this way.
        toolbarTakDot.setColorFilter(takColor)
        toolbarBattery.setPercent(hud?.batteryPct?.takeIf { hud.hasFix || it > 0 })
        toolbarGps.text = if (hud?.hasFix == true) hud.sats.toString() else "—"
        val signalPct = hud?.uplinkSignalPct
        toolbarSignal.setPercent(signalPct)
        toolbarSignalText.text = if (signalPct != null) "${bucketSignalPct(signalPct)}%" else "—%"
        toolbarSignalText.alpha = if (signalPct != null) 1.0f else 0.4f
        // "Waiting for aircraft…" cover. Gated on the product connection rather than on real
        // decoded frames: AutelCodecView gives no frame callback (the codec listener in
        // AutelVideoStreamer only fires while the RTSP push is running, which is a separate
        // thing a pilot may never turn on). So this can briefly clear a moment before the first
        // frame actually paints — deliberately worded "waiting for aircraft", not "waiting for
        // video", so it does not claim more than it knows.
        findViewById<View>(R.id.flightNoVideoCover).visibility =
            if (acOk) View.GONE else View.VISIBLE

        // Video channel (cert B) health. Only meaningful once configured — an
        // unconfigured split has no video channel to be "down". Fires the amber notice on a
        // CHANGE only (see lastVideoChannelDown's doc), and only while the stream is actually
        // LIVE — a down video channel with nothing streaming has nothing to warn about yet.
        if (TakManager.getInstance().isVideoChannelConfigured()) {
            val videoChannelDown = !TakManager.getInstance().isVideoChannelConnected()
                    && VideoStreamerHolder.state == VideoStreamerHolder.State.LIVE
            if (lastVideoChannelDown != null && videoChannelDown && lastVideoChannelDown == false) {
                showNotice("Video link down — feed not advertised", refused = true)
            }
            lastVideoChannelDown = videoChannelDown
        }
        // Emergency Broadcast countdown — the listener paints on start/stop; this
        // keeps the banner's mm:ss moving while it is active, on the same tick as everything
        // else on this screen. It repaints the whole line, not just the clock: the NO VIDEO
        // STREAM state is decided there, so the notice follows the stream and not the button.
        if (TakManager.getInstance().isEmergencyBroadcastActive()) {
            paintEmergencyBroadcast(true, TakManager.getInstance().emergencyBroadcastExpiresAtEpochMs())
        }

        // Debug-only memory/CPU/GPU/contact overlay — see AppLog.resourceMonitor. Piggybacks on
        // the same slow cadence the old exposure poll used (500ms * 4 = ~2s): frequent enough to
        // actually watch a leak grow, cheap enough that the monitor itself is not a load source.
        //
        // Also written to the debug log (not just the on-screen row) — the whole point of this
        // overlay was chasing leaks that only became clear from a TREND over a flight, and the
        // screen shows only the current instant. Both a media.swcodec leak and a
        // CotParser contact-retention bug were found by pulling numbers OUT of the
        // log after the fact; a resource monitor that never reaches the log can't be replayed
        // post-flight, only watched live. Same tag as the rest of this class (not TAK/radar), so
        // it is unaffected by AppLog.takLogging/radarLogging.
        if (AppLog.resourceMonitor && hudTickCount % 4 == 0) {
            val segments = ResourceMonitor.formattedSegments(this)
            segments.forEachIndexed { i, text -> resourceMonitorCells[i].text = text }
            AppLog.d(TAG, "RES  " + segments.joinToString("  "))
        }

        // REC shows the CAMERA's own reported state (MediaStatus events), not the last button
        // press — so a record that failed to start, or stopped itself (card full/removed),
        // shows truthfully within a tick.
        recordToggle.setRecording(AutelProductHolder.isRecording)
        renderMediaMode()
        // ⚠ THIS NOTICE IS THE ONLY CONFIRMATION A PHOTO GIVES THE PILOT.
        //
        // There is no on-screen shutter pill — this controller has a hardware shutter, and
        // a second way to do it was clutter on the one screen the pilot flies from. The flag
        // behind this notice comes from the camera's own MediaStatus.PHOTO_TAKEN_DONE, thus it
        // fires for the HARDWARE button. Keep it.
        // ⚠ THE FAILURE IS CHECKED FIRST AND CLEARS BOTH FLAGS, AND IT IS THE SECOND GUARD,
        // NOT THE FIRST. The first is in AutelProductHolder: a PHOTO_TAKEN_DONE that names no
        // file does not set photoTakenFlag at all, thus a lost still cannot reach the "Photo
        // Saved" branch below. This order stays because a CONFIRMED capture can still be
        // followed by a real failure, and the pilot must be told the worse of the two.
        //
        // The fault both guards exist to stop, measured: two stills were
        // never written and the pilot was told both were saved.
        if (AutelProductHolder.photoFailedFlag) {
            AutelProductHolder.photoFailedFlag = false
            AutelProductHolder.photoTakenFlag = false
            showNotice("The photo did not save", refused = true)
        } else if (AutelProductHolder.photoTakenFlag) {
            AutelProductHolder.photoTakenFlag = false
            showNotice("Photo Saved")
        }

        // Home point is independent of the CURRENT fix — once set it stays valid even if the
        // live fix drops momentarily, so this is not gated behind hasFix like the map work below.
        val homeSet = hud?.homeSet == true && hud.homeLat.isFinite() && hud.homeLon.isFinite()
        rthButton.setImageResource(if (homeSet) R.drawable.ic_rth_home_set else R.drawable.ic_rth)
        if (homeSet) {
            val hPos = GeoPoint(hud!!.homeLat, hud.homeLon)
            val hm = homeMarker ?: Marker(map).apply {
                title = "Home"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                infoWindow = null
                TakMapMarkers.drawableToBitmap(this@FlightActivity, R.drawable.ic_home_marker, 32)?.let {
                    icon = BitmapDrawable(resources, it)
                }
                map.overlays.add(this)
                homeMarker = this
            }
            hm.position = hPos
        }
        if (homeSet && !lastHomeSet) showNotice("Home Point Set")
        lastHomeSet = homeSet

        refreshStreamToggle()

        // Computed once per tick and shared: the AGL readout and the FAA ceiling check both want
        // height above the ground *under the aircraft*, and must never disagree about it — a
        // readout saying one number while the ceiling warning judges another would be worse than
        // having no correction at all.
        val aglReading = if (hud != null) TerrainAgl.reading(this, hud)
            else TerrainAgl.Reading(0.0, terrainCorrected = false, mslMeters = null)

        updateGimbalPitch(hud)
        updateFaaCeiling(hud, aglReading)
        updateRthAltitude()

        fpvHomeDistance.text = if (hud != null && hud.hasFix && homeSet) {
            val dist = CameraSlantPoint.distanceMeters(hud.homeLat, hud.homeLon, hud.lat, hud.lon)
            val bearing = CameraSlantPoint.initialBearingDeg(hud.homeLat, hud.homeLon, hud.lat, hud.lon)
            "HOME %s  %03.0f°T".format(Units.feet(dist), bearing)
        } else {
            "HOME — ft  —°T"
        }

        updateAntennaAim(hud)

        // Imperial throughout (see Units).
        //
        // ⚠ SPANNED, NOT A PLAIN STRING. THE HEIGHT IS THE ONE NUMBER A
        // PILOT CHECKS CONSTANTLY; set at the same size as the clock and the callsign, the eye
        // would have to read the block to find it. So the height's DIGITS are large and its
        // unit is small, and reference figures are small. The line order serves the same
        // intent; this is the part the order alone cannot do.
        //
        // The unit shrinks rather than the number growing alone, so the block gains only about
        // 0.4 of a line. That matters: see the height budget on flight_map_size in dimens.xml —
        // this column ends in the mini-map, and overflow CLIPS THE MAP SILENTLY.
        fpvOverlayText.text = android.text.SpannableStringBuilder().apply {
            // LINE ORDER IS DELIBERATE, most-glanced-at first:
            //   1 callsign   2 speed   3 AGL   4 MSL
            // Height sits high because it is the number a pilot checks constantly;
            // MSL is a reference figure looked up only when asked for.
            // The clock sits above this block in its own view — see fpvClock.
            // The callsign is its own line under the clock; the speed follows on the next.
            append(TakManager.getInstance().callsign ?: "—")
            append('\n')
            append(if (hud != null) Units.mph(hud.speedMs) else "— mph")
            append('\n')
            // "AGL" only when DTED actually corrected it to height-above-terrain-below;
            // otherwise "ALT", which is what the raw number really is (height above the takeoff
            // point). Labelling an uncorrected figure AGL is exactly the inaccuracy the terrain
            // correction exists to remove, so the label moves with it. MSL is computed
            // separately and can be present while the first still reads ALT. See TerrainAgl.
            val heightStart = length
            val heightText = if (hud != null && hud.hasFix) {
                "%s %s".format(
                    Units.feet(aglReading.meters),
                    if (aglReading.terrainCorrected) "AGL" else "ALT",
                )
            } else {
                "— ft AGL"
            }
            append(heightText)
            // Split at the FIRST space: Units.feet is "<number> ft", so everything before it is
            // the figure and everything after is the unit and the label. Guarded, because a
            // format with no space would otherwise span the whole line at the large size and
            // blow the column's height budget.
            val unitAt = heightText.indexOf(' ')
            if (unitAt > 0) {
                setSpan(android.text.style.RelativeSizeSpan(HEIGHT_FIGURE_SCALE),
                    heightStart, heightStart + unitAt,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.RelativeSizeSpan(HEIGHT_UNIT_SCALE),
                    heightStart + unitAt, length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            // AGL AND MSL GET THEIR OWN LINES, never "AGL · MSL" on one.
            //
            // The HUD readouts sit on a fixed, map-width panel. At that width the pair does not
            // fit, and the wrap falls between a
            // number and its unit — "988 ft" on one line, "MSL" on the next, which reads as a
            // different quantity at a glance. Two lines cannot wrap that way, and stay safe at
            // five-digit altitudes where even a wider panel would break.
            append('\n')
            // MSL RECEDES TO THE REFERENCE SIZE. It is a figure a pilot
            // reads when somebody asks for it, not one they fly by — so it takes the same scale rather than competing with the AGL
            // figure directly above it.
            val mslStart = length
            val msl = aglReading.mslMeters
            append(if (msl != null) "%s MSL".format(Units.feet(msl)) else "— ft MSL")
            setSpan(android.text.style.RelativeSizeSpan(REFERENCE_SCALE),
                mslStart, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            // ⚠ THE AIRCRAFT COORDINATES ARE NOT SHOWN IN THIS BLOCK (design decision). They
            // would be the least-read line in the block and the column needs the
            // room — see the media-mode readout above the palette, and the height budget on
            // flight_map_size, where overflow CLIPS THE MAP SILENTLY.
            //
            // ⚠ WHAT IT COSTS, RECORDED SO IT IS A DECISION AND NOT A DRIFT: the TAK feed is a
            // screen capture, thus on-screen coordinates would be the only way a viewer watching
            // the VIDEO rather than a TAK client could read the aircraft's position. Every TAK
            // client still has the aircraft marker and loses nothing.
            // HOME has its own view in the BOTTOM block,
            // between RTH and the FAA ceiling — it reads as return-to-home
            // information, which is what the rest of that group is.
            // AC and TAK state are not appended here: they would be
            // redundant, as the toolbar already carries both — the TAK badge's green/red dot,
            // and the aircraft through the battery gauge, GPS count and signal bars, which all
            // go blank without one. Leaving them out also lets the FAA ceiling sit directly
            // above the mini-map, which is where a pilot looks for it.
            //
            // There is no "SPI ✓" line either: the pilot toggles SPI deliberately and the button
            // carries its own state, so the line would restate a thing the pilot had just done.
        }

        if (hud == null || !hud.hasFix) return

        // Aircraft marker
        val pos = GeoPoint(hud.lat, hud.lon)
        val mk = aircraftMarker ?: Marker(map).apply {
            title = "Aircraft"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            infoWindow = null
            // Heading arrow, not the app logo: this marker is ROTATED to the aircraft's
            // heading each tick (see mk.rotation below), and a logo cannot show a direction —
            // it just spins. 28dp is the aircraft icon size.
            TakMapMarkers.drawableToBitmap(this@FlightActivity, R.drawable.ic_self_marker, 28)?.let {
                icon = BitmapDrawable(resources, it)
            }
            map.overlays.add(this)
            aircraftMarker = this
        }
        mk.position = pos
        // UNVERIFIED SIGN. osmdroid applies the marker's bearing to the canvas as -mBearing,
        // so this double negation should turn the chevron clockwise with increasing heading —
        // correct for compass heading. Traced in the 6.1.14 bytecode, never watched turning:
        // it needs a live aircraft, which this build has not had.
        //
        // The ADS-B symbols were confirmed correct against live traffic, but that
        // does NOT carry over — those bake rotation in with Canvas.rotate(+course) rather than
        // going through Marker.rotation, so they exercise a different sign convention.
        //
        // To check: point the aircraft north, confirm the chevron points up the screen, then
        // yaw right 90 degrees and confirm it points right. If it turns the wrong way, drop one
        // of the two minus signs (here or in osmdroid's convention) — not both.
        mk.rotation = -hud.headingDeg.toFloat()
        // The locked map's only camera movement: keep the aircraft centred, zoom untouched.
        map.controller.setCenter(pos)

        // Home→aircraft line: the pilot's "which way back" reference on a map that by design
        // cannot be panned around to look. Only meaningful once a home point exists.
        val hl = homeLine
        if (hl != null) {
            if (homeSet) {
                hl.setPoints(listOf(GeoPoint(hud.homeLat, hud.homeLon), pos))
                hl.isVisible = true
            } else {
                hl.isVisible = false
            }
        }
        map.invalidate()
    }

    private fun confirmRth() {
        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("Return to Home")
            .setMessage("Command the aircraft to return to home now?")
            .setPositiveButton("Return to Home") { _, _ ->
                val fc = AutelProductHolder.evo2?.flyController
                if (fc == null) {
                    toast("The aircraft is not connected.")
                } else {
                    fc.goHome(object : com.autel.common.CallbackWithNoParam {
                        override fun onSuccess() {
                            AppLog.i(TAG, "goHome: OK")
                            runOnUiThread { toast("Returning to home") }
                        }
                        override fun onFailure(error: com.autel.common.error.AutelError?) {
                            AppLog.w(TAG, "goHome failed: ${error?.description}")
                            runOnUiThread { toast("RTH failed: ${error?.description ?: "unknown error"}") }
                        }
                    })
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Says plainly that a control is present for layout parity but not yet functional on this
     * airframe. Deliberately a specific sentence per control rather than a generic "coming
     * soon": a pilot mid-flight needs to know immediately whether the thing they just reached
     * for is going to happen, and a vague message invites a second and third press.
     */
    private fun notImplemented(name: String, what: String) {
        toast("$name is not available in this build yet.")
    }

    /**
     * LIVE tapped with no stream running. Checks the stream is configured, then asks for the
     * screen-capture permission; [onActivityResult] starts the foreground service, which starts
     * the push.
     *
     * The configured check happens BEFORE the permission prompt on purpose — making a pilot
     * grant screen capture and only then telling them the video server is not set up would be
     * two wasted taps and a confusing order.
     */
    /**
     * Video quality tier picker — long-press on the LIVE badge.
     *
     * Writes the active slot's profile, as Pre-Flight Setup does, and the plain `video_profile`
     * the stream reads — so the two stay in sync with no second source of truth. Reachable in flight because the right tier depends on the link,
     * which is a thing a pilot learns AFTER taking off, not during setup.
     *
     * **A change while streaming applies immediately** — the pilot is choosing a tier because the
     * link is misbehaving now, so "it'll take effect next time" would be the wrong answer. The
     * encoder is configured once at stream start, so this restarts the push; it does NOT re-prompt
     * for screen capture, because the MediaProjection outlives the stream and
     * [ScreenCaptureService.restart] reuses it. Viewers see a brief reconnect.
     */
    private fun onVideoQualityTapped() {
        AppLog.v(TAG, "long-press: video quality")
        val prefs = getSharedPreferences("takpilot2_tak", MODE_PRIVATE)
        val current = TranscodeProfile.fromPref(prefs.getString("video_profile", null))

        val view = layoutInflater.inflate(R.layout.dialog_video_quality, null)
        val group = view.findViewById<android.widget.RadioGroup>(R.id.videoQualityGroup)

        // Built from the enum, not from XML — same reason as the AR category rows.
        val ids = TranscodeProfile.values().associateWith { profile ->
            val button = layoutInflater.inflate(R.layout.row_video_quality, group, false)
                as android.widget.RadioButton
            button.id = View.generateViewId()
            button.text = profile.label
            group.addView(button)
            button.id
        }
        group.check(ids.getValue(current))
        group.setOnCheckedChangeListener { _, checkedId ->
            val chosen = ids.entries.firstOrNull { it.value == checkedId }?.key ?: return@setOnCheckedChangeListener
            if (chosen == current) return@setOnCheckedChangeListener
            // ⚠ THE SLOT TOO, NOT ONLY THE PLAIN KEY. The mirror rewrites video_profile FROM THE
            // ACTIVE SLOT, so a change written to the plain key alone reverted at the next mirror
            // — and there is one mirror, which every screen and the boot step call.
            // The slot is the source; the plain key is written as
            // well so a restart below reads the new value at once.
            val slot = if (prefs.getInt(VideoServersActivity.KEY_V_ACTIVE_SLOT, 1) == 2) 2 else 1
            prefs.edit()
                .putString(VideoServersActivity.vKey(slot, "profile"), chosen.prefValue)
                .putString("video_profile", chosen.prefValue)
                .apply()
            AppLog.i(TAG, "video quality -> ${chosen.label}")
            if (VideoStreamerHolder.isActive) {
                ScreenCaptureService.restart(applicationContext)
                toast("Video quality: ${chosen.label} — restarting stream")
            } else {
                toast("Video quality: ${chosen.label}")
            }
        }

        val dialog = AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("Video Quality")
            .setView(view)
            .setPositiveButton("Done", null)
            .create()
        wireEmergencyBroadcastRow(view, dialog)
        dialog.show()
    }

    private fun onStartStreamTapped() {
        val p = getSharedPreferences("takpilot2_tak", MODE_PRIVATE)
        if ((p.getString("video_host", "") ?: "").isEmpty() ||
            (p.getString("video_streamid", "") ?: "").isEmpty()) {
            toast("Set up the stream in Pre-Flight Setup first")
            return
        }
        AppLog.i(TAG, "tap: LIVE — requesting screen-capture permission")
        val mpm = getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE)
            as android.media.projection.MediaProjectionManager
        toast("The video stream is starting.")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MEDIA_PROJECTION) return
        if (resultCode == RESULT_OK && data != null) {
            AppLog.i(TAG, "screen-capture permission GRANTED — starting ScreenCaptureService")
            ScreenCaptureService.start(this, resultCode, data)
        } else {
            AppLog.w(TAG, "screen-capture permission DENIED (resultCode=$resultCode)")
            // ⚠ A DENIAL TAKES THE OVERRIDE DOWN WITH IT. Without this the
            // pilot refuses the system dialog and is left with a 15-minute notice promising
            // video on every channel and no stream to put on them.
            if (projectionRequestedForEmergency &&
                TakManager.getInstance().isEmergencyBroadcastActive()
            ) {
                AppLog.w(TAG, "Emergency Broadcast cancelled — screen capture was refused")
                TakManager.getInstance().cancelEmergencyBroadcast()
                showNotice("Screen capture refused — Emergency Broadcast stopped", refused = true)
            } else {
                toast("You did not allow screen capture. The stream did not start.")
            }
        }
        projectionRequestedForEmergency = false
        refreshStreamToggle()
    }

    /**
     * Applies [mapWide] to the map and the button, optionally saving it.
     *
     * The button is labelled with what a tap WOULD DO, not with the state it is in — the
     * standard toggle convention (a media button shows "pause" while playing). At NEAR it
     * reads WIDE, and vice versa.
     *
     * Recentring is left to the HUD tick, which runs every 500ms and calls setCenter; there is
     * no need to re-centre here and doing so would fight it.
     */
    private fun applyMapZoom(persist: Boolean) {
        val zoom = if (mapWide) MAP_ZOOM_WIDE else MAP_ZOOM_NEAR
        map.controller.setZoom(zoom)
        mapZoomButton.text = if (mapWide) "NEAR" else "WIDE"
        if (persist) {
            getSharedPreferences("takpilot2_tak", MODE_PRIVATE)
                .edit().putBoolean(KEY_MAP_WIDE, mapWide).apply()
        }
        AppLog.i(TAG, "mini-map zoom = $zoom (${if (mapWide) "WIDE" else "NEAR"})")
    }

    /**
     * Double-tap the map to make it twice as wide and twice as tall; double-tap again to restore.
     *
     * **It grows OVER the screen, not by pushing things aside.**
     *  - Width is free: [flightHudColumn] is end-aligned, so a wider child simply extends LEFT
     *    across the video.
     *  - Height needs the negative top margin. The column is a vertical LinearLayout, so without
     *    it the map would shove the readouts above it upward — and 2x here (480dp) plus the two
     *    HUD blocks exceeds the column's usable height, so something would be squeezed or
     *    clipped. The negative margin makes it grow upward across the readouts instead, which is
     *    the intended behaviour.
     *  - `elevation` puts it above its siblings in the draw order. `bringToFront()` would ALSO
     *    work visually but reorders the LinearLayout's children, which changes where everything
     *    else sits — exactly the layout damage this is avoiding.
     *
     * The zoom level does not change, so an expanded map shows FOUR TIMES the ground, not a
     * magnified version of the same ground. That is the useful direction: the reason to expand
     * is usually to see more of the picture.
     *
     * Marker sizes are in dp and do not scale, so they get relatively smaller when expanded —
     * which is also the right direction.
     */
    private fun toggleMapSize() {
        mapExpanded = !mapExpanded
        val w = resources.getDimensionPixelSize(R.dimen.flight_map_size)
        val h = resources.getDimensionPixelSize(R.dimen.flight_map_height)
        val lp = mapContainer.layoutParams as android.widget.LinearLayout.LayoutParams
        if (mapExpanded) {
            lp.width = w * 2
            lp.height = h * 2
            lp.topMargin = -h            // absorb the extra height upward, over the readouts
            mapContainer.elevation = 8f * resources.displayMetrics.density
        } else {
            lp.width = w
            lp.height = h
            lp.topMargin = 0
            mapContainer.elevation = 0f
        }
        mapContainer.layoutParams = lp
        // The doubled map earns the full-length scale bar; the small map keeps the short
        // one so the bar never reaches the zoom toggle.
        mapScaleBar?.setMaxLength(if (mapExpanded) SCALE_BAR_CM_FULL else SCALE_BAR_CM_SMALL)
        map.invalidate()
        AppLog.i(TAG, "mini-map ${if (mapExpanded) "EXPANDED to ${w * 2}x${h * 2}px" else "restored"}")
    }

    /** Bucket raw signal % into coarse steps for display: 0-10% shows as
     *  0%, otherwise round to the nearest of 25/50/75/100%. */
    private fun bucketSignalPct(pct: Int): Int {
        if (pct <= 10) return 0
        val buckets = intArrayOf(25, 50, 75, 100)
        return buckets.minByOrNull { kotlin.math.abs(it - pct) } ?: 0
    }

    /**
     * Tap on the signal indicator — reports the uncoarsened figure, or explains its absence.
     *
     * The "close Autel Explorer" case is not a guess. The RC message channel that carries
     * signal strength and controller battery is SINGLE-CLIENT: while Explorer (or its
     * background service) holds it, our `setInfoDataListener` registers cleanly and then never
     * fires — no callback, no error. Aircraft telemetry keeps working throughout, because that
     * travels the RNDIS link rather than the controller's MCU bus, so the failure looks like
     * "only the signal bars are broken".
     *
     * Diagnosed on hardware: with Explorer running, sig=-% indefinitely; force-stop
     * Explorer and the same build reports sig=100% rcBat=78%, matching what Explorer itself had
     * been showing. Worth stating in the toast because the symptom gives a pilot no way to
     * guess the cause.
     */
    private fun signalDetail() {
        val pct = TakBridgeHolder.hud()?.uplinkSignalPct
        AppLog.v(TAG, "tap: signal bars (pct=${pct ?: "-"})")
        toast(
            when {
                pct != null -> "Controller link: $pct%"
                AutelProductHolder.isConnected ->
                    "No link reading. Close Autel Explorer if it is open — only one app at a " +
                        "time can read the controller's signal."
                else -> "No link reading yet — connect the aircraft."
            }
        )
    }

    /**
     * Moves the home point to **where the pilot is standing** — i.e. the CONTROLLER's own GPS
     * fix.
     *
     * **Deliberately NOT `setAircraftLocationAsHomePoint()`**, which the Autel SDK also offers.
     * That sets home to wherever the AIRCRAFT currently is, which is a different feature and
     * the wrong one here: the whole point of this gesture is "I have walked or driven away from
     * the takeoff point, come back to ME." Using the aircraft's position would instead pin home
     * to wherever it happens to be hovering, so a subsequent RTH would land it out there rather
     * than return it to the pilot. (Worth the explicit note so it does not get "simplified"
     * to the aircraft-position call.)
     *
     * Refuses rather than guesses when there's no controller fix: a stale or absent position
     * here is a genuine safety problem, not a cosmetic one.
     */
    private fun confirmResetHome() {
        val fc = AutelProductHolder.evo2?.flyController
        if (fc == null) {
            AppLog.w(TAG, "reset home point ignored — aircraft not connected")
            toast("The aircraft is not connected.")
            return
        }
        if (!hasLocationPermission()) {
            AppLog.i(TAG, "reset home point — location permission not granted, requesting")
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                REQUEST_CODE_LOCATION,
            )
            return
        }
        // The controller's GPS receiver stays OFF until an app asks for updates, so
        // getLastKnownLocation returns null forever on this hardware. Power it on and wait for a
        // fix (see acquireControllerLocation) rather than reading an empty cache.
        showNotice("Getting your location…")
        acquireControllerLocation { loc ->
            if (loc == null) {
                AppLog.w(TAG, "reset home point aborted — no controller GPS fix")
                toast("The controller has no GPS position. Make sure location is on, go " +
                    "outside with a clear view of the sky, and try again.")
                return@acquireControllerLocation
            }
            AppLog.i(TAG, "reset home point: controller fix %.6f, %.6f (age=%ds, acc=%.0fm)"
                .format(loc.latitude, loc.longitude,
                    (System.currentTimeMillis() - loc.time) / 1000, loc.accuracy))

            // Destructive styling and the literal coordinates in the message: this changes where RTH will fly the aircraft, and a stale controller fix
            // is exactly the failure the pilot needs a chance to spot before confirming.
            AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
                .setTitle("Reset Home Point")
                .setMessage("Set the aircraft's home point to your current location " +
                    "(%.6f, %.6f)? This changes where Return to Home will send it."
                        .format(loc.latitude, loc.longitude))
                .setPositiveButton("Set Home Here") { _, _ ->
                    AppLog.i(TAG, "reset home point confirmed — sending setLocationAsHomePoint")
                    fc.setLocationAsHomePoint(
                        loc.latitude, loc.longitude,
                        object : com.autel.common.CallbackWithNoParam {
                            override fun onSuccess() {
                                // ⚠ NOT "UPDATED". The camera is not the only part of this SDK
                                // that reports success for work it did not do, and this is the
                                // control where being wrong flies the aircraft to the wrong
                                // field. The aircraft reports its OWN home point at ~2 Hz; that
                                // is what tells the pilot, a moment later. See
                                // verifyHomePointMoved.
                                AppLog.i(TAG, "setLocationAsHomePoint: accepted — " +
                                    "waiting for the aircraft to report the new home point")
                                runOnUiThread {
                                    verifyHomePointMoved(loc.latitude, loc.longitude)
                                }
                            }
                            override fun onFailure(error: com.autel.common.error.AutelError?) {
                                AppLog.w(TAG, "setLocationAsHomePoint failed: ${error?.description}")
                                runOnUiThread {
                                    toast("Set home failed: ${error?.description ?: "unknown error"}")
                                }
                            }
                        },
                    )
                }
                .setNegativeButton("Cancel") { _, _ ->
                    AppLog.i(TAG, "reset home point cancelled at confirm dialog")
                }
                .show()
        }
    }

    /** The pending home-point check, so a second reset replaces the first. */
    private var homeVerify: Runnable? = null

    /**
     * Waits for the AIRCRAFT to report the home point the pilot asked for, then tells them.
     *
     * ⚠ **THE PILOT IS TOLD BY THE AIRCRAFT, NOT BY THE CALLBACK.** Showing "Home Point Updated"
     * the moment `setLocationAsHomePoint` returns success would be the same class of
     * claim as the camera's `OK` on a lens it never changed (see [verifyLensAgainstVideo]) on a
     * control with a worse consequence: the home point is where Return to Home flies, and a
     * pilot who has been told it moved has no other way to find out that it did not.
     *
     * ⚠ **NOTHING NEW IS ASKED OF THE AIRCRAFT.** `homeEnable` / `homeLatitude` /
     * `homeLongitude` already arrive on the fly-controller telemetry at about 2 Hz, and this
     * screen has been drawing the home marker and the home distance from them all along. This
     * polls the snapshot faster than the push so the confirmation lands on the first report
     * that carries it — no new getter, thus no new SDK subscription hazard.
     *
     * A timeout is NOT a refusal: [HomeCheck.NO_ANSWER] means the aircraft has not said, which
     * is its own state and is reported as its own message. See [homePointVerdict] for the one
     * case this cannot distinguish, and why that case does not matter.
     */
    private fun verifyHomePointMoved(wantLat: Double, wantLon: Double) {
        homeVerify?.let { handler.removeCallbacks(it) }
        val deadline = SystemClock.elapsedRealtime() + HOME_VERIFY_TIMEOUT_MS
        val poll = object : Runnable {
            override fun run() {
                val hud = TakBridgeHolder.hud()
                val separation = if (hud != null && hud.homeLat.isFinite() && hud.homeLon.isFinite())
                    CameraSlantPoint.distanceMeters(hud.homeLat, hud.homeLon, wantLat, wantLon)
                else Double.NaN
                when (homePointVerdict(hud?.homeSet == true, separation)) {
                    HomeCheck.MOVED -> {
                        homeVerify = null
                        AppLog.i(TAG, "home point check: the aircraft reports the new home " +
                            "(%.1f m from the requested position)".format(separation))
                        showNotice("Home Point Updated")
                    }
                    HomeCheck.NOT_MOVED, HomeCheck.NO_ANSWER -> {
                        if (SystemClock.elapsedRealtime() < deadline) {
                            // Still inside the window. The aircraft takes a moment to adopt the
                            // new point and the old one reads as NOT_MOVED until it does, thus
                            // neither answer is final until the time is up.
                            handler.postDelayed(this, HOME_VERIFY_POLL_MS)
                            return
                        }
                        homeVerify = null
                        // The distinction is kept: "it is somewhere else" and "it has not said"
                        // are different facts, and only the first is evidence of a lost write.
                        if (separation.isFinite()) {
                            AppLog.e(TAG, "HOME POINT DID NOT MOVE. setLocationAsHomePoint " +
                                "reported OK and the aircraft still reports a home point " +
                                "%.1f m from the requested position.".format(separation))
                            showNotice("The home point did not move", refused = true)
                        } else {
                            AppLog.w(TAG, "home point check: the aircraft did not report a " +
                                "home point within ${HOME_VERIFY_TIMEOUT_MS}ms")
                            showNotice("The aircraft did not confirm the home point",
                                refused = true)
                        }
                    }
                }
            }
        }
        homeVerify = poll
        handler.post(poll)
    }

    private fun hasLocationPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_LOCATION) return
        if (hasLocationPermission()) {
            AppLog.i(TAG, "location permission granted — re-running reset home point")
            confirmResetHome()
        } else {
            AppLog.i(TAG, "location permission denied — cannot set home to controller position")
            toast("Location permission is needed to set the home point to your position.")
        }
    }

    /**
     * Delivers a fix from the CONTROLLER (the Smart Controller V3 has its own GPS), or null.
     *
     * `getLastKnownLocation()` alone is a trap on this hardware: it reads a cache that nothing
     * fills unless an app has called `requestLocationUpdates()`, so the receiver sits at
     * `mStarted=false` and the cache stays null — permanently, outdoors included (see
     * TakConnectActivity's note). So use a recent cached fix if we have one, otherwise POWER THE
     * RECEIVER ON for a single live update with a timeout. Asynchronous: the result comes back on
     * the main thread via [onResult].
     */
    @android.annotation.SuppressLint("MissingPermission")   // callers gate on hasLocationPermission()
    private fun acquireControllerLocation(onResult: (android.location.Location?) -> Unit) {
        val lm = getSystemService(android.content.Context.LOCATION_SERVICE)
            as android.location.LocationManager
        val cached = latestCachedLocation(lm)
        if (cached != null && System.currentTimeMillis() - cached.time < FRESH_FIX_MS) {
            onResult(cached); return
        }
        val provider = when {
            lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ->
                android.location.LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) ->
                android.location.LocationManager.NETWORK_PROVIDER
            else -> { onResult(cached); return }   // location services off — nothing to power on
        }
        stopHomeLocationUpdates(lm)   // clear any request still pending from a previous tap
        var done = false
        val timeout = Runnable {
            if (done) return@Runnable
            done = true
            stopHomeLocationUpdates(lm)
            onResult(cached)          // best we have when the receiver did not answer in time
        }
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: android.location.Location) {
                if (done) return
                done = true
                handler.removeCallbacks(timeout)
                stopHomeLocationUpdates(lm)
                onResult(location)
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("required by LocationListener on older APIs")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        }
        homeLocListener = listener
        val ok = runCatching {
            lm.requestLocationUpdates(provider, 0L, 0f, listener, android.os.Looper.getMainLooper())
        }.isSuccess
        if (!ok) { stopHomeLocationUpdates(lm); onResult(cached); return }
        handler.postDelayed(timeout, LOCATION_TIMEOUT_MS)
    }

    @android.annotation.SuppressLint("MissingPermission")   // callers gate on hasLocationPermission()
    private fun latestCachedLocation(lm: android.location.LocationManager): android.location.Location? =
        runCatching {
            listOf(
                android.location.LocationManager.GPS_PROVIDER,
                android.location.LocationManager.NETWORK_PROVIDER,
            ).mapNotNull { p -> if (lm.isProviderEnabled(p)) lm.getLastKnownLocation(p) else null }
                .maxByOrNull { it.time }
        }.getOrNull()

    private fun stopHomeLocationUpdates(lm: android.location.LocationManager) {
        homeLocListener?.let { runCatching { lm.removeUpdates(it) } }
        homeLocListener = null
    }

    // ---- AR overlay ----

    /** AR on/off.
     *
     *  ON by default every time the flight screen opens (design decision). The argument for OFF
     *  is that an overlay drawn over the video should be switched on deliberately rather than
     *  inherited; in practice the overlay is wanted on nearly every sortie, and starting each
     *  one by reaching for this button is the greater nuisance.
     *
     *  The accuracy limit is stated in the AR options dialog and the Field Guide. A pilot can
     *  see the overlay without having opened the dialog that states it — that is known and
     *  accepted for trained pilots. */
    private fun onArToggleTapped() {
        if (arOverlay.isRunning) arOverlay.stop() else arOverlay.start()
        AppLog.v(TAG, "tap: AR overlay -> ${if (arOverlay.isRunning) "ON" else "OFF"}")
        refreshArButton()
    }

    /**
     * AR options — what the overlay may draw, and how far out it draws air traffic. One dialog
     * showing every switch at once; everything applies LIVE (the overlay reads ArSettings every
     * frame), which is what makes decluttering a usable in-flight action rather than a setup
     * step.
     */
    private fun onArOptionsTapped() {
        AppLog.v(TAG, "long-press: AR options")
        val view = layoutInflater.inflate(R.layout.dialog_ar_options, null)

        // Rows built from the enum, not written out in XML — a category added later cannot be
        // silently missing from the menu that controls it.
        val container = view.findViewById<android.widget.LinearLayout>(R.id.arCategoryContainer)
        for (category in ArSettings.Category.values()) {
            val row = layoutInflater.inflate(R.layout.row_ar_category, container, false)
                as android.widget.CheckBox
            row.text = category.label
            row.isChecked = ArSettings.isEnabled(this, category)
            row.setOnCheckedChangeListener { _, isChecked ->
                ArSettings.setEnabled(this, category, isChecked)
            }
            container.addView(row)
        }

        val group = view.findViewById<android.widget.RadioGroup>(R.id.arRangeGroup)
        val rangeIds = mapOf(
            ArSettings.AirRange.MI_2_5 to R.id.arRange25,
            ArSettings.AirRange.MI_5 to R.id.arRange5,
            ArSettings.AirRange.MI_15 to R.id.arRange15,
        )
        group.check(rangeIds.getValue(ArSettings.airRange(this)))
        group.setOnCheckedChangeListener { _, checkedId ->
            rangeIds.entries.firstOrNull { it.value == checkedId }?.let {
                ArSettings.setAirRange(this, it.key)
            }
        }

        val dialog = AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("AR Overlay")
            .setView(view)
            .setPositiveButton("Done", null)
            .setNeutralButton("Calibrate FOV…") { _, _ -> onArCalibrateTapped() }
            .setNegativeButton("Aim Offsets…") { _, _ -> onAimOffsetsTapped() }
            .show()

        // Straight to the AR entry, not the top of the guide — the pilot asked this question
        // from the AR menu and should not have to scroll a five-section document to find the
        // answer. Dismiss first so the dialog is not left behind the guide.
        view.findViewById<TextView>(R.id.arFieldGuideLink).setOnClickListener {
            dialog.dismiss()
            AppLog.v(TAG, "AR menu: opening field guide at the AR section")
            startActivity(FieldGuideActivity.intent(this, FieldGuideActivity.ANCHOR_AR))
        }
    }

    /**
     * SPI AIM CALIBRATION — corrects the bias between where the gimbal reports it is looking
     * and where the lens actually looks (mount tolerance, gimbal wear, a hard landing).
     *
     * NOT the same thing as FOV calibration, and one will not fix the other: an FOV error is
     * invisible at the frame centre and grows toward the edges, while an aim offset moves the
     * CENTRE — which is precisely what a marker drop uses.
     *
     * Why this exists as a pilot-facing control rather than a constant: as a compile-time
     * `const val` at 0.0, tuning it costs a rebuild and in practice nobody does. An unmeasured
     * offset is the most likely cause of poor marker accuracy at shallow look angles. It is an
     * airframe property, not a software constant — re-check it after
     * a gimbal strike, a repair, or swapping aircraft.
     *
     * Applied live on every tap so the pilot can hold the crosshair on a known object, watch
     * the SPI/AR marker walk onto it, and stop when it sits right. Steps are deliberately fine
     * (0.25°): at 200ft AGL a QUARTER of a degree is ~1ft of ground error at 54° down but ~80ft
     * at 6°, so a coarse step would be unusable for the shallow angles that actually need this.
     *
     * CALIBRATE SHALLOW. A bias hides at steep angles — if it looks perfect at 50° that proves
     * almost nothing. 25° down is the recommended angle (found by calibrating
     * in flight): shallow enough that a real offset shows, and clear of the red-reticle gate
     * that blocks drops — with no DTED loaded the reticle turns red below 15°, which would
     * stop the pilot part-way through the procedure.
     */
    private fun onAimOffsetsTapped() {
        AppLog.v(TAG, "aim calibration opened")
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        var pitch = TakBridgeHolder.currentPitchOffset
        var bearing = TakBridgeHolder.currentBearingOffset

        val hint = TextView(this).apply {
            textSize = 13f
            setTextColor(androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))
        }
        fun refreshHint() {
            // Both directions spelled out for BOTH rows. A pilot mid-calibration should not have to infer that "−" is the
            // opposite of the one direction the hint happened to name.
            hint.text = "Pitch +  sends the marker FARTHER from the aircraft, −  brings it " +
                "closer.\nBearing +  swings it clockwise, −  swings it counter-clockwise.\n\n" +
                "Aim at a known object with the gimbal 25° DOWN — a bias is nearly " +
                "invisible looking straight down.\n\nFastest with a second TAK device: watch the " +
                "camera point (name ends \"-SPI\") slide onto the target as you adjust.\n\n" +
                "Default is 0.00° / 0.00° (uncalibrated)."
        }
        refreshHint()

        // Built in code rather than XML: two near-identical stepper rows, and a layout file
        // would need its own ids for each without buying any clarity.
        fun stepperRow(label: String, get: () -> Double, set: (Double) -> Unit): android.view.View {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, pad / 2, 0, pad / 2)
            }
            val name = TextView(this).apply {
                text = label; textSize = 16f
                setTextColor(android.graphics.Color.WHITE)
                layoutParams = android.widget.LinearLayout.LayoutParams(0,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val value = TextView(this).apply {
                textSize = 18f; minWidth = (90 * resources.displayMetrics.density).toInt()
                gravity = android.view.Gravity.CENTER
                setTextColor(androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_accent))
            }
            fun show() { value.text = "%+.2f°".format(get()) }
            show()
            fun button(text: String, delta: Double) = android.widget.Button(this).apply {
                this.text = text
                setOnClickListener {
                    set(get() + delta)
                    ArSettings.saveAimOffsets(this@FlightActivity, pitch, bearing)
                    // Read back: the holder clamps, so the display must show what was ACCEPTED,
                    // not what was asked for — otherwise the pilot keeps tapping past the limit.
                    pitch = TakBridgeHolder.currentPitchOffset
                    bearing = TakBridgeHolder.currentBearingOffset
                    show()
                }
            }
            row.addView(name)
            row.addView(button("−", -0.25))
            row.addView(value)
            row.addView(button("+", 0.25))
            return row
        }

        root.addView(stepperRow("Pitch offset", { pitch }, { pitch = it }))
        root.addView(stepperRow("Bearing offset", { bearing }, { bearing = it }))
        root.addView(hint)

        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle(if (TakBridgeHolder.aimIsThermal) "Aim Calibration — Thermal" else "Aim Calibration")
            .setView(root)
            .setPositiveButton("Done", null)
            .setNeutralButton("Reset to 0") { _, _ ->
                ArSettings.resetAimOffsets(this)
                toast("Aim calibration reset")
            }
            .show()
    }

    /**
     * AR field-of-view calibration. The base FOV is published-spec, not measured, and the
     * projection is most sensitive to it at the FRAME EDGES — so the pilot puts a marker on a
     * known object near the edge and adjusts until the icon sits on it, watching it converge
     * live. Adjusts the 1x base; the zoom correction rides on top, so calibrating at 1x fixes
     * every zoom level at once.
     */
    private fun onArCalibrateTapped() {
        AppLog.v(TAG, "AR FOV calibration opened")
        val view = layoutInflater.inflate(R.layout.dialog_ar_fov, null)
        val hValue = view.findViewById<TextView>(R.id.arFovHValue)
        val vValue = view.findViewById<TextView>(R.id.arFovVValue)
        val hint = view.findViewById<TextView>(R.id.arFovHint)

        // One knob. The vertical is derived from this and the live video aspect, and is shown
        // read-only so the pilot can see it track — see ArSettings.loadFov for why it stopped
        // being independently adjustable.
        // Edits the PILOT's value, not the camera's. When the camera is reporting its own FOV
        // that value wins, and the steppers here only change the fallback — the hint says so
        // rather than letting the taps look inert.
        var h = TakBridgeHolder.calibratedHFovBase

        fun apply() {
            ArSettings.saveFov(this, h)
            h = TakBridgeHolder.calibratedHFovBase
            hValue.text = "%.1f°".format(h)
            vValue.text = "%.1f°".format(TakBridgeHolder.vFovFor(h))
            hint.text = when {
                TakBridgeHolder.hasLiveCameraFov ->
                    "The picture is %.1f° × %.1f° — that is being used. This setting is the fallback."
                        .format(TakBridgeHolder.pictureHFovBase, TakBridgeHolder.currentVFovBase)
                TakBridgeHolder.currentZoomFactor > 1.0 ->
                    "Effective at %.1fx zoom: %.1f° × %.1f°".format(
                        TakBridgeHolder.currentZoomFactor,
                        AutelTakBridge.hFovDeg(TakBridgeHolder.currentZoomFactor),
                        AutelTakBridge.vFovDeg(TakBridgeHolder.currentZoomFactor),
                    )
                else -> "Marker too far OUT from centre → reduce. Too far IN → increase."
            }
        }
        apply()

        view.findViewById<android.widget.Button>(R.id.arFovHMinus).setOnClickListener { h -= FOV_STEP_DEG; apply() }
        view.findViewById<android.widget.Button>(R.id.arFovHPlus).setOnClickListener { h += FOV_STEP_DEG; apply() }

        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("Calibrate AR field of view")
            .setView(view)
            .setPositiveButton("Done", null)
            .setNeutralButton("Reset") { _, _ ->
                ArSettings.resetFov(this)
                toast("FOV reset to published specs")
            }
            .show()
    }

    /** AR pill on/off state: green pill when running (the colour this app already means
     *  "on/good" with), plain dimmed pill when off. Size unchanged between states — a control
     *  that grows on tap reads as a different control. */
    private fun refreshArButton() {
        val on = arOverlay.isRunning
        arButton.alpha = if (on) 1f else 0.45f
        arButton.setBackgroundResource(
            if (on) R.drawable.bg_pill_active else R.drawable.bg_zoom_pill
        )
        arButton.setTextColor(
            if (on) androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_state_go) else Color.WHITE
        )
    }

    /**
     * The quick marker — place it, or move it if it already exists. ONE action, SHORT press only.
     *
     * THE SPLIT THAT WAS REMOVED. Short-press-places / long-press-re-aims was tried and removed
     * after flying it. [TakDropMarkers.QUICK_NAME] is a SINGLETON,
     * thus "place" and "move" are the same intent — "the marker belongs where I look" — and the
     * only result of that split was a scolding toast when the pilot used the wrong gesture on a
     * marker that already existed.
     *
     * THE SPLIT USED NOW IS NOT THE SAME SPLIT, and this note is here so that nobody
     * removes it for the old reason. The old split gave ONE marker two verbs. This
     * one gives two different KINDS of marker one verb each:
     *   SHORT — this function. The one quick marker, re-aimed at whatever the camera looks at.
     *   LONG  — [onUnknownMarkerAction]. A NEW stationary Unknown marker, numbered, that stays.
     * The old wrong-gesture scolding cannot come back, because neither gesture refuses. They give
     * different results, and both are useful.
     *
     * Both controls reach both functions: the crosshair and the controller's C2 button each send
     * their short press here and their long press there. Same convention as the C1 button and the
     * zoom pill — one shared function for each action, never one copy for the touch route and
     * another for the hardware route.
     *
     * @param source only for the log, so a press can be traced back to the control that sent it.
     */
    private fun onQuickMarkerAction(source: String) {
        AppLog.v(TAG, "$source: quick marker")
        if (aimTooPoorToDrop()) { refuseDropForAim(); return }
        val look = TakBridgeHolder.lookPoint()
        if (look == null) {
            AppLog.w(TAG, "quick marker refused — no look point (GPS/gimbal not ready)")
            toast("Cannot place the marker yet. Wait for GPS and the gimbal.")
            return
        }
        val (lat, lon, elev) = look
        // Move first: moveQuick keeps the uid, so the marker slides in place on every other TAK
        // client rather than the team seeing a delete and a new contact.
        if (TakDropMarkers.moveQuick(lat, lon, elev)) {
            showNotice("${TakDropMarkers.QUICK_NAME} re-aimed")
        } else if (TakDropMarkers.placeQuick(lat, lon, elev)) {
            showNotice("${TakDropMarkers.QUICK_NAME} dropped")
        }
    }

    /**
     * Drop a NEW stationary marker of the type Unknown at the look point, and send it immediately.
     *
     * This is a SHORTCUT for the marker button plus "Unknown" in the type list, and nothing more.
     * The same name from the same shared counter ("<callsign>-NN"), the same entry in the markers
     * list, the same 60-second re-broadcast that catches a teammate whose ATAK connects late. It
     * is NOT the quick marker: it stays where it is put, and a second long press makes a second
     * marker.
     *
     * It sends with no Send / Do not Send question (design decision). This gesture is for the
     * moment when the pilot cannot give a dialog any attention. The marker button keeps the
     * question, because there the pilot has already stopped to choose.
     *
     * The same gate as each other drop route: an aim that the marker button refuses must be
     * refused here too, or the controller button becomes a quiet way around it.
     *
     * @param source only for the log, so a press can be traced back to the control that sent it.
     */
    private fun onUnknownMarkerAction(source: String) {
        AppLog.v(TAG, "$source: Unknown marker")
        if (aimTooPoorToDrop()) { refuseDropForAim(); return }
        // Read the look-point HERE. The aircraft keeps flying, thus a position captured when the
        // press started is already old.
        val look = TakBridgeHolder.lookPoint()
        if (look == null) {
            AppLog.w(TAG, "Unknown marker refused — no look point (GPS/gimbal not ready)")
            toast("Cannot place the marker yet. Wait for GPS and the gimbal.")
            return
        }
        val (lat, lon, elev) = look
        val name = TakDropMarkers.placeAt(
            TakDropMarkers.Affiliation.UNKNOWN, lat, lon, elev, autoSend = true)
        // The notice reports the DROP and the toast from sendPin reports the SEND. Both are shown
        // on purpose: the send can fail on its own ("not connected to TAK"), and one message for
        // the two facts would hide the second.
        showNotice("$name dropped")
    }

    /**
     * Video re-sync — rebuilds the on-screen decode from scratch. The SDK offers no
     * request-keyframe hook (the DJI SDK's requestResync asks the encoder for an IDR); the
     * contained equivalent here is tearing the codec view down and constructing a fresh one,
     * which restarts the decode and picks up clean at the next keyframe. Expect a brief
     * black gap. Only affects the local picture — the
     * RTSP push reads its own frame tap and is untouched.
     */
    /**
     * Makes the FPV video fill the screen in the VISIBLE-LIGHT modes, aspect preserved, centred.
     * Thermal is the exception and is shown whole — see the scale decision in [applyVideoFill].
     *
     * THE PROBLEM THIS SOLVES. The camera emits a different shape per mode — photo 1280x960
     * (4:3), video 1280x720 (16:9), IR 640x512 (5:4) — and the SDK fits each one INSIDE the
     * widget, so the picture changed shape and grew black bars whenever the pilot took a photo,
     * started recording or switched to thermal. Those bars are not cosmetic: the TAK feed is a
     * screen capture, so the whole team saw them too. A pilot should not have to track aspect
     * ratios; the picture should just fill the screen and stay put.
     *
     * HOW. AutelCodecView is a TextureView underneath, so setTransform() can scale what the SDK
     * already rendered — no SDK internals touched, no second surface, no re-encode. The SDK fits
     * content CENTRED (verified from its own renderPos trace: 16:9 -> x:0 y:120, 4:3 -> x:96 y:0
     * — symmetric bars either way), so a centre-anchored uniform scale crops the bars off exactly
     * and leaves the image's true centre dead on the view's centre.
     *
     * The scale is just the ratio of the two aspects, whichever way round they sit:
     *
     *     content wider than view  -> letterboxed -> scale = contentAspect / viewAspect
     *     content taller than view -> pillarboxed -> scale = viewAspect / contentAspect
     *
     * which collapses to max(a/b, b/a). At most one of those is > 1, and the other case is the
     * reciprocal, so the same expression covers all three camera modes and anything Autel adds.
     *
     * WHY THE CENTRE MATTERS MORE THAN THE EDGES. [CrosshairView] is match_parent over the same
     * rect, so the reticle sits at the view centre. Because the scale is anchored at that exact
     * point, the optical centre stays under the reticle in every mode — which is what the
     * crosshair PROMISES, since it is the aiming reference for marker drops. Losing the edges to
     * the crop is an accepted trade; losing the centre would silently corrupt
     * every marker placed through it.
     *
     * Re-applied on every frame-size change AND every layout change, because either can happen
     * without the other: the camera switches mode without the view resizing, and the map toggle
     * resizes the view without the camera changing.
     */
    private fun armVideoFill(view: AutelCodecView) {
        AutelCodecView.setOnRenderFrameInfoListener(object : com.autel.common.video.OnRenderFrameInfoListener {
            override fun onRenderFrameSizeChanged(w: Int, h: Int) {
                // Source or already-fitted dimensions — either is fine, we only use the RATIO,
                // and the SDK's fit preserves it.
                if (w <= 0 || h <= 0) return
                val aspect = w.toFloat() / h
                if (kotlin.math.abs(aspect - videoAspect) < 0.001f) return   // no real change
                videoAspect = aspect
                AppLog.i(TAG, "video frame size ${w}x$h (aspect ${"%.3f".format(aspect)}) — refilling")
                // The FOV model needs the frame's shape too: the vertical FOV is derived from the
                // horizontal and THIS aspect, so a camera mode change (16:9 video -> 4:3 photo ->
                // 5:4 IR) re-derives it instead of needing a per-mode recalibration.
                TakBridgeHolder.setVideoAspect(aspect.toDouble())
                runOnUiThread {
                    // The first frame of a pending view's shape commits it.
                    pendingView?.let { pv ->
                        if (lensAgreesWithFrame(believeIr = pv.expectsThermalFrame, frameAspect = aspect)
                            == Verdict.AGREES) commitCameraView(pv)
                    }
                    applyVideoFill(view)
                }
            }
            /**
             * ⚠ **THIS IS THE ONE SIGNAL A STUTTER IS MADE OF.** Without it, three reported
             * stutters left nothing in the log: no error, no reconnect, no stalled tick, flat
             * CPU and GPU, and sig=100% either side of all three. A stutter is frames not
             * arriving on time, and this callback says when every frame arrived.
             *
             * Runs on the SDK's RENDER THREAD, once per frame, on the pipeline that must never
             * regress — so the work is a subtraction and two comparisons, and [VideoFrameMonitor]
             * allocates only when it has something to say. See that class for what is measured.
             */
            override fun onRenderFrameTimestamp(ts: Long) {
                PictureClock.onFrame()   // the picture's age, for AR and drops
                when (val r = frameMonitor.onFrame(SystemClock.elapsedRealtime(), ts)) {
                    is VideoFrameMonitor.Report.Stall -> AppLog.w(TAG,
                        "video STALL: the picture froze for ${r.gapMs}ms " +
                            "(sdk ts step ${r.ptsDelta}" +
                            (if (r.sinceLastStallMs >= 0) ", ${r.sinceLastStallMs}ms since the last" else ", first") +
                            ")")
                    is VideoFrameMonitor.Report.Summary -> AppLog.i(TAG,
                        "video ok: %d frames in %dms (%.1f fps), worst gap %dms"
                            .format(r.frames, r.periodMs, r.fps, r.worstGapMs))
                    null -> Unit
                }
            }
        })
        view.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) applyVideoFill(view)
        }
    }

    /** Centre-anchored scale that turns the SDK's fit-inside into a fill. See [armVideoFill]. */
    private fun applyVideoFill(view: AutelCodecView) {
        val vw = view.width.toFloat()
        val vh = view.height.toFloat()
        // Before first layout, or before any frame has arrived, leave the view alone — an
        // identity transform is the SDK's own behaviour and a safe resting state.
        if (vw <= 0f || vh <= 0f || videoAspect <= 0f) return
        val viewAspect = vw / vh
        // FILL for the visible-light modes, FIT for thermal (design decision).
        //
        // The thermal sensor is 640x512 and there is no more of it to be had. Filling a
        // 2048x1536 view with it means a 1.067 magnification and, measured on the controller,
        // an AR rect of 2048x1638 — thus 102 rows of a 512-row sensor never reach the screen.
        // Giving up about 6% of a scarce sensor to remove a black bar is the wrong trade, so
        // thermal is shown whole and the view is filled at the sides with the flight screen's
        // own black.
        //
        // The visible-light modes are NOT changed. Their crop is far larger — 16:9 in a 4:3
        // view magnifies by 1.333 and cuts 25% off the sides — but that camera has resolution
        // to spare, the picture fills the screen, and the TAK feed is a capture of this screen,
        // thus a change here changes what the whole team sees. That is a separate decision and
        // it has not been made.
        //
        // A scale of exactly 1 is the SDK's own behaviour: it already fits content INSIDE the
        // widget and centres it, so the identity transform IS the fit, and the bars are the
        // root layout's tp_bg_flight (#000000) showing through the TextureView.
        // PIP is a 1280x720 composite from the camera (measured), thus it fills
        // like the visible modes; only the bare 640x512 sensor is shown whole.
        val scale = if (cameraView.fitsWhole) 1f
            else maxOf(videoAspect / viewAspect, viewAspect / videoAspect)
        view.setTransform(android.graphics.Matrix().apply {
            setScale(scale, scale, vw / 2f, vh / 2f)   // anchor = view centre = reticle centre
        })
        view.invalidate()

        // TELL THE AR OVERLAY WHERE THE FULL VIDEO FRAME NOW LIVES.
        //
        // Without this, ArOverlayView projects angles onto its default videoRect,
        // the whole view — correct only while the video fills the view 1:1.
        // The centre-crop above MAGNIFIES the image by `scale`, so every marker would be drawn too
        // close to the centre, with the error growing linearly outward. It looks fine near the
        // crosshair and badly wrong at the edges, which shows up as soon as the gimbal
        // is pitched up and contacts sit near the frame edge.
        //
        // The rect handed over is where the WHOLE video frame would be if nothing were cropped:
        // fit-inside dimensions times the same scale, centred. Its overflow beyond the view is
        // the point — a target that has been cropped off screen then projects outside the view
        // bounds and is correctly treated as off-frame, instead of being squeezed back inside.
        val fitW: Float
        val fitH: Float
        if (videoAspect > viewAspect) { fitW = vw; fitH = vw / videoAspect }
        else { fitH = vh; fitW = vh * videoAspect }
        val fullW = fitW * scale
        val fullH = fitH * scale
        val rect = android.graphics.RectF(
            vw / 2f - fullW / 2f, vh / 2f - fullH / 2f,
            vw / 2f + fullW / 2f, vh / 2f + fullH / 2f)
        arOverlay.setVideoRect(rect)
        lastVideoRect = rect
        AppLog.i(TAG, "AR video rect: ${fullW.toInt()}x${fullH.toInt()} in view ${vw.toInt()}x${vh.toInt()}")
        AppLog.i(TAG, "video ${if (cameraView.fitsWhole) "FIT (thermal)" else "FILL"} ($cameraView): " +
            "view ${vw.toInt()}x${vh.toInt()} (aspect ${"%.3f".format(viewAspect)}) " +
            "content aspect ${"%.3f".format(videoAspect)} -> scale ${"%.3f".format(scale)}")
    }

    private fun resyncVideo() {
        val container = findViewById<FrameLayout>(R.id.videoContainer)
        codecView?.let { container.removeView(it) }
        runCatching { AutelCodecView.stopCodec() }
            .onFailure { AppLog.w(TAG, "stopCodec during resync: ${it.message}") }
        // The gap across a rebuild is OURS, not a fault — see VideoFrameMonitor.reset.
        frameMonitor.reset()
        codecView = AutelCodecView(this).also { container.addView(it); armVideoFill(it) }
        AppLog.i(TAG, "video resync: codec view rebuilt")
    }

    /**
     * Switches the FPV between the visible camera and the 640T's thermal sensor.
     *
     * A tap TOGGLES visible and PIP, on the pill and on the C1 key alike, and from full
     * thermal goes back to PIP (design decision). Two other shapes were tried and
     * rejected: a long-press menu, then a three-step cycle, "a chore".
     * Full thermal is reached from the ⤢ on the window's corner, the phone idiom —
     * see [renderPipSizeButton]. [selectCameraView] owns the write, the belief and the verify.
     *
     * ⚠ **THE CAMERA WILL NOT CHANGE LENS WHILE IT IS RECORDING, AND IT SAYS `OK` ANYWAY**
     * (measured in flight). A success callback is not evidence: the assumption "if the camera
     * rejects the change the buttons keep showing what the
     * camera is actually doing" is WRONG — the camera does not reject it. It accepts the call,
     * returns success, and does nothing.
     *
     * The proof is the app's own frame measurements. Mid-recording, eight VISIBLE/IR toggles
     * over 22 seconds all logged `setDisplayMode: OK` and the incoming video never once changed
     * shape — it stayed 1920x1080. Five seconds after RECORD_STOP the same button gave
     * `video frame size 640x512` 13 ms later, which is the thermal sensor's native size.
     *
     * Believing the OK makes this application letterbox its own VISIBLE picture by applying
     * the thermal FIT rule. Worse, and the real reason this is
     * refused rather than merely logged: it tells [TakBridgeHolder] the active lens is IR, so
     * **the camera point goes to the whole TAK team tagged as thermal, with the thermal field
     * of view**, while the aircraft streams visible light. That is bad geometry on everyone
     * else's map.
     *
     * A read-back is NOT the fix here: `getDisplayMode` and `getIrColor` were both measured
     * timing out 350 ms after RECORD_START, so the channel that would answer the question is
     * itself unhealthy while recording. Refusing the request is honest, immediate and needs
     * nothing from the camera.
     *
     * Otherwise, state flips only in the success callback.
     */
    private fun onIrTapped() {
        selectCameraView(cameraView.next)
    }

    /**
     * Writes a camera view, then believes it only until the picture says otherwise.
     *
     * ⚠ The recording refusal covers PIP as well as the lens: `setDisplayMode` is one call,
     * and the camera's own API answers it with status -1 while recording (measured),
     * where the SDK path reports OK. Either way nothing changes, so the request is refused
     * here with the amber notice and nothing is written.
     */
    private fun selectCameraView(target: CameraView) {
        val cam = AutelProductHolder.xt706
        if (cam == null) {
            AppLog.w(TAG, "camera view ignored — camera not connected (or not an XT70x)")
            toast("The camera is not connected.")
            return
        }
        if (target == cameraView) return
        if (AutelProductHolder.isRecording) {
            // Refused, not silently dropped: showNotice's refused flag is the amber transient
            // on the flight screen, which IS in the screen capture the team sees.
            AppLog.w(TAG, "camera view $target refused — the camera is recording and will " +
                "ignore a display-mode change")
            showNotice("Stop the recording to change the camera view", refused = true)
            return
        }
        val mode = DisplayMode.valueOf(target.displayModeName)
        cam.setDisplayMode(mode, camCb("setDisplayMode($mode)") {
            // ⚠ The OK is a REQUEST, not the picture. The old lens's frames
            // go on arriving for 100-470 ms, and committing the new FOV and fill rule now would
            // draw every marker 2.2x (or 0.45x) its true offset over them. When the frame SHAPE will
            // change (visible/PIP 16:9 <-> thermal 5:4), wait for the first frame of the new
            // shape; when it will not, nothing on screen can tell the two apart, so commit now.
            if (lensAgreesWithFrame(believeIr = target.expectsThermalFrame, frameAspect = videoAspect)
                == Verdict.AGREES) {
                commitCameraView(target)
            } else {
                pendingView = target
                pendingViewTimeout?.let { handler.removeCallbacks(it) }
                pendingViewTimeout = Runnable {
                    pendingView?.let { pv ->
                        AppLog.w(TAG, "no $pv frame within ${LENS_COMMIT_TIMEOUT_MS}ms — committing it anyway")
                        commitCameraView(pv)
                    }
                }.also { handler.postDelayed(it, LENS_COMMIT_TIMEOUT_MS) }
            }
        })
    }

    /** A requested view waiting for its first frame of the right shape. Main thread. */
    private var pendingView: CameraView? = null
    private var pendingViewTimeout: Runnable? = null

    private fun commitCameraView(target: CameraView) {
        pendingViewTimeout?.let { handler.removeCallbacks(it) }
        pendingViewTimeout = null
        pendingView = null
        cameraView = target
        verifyLensAgainstVideo(expectThermalFrame = target.expectsThermalFrame)
        refreshIrButtons()
        AppLog.i(TAG, "camera view now $target")
    }

    /** True between the hardware record button's press and the start this makes on the pilot's
     *  behalf. It stops a second event in that window queueing a second start — see the
     *  START_VIDEO branch of the button listener. */
    @Volatile private var hardwareRecordPending = false

    /** The camera mode the pilot has already been told about, so a CHANGE can be announced and
     *  a steady state cannot. Null until the camera first answers — see [renderMediaMode]. */
    private var announcedMediaMode: MediaMode? = null

    /** The pending lens check, so a second toggle replaces the first rather than racing it. */
    private var lensVerify: Runnable? = null

    /**
     * Checks the camera's `OK` against the PICTURE, a moment later.
     *
     * ⚠ **THE SUCCESS CALLBACK IS NOT EVIDENCE.** The camera accepts a lens
     * change it will not perform and returns success. The refusal in [onIrTapped] closes the one
     * trigger that is measured (recording), and this closes the rest: whatever the reason, a
     * lens that did not change is caught by the shape of the frame. See [lensAgreesWithFrame]
     * for what the shapes are and why the answer has three values.
     *
     * ⚠ **THE PICTURE WINS, AND THAT IS THE POINT OF THE WHOLE THING.** On a contradiction this
     * corrects [cameraView], which is not cosmetic: that setter tells [TakBridgeHolder] which lens is
     * live, and a wrong answer there sends the camera point to the entire TAK team tagged
     * thermal, with the thermal field of view, over visible video. It also restores the right
     * FILL rule, so the pilot stops seeing their own visible picture letterboxed by the thermal
     * rule. Correcting the belief is what un-does both.
     *
     * ⚠ **NO READ-BACK IS USED HERE, DELIBERATELY.** `getDisplayMode` was measured timing out
     * 350 ms after RECORD_START, so the channel that would answer is itself unhealthy exactly
     * when the question matters. The frame size costs nothing and arrives anyway —
     * [armVideoFill] is already listening to it for the FOV model.
     */
    private fun verifyLensAgainstVideo(expectThermalFrame: Boolean) {
        lensVerify?.let { handler.removeCallbacks(it) }
        val check = Runnable {
            lensVerify = null
            // Gone in the meantime — a disconnect stops the frames, thus the last aspect
            // describes a picture that is no longer arriving and proves nothing.
            if (AutelProductHolder.xt706 == null) return@Runnable
            val aspect = videoAspect
            val expected = if (expectThermalFrame) "thermal 5:4" else "video 16:9"
            when (lensAgreesWithFrame(believeIr = expectThermalFrame, frameAspect = aspect)) {
                Verdict.AGREES ->
                    AppLog.i(TAG, "lens check: the picture agrees " +
                        "($cameraView, $expected, aspect ${"%.3f".format(aspect)})")
                Verdict.INCONCLUSIVE ->
                    AppLog.i(TAG, "lens check: no answer from the picture " +
                        "(aspect ${"%.3f".format(aspect)}) — neither lens shape")
                Verdict.CONTRADICTS -> {
                    AppLog.e(TAG, "VIEW DID NOT CHANGE. Asked for $cameraView ($expected), " +
                        "the camera reported OK, and the picture is still the other shape " +
                        "(aspect ${"%.3f".format(aspect)}). Correcting the published lens.")
                    // The belief goes back to what the picture shows. This repairs the wire as
                    // well as the screen — see the note on this method. A thermal frame can
                    // only be the bare sensor; a video frame is read as the plain camera, the
                    // safe direction (visible cone, FILL) if it is really PIP.
                    cameraView = if (expectThermalFrame) CameraView.VISIBLE else CameraView.IR
                    refreshIrButtons()
                    showNotice("The camera did not change the view", refused = true)
                }
            }
        }
        lensVerify = check
        handler.postDelayed(check, LENS_VERIFY_SETTLE_MS)
    }

    /** White hot → black hot → ironbow, then round again. Only reachable while [CameraView.hasThermal] — the
     *  button is hidden otherwise. A palette outside the cycle (Explorer's leftovers) enters
     *  at WHITE HOT: indexOf returns -1, and -1 + 1 is index 0. */
    private fun onIrPaletteTapped() {
        val cam = AutelProductHolder.xt706 ?: return
        // Same refusal as the lens, same reason — see onIrTapped. This is the SAME camera
        // channel: getIrColor was measured timing out alongside getDisplayMode 350 ms after
        // RECORD_START. A palette that silently does not change is a smaller lie than a lens
        // that does not change, but it is the same lie.
        if (AutelProductHolder.isRecording) {
            AppLog.w(TAG, "IR palette refused — the camera is recording")
            showNotice("Stop the recording to change the palette", refused = true)
            return
        }
        val target = irPaletteCycle[(irPaletteCycle.indexOf(irPalette) + 1) % irPaletteCycle.size]
        cam.setIrColor(target, camCb("setIrColor($target)") {
            irPalette = target
            refreshIrButtons()
            AppLog.i(TAG, "IR palette now $target")
        })
    }

    /**
     * The thermal expand / contract PILL (design decision): a pill in the actions column
     * directly under the PIP pill, shown only while thermal is on screen. In PIP its icon
     * makes thermal full screen; in full thermal it puts the window back. Gone in visible and
     * the column closes up. Five placements ON the picture were tried and rejected; the pill
     * is where the thumb already is. [PipWindowGeometry] still records where the
     * camera draws the window.
     */
    /**
     * The blend slider follows the camera's own value into the pilot's hand, and is SHOWN ONLY
     * IN PIP (design decision).
     *
     * ⚠ **THE WRITE IS THROTTLED DURING THE DRAG AND COMMITTED ON THE LIFT.** A stroke of the
     * thumb crosses twenty steps; twenty writes to the camera in half a second is the shape of
     * a keystroke burst that has crashed an aircraft, even though this is the
     * camera's channel and not the fly-controller's. So the drag sends at most one write per
     * [BLEND_WRITE_MS], the pilot sees the picture move as they go, and the lift sends the
     * final value and STORES it. Only the lift is stored: a value the thumb passed over on the
     * way is not a decision.
     */
    private fun wireBlendSlider() {
        blendSlider.percent = AutelBlendFormat.percent(this)
        blendSlider.onPercentChanged = { percent, settled ->
            val cam = AutelProductHolder.xt706
            if (settled) {
                AutelBlendFormat.setPercent(this, percent)
                AutelBlendFormat.sendPercent(cam, percent)
                AppLog.i(TAG, "PIP blend set to $percent% thermal")
            } else {
                val now = SystemClock.elapsedRealtime()
                if (now - lastBlendWriteMs >= BLEND_WRITE_MS) {
                    lastBlendWriteMs = now
                    AutelBlendFormat.sendPercent(cam, percent)
                }
            }
        }
    }

    /** Shown only in PIP, where there are two layers to mix. See [BlendRatioSliderView]. */
    private fun renderBlendSlider() {
        if (!::blendSlider.isInitialized) return
        val show = cameraView == CameraView.PIP
        val want = if (show) View.VISIBLE else View.GONE
        if (blendSlider.visibility != want) {
            blendSlider.visibility = want
            // The left chrome inset changes with it — the AR arrows and the proximity labels
            // are measured from the slider's edge while it is on screen.
            findViewById<View>(R.id.flightToolbar).requestLayout()
        }
    }

    private fun renderPipSizeButton() {
        if (!::pipSizeButton.isInitialized) return
        val target = cameraView.maximised
        if (target == null) { pipSizeButton.visibility = View.GONE; return }
        pipSizeButton.setImageResource(
            if (cameraView == CameraView.PIP) R.drawable.ic_pip_expand else R.drawable.ic_pip_collapse)
        pipSizeButton.visibility = View.VISIBLE
    }

    /** IR pill lit when the thermal sensor is on screen (thermal or PIP), labelled with the
     *  view like the zoom pill is labelled with its level; palette button shown only then. */
    private fun refreshIrButtons() {
        renderPipSizeButton()
        renderBlendSlider()
        irButton.text = cameraView.label
        irButton.setBackgroundResource(
            if (cameraView.active) R.drawable.bg_pill_active else R.drawable.bg_zoom_pill
        )
        irButton.setTextColor(if (cameraView.active) androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_state_go) else Color.WHITE)
        irPaletteButton.visibility = if (cameraView.hasThermal) View.VISIBLE else View.GONE
        // Spelled out, not "WHOT"/"BHOT": in the HUD column it has the full column width, and
        // the abbreviations only existed to fit a narrow toolbar pill.
        irPaletteButton.text = when (irPalette) {
            IrColor.WhiteHot -> "WHITE HOT"
            IrColor.BlackHot -> "BLACK HOT"
            IrColor.IronBow -> "IRONBOW"
            // Not in the cycle (Explorer left the camera in Lava, Arctic, …): show the truth
            // rather than a wrong cycle label. Next tap moves to WHITE HOT.
            else -> irPalette.name.uppercase()
        }
    }

    /**
     * Reads the camera's ACTUAL display mode and palette so the buttons start truthful.
     *
     * Without this the toggle would assume VISIBLE/WhiteHot at every launch, and a camera left
     * in thermal by Autel's own app — or by a previous flight — would show an unlit IR button
     * over a thermal picture, with the first tap switching it to visible while the label
     * implied the opposite.
     */
    private fun syncIrStateFromCamera() {
        val cam = AutelProductHolder.xt706 ?: return
        // getDisplayMode / getIrColor / getISO / getShutter / getMediaMode are ONE-SHOT — the
        // XT706 impl routes them through CameraHttpRequest (an HTTP GET returns once). Verified in
        // the aar; none is a repeating subscription, so calling them on demand is safe.
        cam.getDisplayMode(object : CallbackWithOneParam<DisplayMode> {
            override fun onSuccess(mode: DisplayMode?) {
                // A camera left in PIP by Explorer or a previous flight lands here as PIP,
                // with the visible cone and the FILL rule — not as VISIBLE.
                cameraView = CameraView.fromDisplayModeName(mode?.name)
                runOnUiThread { refreshIrButtons() }
                AppLog.i(TAG, "camera display mode at connect: $mode -> $cameraView")
            }
            override fun onFailure(error: AutelError?) {
                AppLog.w(TAG, "getDisplayMode failed: ${error?.description}")
            }
        })
        cam.getIrColor(object : CallbackWithOneParam<IrColor> {
            override fun onSuccess(c: IrColor?) {
                irPalette = c ?: IrColor.WhiteHot
                runOnUiThread { refreshIrButtons() }
            }
            override fun onFailure(error: AutelError?) {
                AppLog.w(TAG, "getIrColor failed: ${error?.description}")
            }
        })
    }

    private fun applyZoom(level: Int) {
        val cam = AutelProductHolder.xt706
        if (cam == null) {
            AppLog.w(TAG, "zoom ${level}X ignored — camera not connected (or not an XT70x)")
            toast("The camera is not connected.")
            return
        }
        if (refuseZoomInPip()) return
        // ABSOLUTE, NOT RELATIVE TO A LEARNED BASELINE.
        //
        // The raw units are hundredths — 100 = 1.0x. Established from the camera's own
        // status push, where focalLength tracks zoomScale linearly: 100/0.47, 400/1.90, 800/3.79,
        // 1600/7.58. So "4X" is simply 400 and needs no baseline at all.
        //
        // ⚠ WHY A RELATIVE FORM IS A BUG, not just a roundabout way to the same answer.
        // It multiplies a value read once at connect, on the assumption the camera
        // always connects at 1x. Reconnect while the camera is still zoomed and that assumption
        // breaks silently: the app banks the zoomed value as "1X" and every level is then off by
        // that factor. Observed for real — the app reattached at a true 4x, labelled it 1X, and
        // could no longer zoom out; "1X" returned to 4x and "4X" drove the camera to 16x. An
        // absolute scale has no baseline to capture wrong.
        applyZoomRaw(level * ZOOM_RAW_PER_X)
    }

    /**
     * ⚠ **THE CAMERA IGNORES ZOOM IN PIP AND SAYS OK** (measured: `SetZoomFactor 200`
     * answered status 0 and read back 100). A success callback is not evidence. Refused here,
     * with the amber notice, rather than written and then caught by [verifyZoomAgainstCamera] — a pill
     * that jumps to 2X and back to 1X a second later reads as a fault, and the rocker would
     * do it on every tick. Not throttled: the rocker's own repeat delay spaces the ticks.
     */
    private fun refuseZoomInPip(): Boolean {
        if (cameraView != CameraView.PIP) return false
        AppLog.w(TAG, "zoom refused — the camera ignores zoom in PIP")
        showNotice("Zoom is not available in the PIP view", refused = true)
        return true
    }

    /**
     * Drives the camera to an absolute raw zoom and repaints the pill. The one place zoom is
     * written, whichever control asked — the pill or the zoom rocker.
     *
     * Clamped HERE because the SDK does not: `setDigitalZoomScale` passes the int straight to the
     * camera with no range check of its own (verified in the aar), so an unclamped accumulator
     * would happily send nonsense. The bounds are this project's own measured ones — 100 = 1x
     * through 1600 = 16x, from the focal-length/zoomScale calibration in [applyZoom].
     */
    private fun applyZoomRaw(rawTarget: Int) {
        val cam = AutelProductHolder.xt706 ?: return
        val target = rawTarget.coerceIn(ZOOM_RAW_MIN, ZOOM_RAW_MAX)
        // Stamped for seedZoomFromCamera's settle guard: for the next ZOOM_SEED_SETTLE_MS the
        // camera's own ~2Hz reading may still describe the zoom from BEFORE this write.
        lastZoomWriteMs = SystemClock.elapsedRealtime()
        // Record the intent SYNCHRONOUSLY, before the round-trip. This is what the ack below
        // checks itself against, and it is also where the rocker resumes from — so a pill tap
        // mid-flight leaves the rocker continuing from the value the pilot just
        // chose, not from wherever it had drifted to.
        pendingZoomRaw = target
        cam.setDigitalZoomScale(target, camCb("setDigitalZoomScale($target)") {
            // ⚠ A STALE ACK MUST NOT DRAG THE PILL BACKWARDS. The camera answers a round-trip
            // later, by which time a held rocker has already moved the accumulator on. Writing
            // this (older) target back unconditionally would make the label and the AR zoom jump
            // backward mid-hold, then jump forward again on the next push — a visible stutter
            // on the one control whose whole purpose is smoothness. So the write-back happens
            // only when this ack IS still the latest intent.
            if (pendingZoomRaw != target) {
                AppLog.v(TAG, "zoom ack for $target superseded by $pendingZoomRaw — not repainting")
                return@camCb
            }
            zoomRaw = target
            zoomButton.text = zoomLabel(target)
            // NOT written to the AR/published zoom here. An OK is a request:
            // the picture changes when the camera applies it, and the camera's own ~2 Hz status
            // push (AutelProductHolder) reports exactly that. Several writers of one value make
            // the markers jump out, back and out again over a picture that has not changed.
            AppLog.i(TAG, "zoom now ${zoomLabel(target)} (raw=$target)")
        })
        verifyZoomAgainstCamera(target)
    }

    /** The pending zoom check. A held rocker writes repeatedly and each write replaces this, so
     *  one check falls at the end of the burst rather than one per tick. */
    private var zoomVerify: Runnable? = null

    /**
     * Checks the camera's reported zoom against what was asked for, once the write has settled.
     *
     * ⚠ **THIS ONE ONLY DIAGNOSES — see [ZoomVerifyPolicy]'s file note before extending it.**
     * A zoom that does not take already corrects itself on both the wire and the pill, so there
     * is nothing here to repair and nothing to tell the pilot. What was missing is a log line
     * that can be told apart from the benign case, which is Autel Explorer having moved the
     * zoom behind us — [seedZoomFromCamera] writes one wording for both.
     *
     * ⚠ IT WAITS THE SAME [ZOOM_SEED_SETTLE_MS] THAT SEEDING DOES. Believing the camera's
     * reading sooner would accuse a write on data this screen elsewhere calls stale.
     */
    private fun verifyZoomAgainstCamera(requestedRaw: Int) {
        zoomVerify?.let { handler.removeCallbacks(it) }
        val check = Runnable {
            zoomVerify = null
            if (AutelProductHolder.xt706 == null) return@Runnable
            // Superseded by a later write: that write started its own check, and this target is
            // no longer what the pilot asked for.
            if (pendingZoomRaw != requestedRaw) return@Runnable
            val ratio = TakBridgeHolder.currentZoomFactor
            if (!ratio.isFinite() || ratio < 1.0) return@Runnable    // nothing usable to compare
            val reportedRaw = Math.round(ratio * ZOOM_RAW_PER_X).toInt()
            if (zoomWriteWasIgnored(requestedRaw, reportedRaw)) {
                AppLog.w(TAG, "ZOOM WRITE IGNORED. Asked for ${zoomLabel(requestedRaw)} " +
                    "(raw=$requestedRaw), setDigitalZoomScale reported OK, and the camera " +
                    "reports ${zoomLabel(reportedRaw)} (raw=$reportedRaw) " +
                    "${ZOOM_VERIFY_SETTLE_MS}ms later. The pill follows the CAMERA.")
                // ⚠ THE CAMERA WINS; LOGGING ALONE IS NOT ENOUGH. A pill left
                // claiming a zoom the camera is not at breaks the rule the whole HUD is built
                // on — UI state shows what the AIRCRAFT holds, never what was requested. The
                // published cone already follows the camera; this corrects the pill.
                pendingZoomRaw = reportedRaw
                zoomRaw = reportedRaw
                zoomButton.text = zoomLabel(reportedRaw)
            } else {
                AppLog.v(TAG, "zoom check: the camera is at ${zoomLabel(reportedRaw)} as asked")
            }
        }
        zoomVerify = check
        handler.postDelayed(check, ZOOM_VERIFY_SETTLE_MS)
    }

    /** "2X" for whole steps, "2.4X" for anything the rocker lands on between them. A pill reading
     *  "2X" while the camera sits at 2.4x would be the kind of small lie this HUD does not tell. */
    private fun zoomLabel(raw: Int): String {
        val x = raw / ZOOM_RAW_PER_X.toDouble()
        return if (raw % ZOOM_RAW_PER_X == 0) "${x.toInt()}X" else String.format("%.1fX", x)
    }

    /**
     * The right zoom rocker was deflected: +1 zooms in (tighter), -1 zooms out (wider).
     *
     * ⚠ THE ROCKER IS SPRING-LOADED, NOT A FREE-SPINNING DETENTED WHEEL. It is pushed against a stop and held there; the controller then REPEATS the
     * same event about every 200ms. Confirmed in the hardware capture: 8 × ZOOM_IN at 199-213ms
     * spacing, then 12 × ZOOM_OUT at the same cadence. So each event is a TICK OF HELD TIME,
     * not a countable detent, and the code below counts ticks because that is the only clock
     * this control has.
     *
     * EVERY TICK THAT REACHES THIS FUNCTION IS A HELD ONE. The rocker goes on emitting while
     * the spring returns it to centre; acting on those would run
     * the zoom on past where the pilot let go — 1.8x at worst, three trailing ticks. Those
     * events carry keyValue 0 and are dropped in installHardwareButtonListener, which is the
     * only place that may read that field. Do not re-add release detection here.
     *
     * FIXED LEVELS, NOT A CONTINUOUS RATE (design decision). One press moves ONE level
     * along [ZoomLadder]; holding walks the levels and stops on whichever one it reaches. A
     * continuous accumulator — 0.6x a tick, with soft detents parking at 2x, 4x
     * and 8x — was flown and judged the wrong model: a pilot frames a subject at a few
     * known magnifications and does not want the values between them.
     *
     * Press and hold are told apart the way a keyboard does it: one level immediately, then
     * [ZOOM_LADDER_REPEAT_DELAY_TICKS] before the walk starts, then one level every
     * [ZOOM_LADDER_REPEAT_EVERY_TICKS]. An ordinary press is several ticks long, so without the
     * delay it would run up several levels before the pilot could let go.
     *
     * A new press — silence longer than [ZOOM_RELEASE_GAP_MS], or a reversal — RE-SEEDS from
     * the camera before it steps. See [seedZoomFromCamera] for why that matters to a ladder in
     * a way it never did to an accumulator.
     */
    private fun onZoomRocker(direction: Int) {
        if (AutelProductHolder.xt706 == null) return

        val now = SystemClock.elapsedRealtime()
        val newPress = now - lastZoomTickMs > ZOOM_RELEASE_GAP_MS ||
            direction != lastZoomDirection
        lastZoomTickMs = now
        lastZoomDirection = direction

        if (newPress) {
            // START OF A PRESS: take the camera's own zoom before deciding where "one level
            // up" goes. Nothing is in flight here — a new press follows at least
            // ZOOM_RELEASE_GAP_MS of silence and the push throttle is shorter than that — so
            // the reading is settled, and every press re-anchors the app to the aircraft
            // rather than to whatever it last believed.
            seedZoomFromCamera()
            zoomHoldTicks = 0
            stepZoomOneLevel(direction)
            return
        }

        // HELD. Wait out the repeat delay, then move one level per repeat period. This is a
        // keyboard's typematic behaviour, and it is here for the same reason: without the
        // delay, an ordinary press — which is several ticks long — would run up several levels
        // before the pilot could let go.
        zoomHoldTicks++
        if (zoomHoldTicks < ZOOM_LADDER_REPEAT_DELAY_TICKS) return
        if ((zoomHoldTicks - ZOOM_LADDER_REPEAT_DELAY_TICKS) % ZOOM_LADDER_REPEAT_EVERY_TICKS != 0)
            return
        stepZoomOneLevel(direction)
    }

    /**
     * Moves the zoom one level along [ZoomLadder], in [direction], and paints the result.
     *
     * The pill and the AR overlay follow IMMEDIATELY, ahead of the camera acknowledging, while
     * the camera itself is written on a [ZOOM_PUSH_MS] throttle. A held rocker walking the
     * ladder must not become a queue of commands the camera answers long after the pilot let
     * go; only the newest target matters, thus coalescing loses nothing.
     */
    private fun stepZoomOneLevel(direction: Int) {
        if (refuseZoomInPip()) return
        val next = ZoomLadder.next(pendingZoomRaw, direction)
            .coerceIn(ZOOM_RAW_MIN, ZOOM_RAW_MAX)
        if (next == pendingZoomRaw) return          // already against the end of the ladder
        pendingZoomRaw = next
        zoomRaw = next
        zoomButton.text = zoomLabel(next)
        // The pill shows the request at once; the AR zoom waits for the camera.
        if (!zoomPushScheduled) {
            zoomPushScheduled = true
            handler.postDelayed({
                zoomPushScheduled = false
                applyZoomRaw(pendingZoomRaw)
            }, ZOOM_PUSH_MS)
        }
    }

    /**
     * Points the app's idea of the zoom at what the CAMERA actually holds.
     *
     * [TakBridgeHolder.currentZoomFactor] is an absolute ratio off the camera's own ~2Hz status
     * push (AutelProductHolder), thus it needs NO new SDK listener — SDK listener
     * slots hold one client, and this screen must not take one.
     *
     * Why it matters for a ladder: with a continuous rocker, starting from a wrong
     * value only means a wrong label, and the pilot zooms until the picture looks right. With
     * a ladder, the wrong starting value sends "one level up" to the wrong level. Autel
     * Explorer leaves the camera zoomed, and an app that assumed 1x at connect
     * called a true 4x "1X" and could not zoom out at all.
     *
     * ⚠ IT DEFERS TO OUR OWN VALUE WHILE A WRITE IS STILL SETTLING. The camera's reading is a
     * ~2Hz push, thus it lags a zoom this app just wrote by up to half a second. Without the
     * [ZOOM_SEED_SETTLE_MS] guard, tapping twice in quick succession would re-seed the second
     * press from the pre-first-press value and send the pilot to the SAME level twice — the
     * control would appear stuck exactly when it is being used the way a ladder invites, which
     * is a rapid tap. The guard costs nothing: the case this function exists for is a camera
     * some OTHER app left zoomed, and that is never within a second of our own write.
     *
     * Silently does nothing if the reading is not usable — before the bridge has ever run it is
     * the 1.0 default, which is also the honest answer when there is nothing better.
     */
    private fun seedZoomFromCamera() {
        if (SystemClock.elapsedRealtime() - lastZoomWriteMs < ZOOM_SEED_SETTLE_MS) return
        val ratio = TakBridgeHolder.currentZoomFactor
        if (!ratio.isFinite() || ratio < 1.0) return
        val raw = Math.round(ratio * ZOOM_RAW_PER_X).toInt().coerceIn(ZOOM_RAW_MIN, ZOOM_RAW_MAX)
        if (raw == pendingZoomRaw) return
        AppLog.i(TAG, "zoom re-seeded from the camera: ${zoomLabel(raw)} (raw=$raw)")
        pendingZoomRaw = raw
        zoomRaw = raw
        zoomButton.text = zoomLabel(raw)
    }

    /**
     * The TAK channels, from the flight screen.
     *
     * A pilot must be able to change the scope of this aircraft IN FLIGHT. Pre-Flight can do it,
     * but going there stops the video to the team, which is the wrong thing to do in the middle
     * of a sortie.
     *
     * THE SERVER HOLDS THE STATE. This screen reads it, writes to it and follows it. It is not
     * the control — an administrator can change the same thing from TAK Portal, and the rows
     * follow that within about a second because of the t-x-g-c listener below.
     *
     * LOCKED BY THE TAK CONFIGURATION LOCK. When the lock is on, the rows show the channels and
     * refuse a change. Reading is never locked: a pilot must always be able to SEE the scope of
     * the aircraft, and the lock exists to stop an accidental change and not to hide the truth.
     */
    /**
     * True when the pilot has entered the unlock password on THIS visit to the flight screen.
     *
     * THIS VISIT ONLY — it is never written to the preferences. The pilot wants to change a channel
     * in flight, not to leave the TAK configuration unlocked after they land. Pre-Flight keeps
     * its own lock, and this does not touch it. Leaving the flight screen clears this.
     */
    private var takChannelsUnlockedThisSession = false

    /** The store every [AppMode] predicate on this screen reads. One accessor, so no gate on
     *  this screen can end up reading a different preference file from the others. */
    private fun modePrefs(): android.content.SharedPreferences =
        getSharedPreferences(TakConnectActivity.PREFS, MODE_PRIVATE)

    /**
     * Asks for the unlock password, in the flight dialog.
     *
     * Going to Pre-Flight to unlock defeats the point of a control on the flight screen.
     * The password is Pre-Flight's own constant, not a copy of it —
     * one password and one idea of "locked", with no second string to drift.
     *
     * A wrong password and Cancel do the same thing, the same as Pre-Flight: the only way out
     * with the rows editable is the right password.
     */
    private fun promptChannelUnlock(onUnlocked: () -> Unit) {
        // ⚠ BASIC: NO PASSWORD — THE FOURTH PROMPT, AND THE EASY ONE TO MISS.
        // This is the TAK lock's in-flight face, built outside ConfigLock, so
        // ConfigLock's Basic rule does not reach it. Without this a Basic pilot meets the
        // password in the AIR, which AppMode.unlockNeedsPassword says a Basic user never does.
        // This visit only, exactly as the password path: the flag below, never KEY_TAK_LOCKED.
        if (!AppMode.unlockNeedsPassword(modePrefs())) {
            takChannelsUnlockedThisSession = true
            AppLog.i(TAG, "channels unlocked for this session (Basic mode, no password)")
            onUnlocked()
            return
        }
        // STYLED EXACTLY AS PRE-FLIGHT'S UNLOCK FIELD. A programmatic EditText takes the
        // PLATFORM's colours and no background at all, thus without styling it is a bare line
        // of text across the full width of the dialog — hard to see and hard to hit. The
        // background and the padding are what make it look like a field, and the wrapper is
        // what keeps it off the edges. Do not simplify either away.
        val field = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            // Pre-Flight's field has this: on a landscape-locked screen the full-screen extract
            // keyboard would otherwise cover the dialog.
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            hint = "Password"
            textSize = 15f
            setTextColor(androidx.core.content.ContextCompat.getColor(
                applicationContext, R.color.tp_text_primary))
            setHintTextColor(androidx.core.content.ContextCompat.getColor(
                applicationContext, R.color.tp_text_hint))
            setBackgroundResource(R.drawable.bg_dialog_field)
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val wrap = android.widget.FrameLayout(this).apply {
            val padH = (16 * resources.displayMetrics.density).toInt()
            val padV = (8 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            addView(field)
        }
        // Destructive theme, as Pre-Flight's unlock uses: getting this wrong changes who sees
        // the aircraft.
        AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
            // No body text. It is a password prompt, and the pilot already knows what they
            // touched. Explaining the this-visit-only rule here would be a thing to
            // read in the air and not a thing to decide.
            .setTitle("Unlock channels")
            .setView(wrap)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Unlock") { _, _ ->
                if (field.text.toString() == TakConnectActivity.UNLOCK_PASSWORD) {
                    takChannelsUnlockedThisSession = true
                    AppLog.i(TAG, "channels unlocked for this flight-screen session")
                    onUnlocked()
                } else {
                    AppLog.w(TAG, "channel unlock refused — wrong password")
                    showNotice("The password is wrong.", refused = true)
                }
            }
            .show()
    }

    private fun onTakChannelsTapped() {
        if (!TakManager.getInstance().isConnected) {
            showNotice("TAK is not connected. The channels are on the server.", refused = true)
            return
        }
        val themed = android.view.ContextThemeWrapper(this, R.style.TakDialogTheme)
        val view = android.view.LayoutInflater.from(themed)
            .inflate(R.layout.dialog_tak_channels, null)
        val list = view.findViewById<android.widget.LinearLayout>(R.id.takChanList)
        val status = view.findViewById<TextView>(R.id.takChanStatus)
        val configLocked = getSharedPreferences(TakConnectActivity.PREFS, MODE_PRIVATE)
            .getBoolean(TakConnectActivity.KEY_TAK_LOCKED, false)
        // The this-visit unlock is what the pilot just typed; the pref is what Pre-Flight holds.
        var locked = configLocked && !takChannelsUnlockedThisSession
        val lockedNote = view.findViewById<TextView>(R.id.takChanLocked)

        var channels: List<com.taklite.client.tak.TakMissionClient.Channel> = emptyList()
        var painting = false

        fun paint(chans: List<com.taklite.client.tak.TakMissionClient.Channel>) {
            channels = chans
            painting = true
            list.removeAllViews()
            if (chans.isEmpty()) {
                // Channels turned off on this server. Show nothing to change — a write to such
                // a server is reported to cause real trouble on it.
                status.text = "This server has no channels."
                painting = false
                return
            }
            for (ch in chans) {
                val row = android.widget.CheckBox(themed).apply {
                    // Two-way is the normal case and gets no label — a note on every row is
                    // noise, and the exception is what a pilot needs to see.
                    text = when {
                        ch.canSend && ch.canReceive -> ch.name
                        ch.canReceive -> "${ch.name} - Rx Only"
                        ch.canSend -> "${ch.name} - Tx Only"
                        else -> "${ch.name} - no direction"
                    }
                    // ⚠ LOCKED IS NOT DISABLED — see the same note in TakConnectActivity.
                    // Disabling greys the tick, and the tick is what the pilot came to read.
                    setTextColor(androidx.core.content.ContextCompat.getColor(
                        applicationContext,
                        if (locked) R.color.tp_text_secondary else R.color.tp_text_primary))
                    isChecked = ch.active
                    isClickable = !locked
                    isFocusable = !locked
                    buttonTintList = android.content.res.ColorStateList.valueOf(
                        androidx.core.content.ContextCompat.getColor(
                            applicationContext, R.color.tp_accent))
                    setOnCheckedChangeListener { _, checked ->
                        if (painting) return@setOnCheckedChangeListener
                        ch.active = checked
                        val bits = channels.filter { it.active && it.bitpos >= 0 }.map { it.bitpos }
                        status.text = "Sending ${bits.size} active channel(s)…"
                        // The COMPLETE set every time — activebits is absolute.
                        TakMissionManager.setActiveChannels(bits) { ok ->
                            status.text = if (ok) "The server has ${bits.size} active channel(s)."
                                          else "The server refused the change."
                        }
                    }
                }
                list.addView(row)
            }
            painting = false
        }

        fun reload() = TakMissionManager.listChannels { paint(it) }
        lockedNote.visibility = if (locked) View.VISIBLE else View.GONE
        reload()

        // The ELEVATED account's channels under the Standard's: the whole
        // scope of this aircraft, in flight, on one screen. WRITABLE, the same
        // as the Standard rows above and behind the same lock — see
        // TakServerActivity.renderVideoChannels for why a tick here is also the only possible
        // "ignore incoming" control, and for what it costs every controller on that account.
        // Read once at open: the server's t-x-g-c change notice arrives on the Standard
        // connection only.
        val elevatedLabel = view.findViewById<TextView>(R.id.takChanElevatedLabel)
        val elevatedList = view.findViewById<android.widget.LinearLayout>(R.id.takChanElevatedList)
        var elevated: List<com.taklite.client.tak.TakMissionClient.Channel> = emptyList()
        var paintingElevated = false

        fun paintElevated(chans: List<com.taklite.client.tak.TakMissionClient.Channel>) {
            elevated = chans
            paintingElevated = true
            elevatedList.removeAllViews()
            if (chans.isEmpty()) {
                elevatedList.addView(TextView(themed).apply {
                    text = "The server returned no channels for the Elevated account."
                    setTextColor(androidx.core.content.ContextCompat.getColor(
                        applicationContext, R.color.tp_text_secondary))
                })
            } else for (ch in chans) {
                elevatedList.addView(android.widget.CheckBox(themed).apply {
                    text = TakMissionManager.channelLabel(ch)
                    // ⚠ LOCKED IS NOT DISABLED — the same rule as every other channel row.
                    setTextColor(androidx.core.content.ContextCompat.getColor(
                        applicationContext,
                        if (locked) R.color.tp_text_secondary else R.color.tp_text_primary))
                    isChecked = ch.active
                    isClickable = !locked
                    isFocusable = !locked
                    buttonTintList = android.content.res.ColorStateList.valueOf(
                        androidx.core.content.ContextCompat.getColor(
                            applicationContext, R.color.tp_accent))
                    setOnCheckedChangeListener { _, checked ->
                        if (paintingElevated) return@setOnCheckedChangeListener
                        ch.active = checked
                        val bits = elevated.filter { it.active && it.bitpos >= 0 }.map { it.bitpos }
                        status.text = "Sending ${bits.size} Elevated channel(s)…"
                        TakMissionManager.setElevatedActiveChannels(
                            this@FlightActivity, bits) { ok ->
                            status.text = if (ok) {
                                "The server has ${bits.size} Elevated channel(s). " +
                                    "This applies to every controller on that account."
                            } else {
                                "The server refused the Elevated change."
                            }
                        }
                    }
                })
            }
            paintingElevated = false
        }

        // ⚠ EXPLICIT, NOT INHERITED. These rows are WRITABLE and change the shared Elevated
        // account's activebits. Hidden in Basic only because the split happens to be gone would
        // be the same inherited gating the Emergency Broadcast row rejects: a stray split in Basic
        // would put live account-wide controls in front of a pilot over video.
        if (AppMode.elevatedAccountAllowed(modePrefs()) &&
            TakManager.getInstance().isVideoChannelConfigured()
        ) {
            TakMissionManager.listElevatedChannels(this) { chans ->
                if (chans == null) return@listElevatedChannels
                paintElevated(chans)
                elevatedLabel.visibility = View.VISIBLE
                elevatedList.visibility = View.VISIBLE
            }
        }

        // Follow the server while the dialog is open, and stop when it closes.
        val onGroups = TakManager.GroupChangeListener {
            AppLog.i(TAG, "channels changed on the server — re-reading (flight screen)")
            reload()
        }
        TakManager.getInstance().addGroupChangeListener(onGroups)

        val dialog = AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("TAK Channels")
            .setView(view)
            .setNegativeButton("Close", null)
            // Unlock HERE. Going to Pre-Flight for it defeats the point of this dialog.
            // In Basic there is no password, so no ellipsis: "…" promises a further step that
            // never comes.
            .apply {
                if (locked) setNeutralButton(
                    if (AppMode.unlockNeedsPassword(modePrefs())) "Unlock…" else "Unlock", null)
            }
            .setOnDismissListener {
                TakManager.getInstance().removeGroupChangeListener(onGroups)
            }
            .create()
        dialog.show()
        // Set after show() so the dialog does NOT close when the password prompt opens over it.
        if (locked) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                promptChannelUnlock {
                    locked = false
                    lockedNote.visibility = View.GONE
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.visibility = View.GONE
                    paint(channels)     // repaint the rows, now editable
                    // BOTH lists. The Elevated rows are ticks behind the same lock,
                    // so an unlock that repainted only the Standard ones would
                    // leave half the dialog refusing touches with nothing to say why.
                    if (elevated.isNotEmpty()) paintElevated(elevated)
                }
            }
        }
    }

    /** Log + toast-on-failure adapter for the camera's completion callbacks. */
    private fun camCb(opName: String, onOk: (() -> Unit)? = null) = object : CallbackWithNoParam {
        override fun onSuccess() {
            AppLog.i(TAG, "$opName: OK")
            onOk?.let { runOnUiThread { it() } }
        }
        override fun onFailure(error: AutelError?) {
            AppLog.w(TAG, "$opName failed: ${error?.description}")
            runOnUiThread { toast("$opName failed: ${error?.description ?: "unknown error"}") }
        }
    }

    /**
     * REC — start/stop recording to the aircraft's SD card. The pill's shown state is driven by
     * the camera's own MediaStatus push events (via [AutelProductHolder.isRecording]), not by
     * which button was pressed last — see updateHud().
     *
     * Recording needs the camera in VIDEO media mode (the DJI SDK needs a flat-mode switch first
     * for the same reason).
     *
     * ⚠ **THE MODE IS READ ON EVERY PRESS, NEVER ASSUMED.** A still leaves the camera in SINGLE
     * if anything interrupts the return to VIDEO. There is no on-screen shutter: the pilot uses
     * the controller's HARDWARE shutter, which this application neither drives nor observes. So the camera can
     * arrive here in a mode nothing in this process chose. Do not replace this read with a
     * cached value.
     */
    private fun onRecordToggleTapped() {
        val cam = AutelProductHolder.camera
        if (cam == null) {
            AppLog.w(TAG, "REC ignored — camera not connected")
            toast("The camera is not connected.")
            return
        }
        if (AutelProductHolder.isRecording) {
            // Nothing to restore: VIDEO is the resting mode, so stopping leaves the camera
            // exactly where it belongs and the picture does not move.
            cam.stopRecordVideo(camCb("stopRecordVideo"))
            return
        }
        // ⚠ **REC RECORDS FROM ANY MODE** (design decision). The pill is never a SHUTTER, even
        // while the camera is in stills; the rule is that the record button — pill or hardware —
        // puts the camera in video if
        // it is not there, starts, and leaves it in video; the hardware shutter puts it in
        // stills and leaves it there. One rule for both buttons, and the pill always reads REC.
        //
        // ⚠ **THE FLAG IS NOT THE ONLY THING THIS PRESS LOOKS AT.** The flag is learned from
        // MediaStatus pushes, and a recording that is already running sends no further
        // RECORD_START, thus a flag that went wrong-false stays wrong: the pilot presses
        // a dark pill, this takes the START path, and the camera answers "Needs an idle status
        // to start recording videos" (measured). The camera's own work
        // state is checked as well — it rides the ~2 Hz info push and is independent of the
        // pushes this flag comes from — and a recording that is really running is STOPPED,
        // which is what the pilot meant by pressing the only record control on the screen.
        if (AutelProductHolder.cameraSaysRecording) {
            AppLog.i(TAG, "REC pressed while the camera reports " +
                "${AutelProductHolder.workState} — stopping")
            cam.stopRecordVideo(camCb("stopRecordVideo"))
            return
        }
        startRecordFromAnyMode(cam)
    }

    /**
     * Starts a recording whatever mode the camera is in.
     *
     * ⚠ **THE CAMERA'S MODE ACK IS NOT READINESS, AND NEITHER IS A FIXED WAIT.** Measured:
     * a start 2 ms after the `setMediaMode` ack is acked and silently ignored;
     * a start at 800 ms after the ack likewise; a fixed 2000 ms
     * wait was still answered "Needs the recording mode" once, and the
     * retry at 3.2 s worked. So this WAITS FOR THE CAMERA'S OWN 2 Hz PUSH to say VIDEO, then
     * gives it [MODE_READY_SETTLE_MS] more, then issues the verified start. Polled at
     * [MODE_POLL_MS] up to [MODE_POLL_LIMIT_MS]; past that it starts anyway and lets the
     * watchdog in [startRecordVerified] report the truth.
     *
     * Already in VIDEO: the start is immediate.
     */
    private fun startRecordFromAnyMode(cam: AutelBaseCamera) {
        if (AutelProductHolder.mediaMode == MediaMode.VIDEO) {
            startRecordVerified(cam)
            return
        }
        AppLog.i(TAG, "record from ${AutelProductHolder.mediaMode}: switching the camera to VIDEO first")
        // Idempotent if the camera is already changing on its own (the hardware button's
        // first press); the read-back below is what is waited for, not this ack.
        runCatching { cam.setMediaMode(MediaMode.VIDEO, camCb("setMediaMode(VIDEO)") {}) }
            .onFailure { AppLog.w(TAG, "setMediaMode(VIDEO) threw: ${it.message}") }
        val startedAt = SystemClock.elapsedRealtime()
        lateinit var poll: Runnable
        poll = Runnable {
            if (AutelProductHolder.camera == null) return@Runnable
            if (AutelProductHolder.isRecording) return@Runnable   // the camera got there itself
            val waited = SystemClock.elapsedRealtime() - startedAt
            when {
                AutelProductHolder.mediaMode == MediaMode.VIDEO -> {
                    AppLog.i(TAG, "camera reports VIDEO after ${waited}ms — starting in ${MODE_READY_SETTLE_MS}ms")
                    handler.postDelayed({ startRecordVerified(cam) }, MODE_READY_SETTLE_MS)
                }
                waited >= MODE_POLL_LIMIT_MS -> {
                    AppLog.w(TAG, "camera never reported VIDEO in ${waited}ms — starting anyway; the watchdog decides")
                    startRecordVerified(cam)
                }
                else -> handler.postDelayed(poll, MODE_POLL_MS)
            }
        }
        handler.postDelayed(poll, MODE_POLL_MS)
    }

    /**
     * Issues StartRecording and then CHECKS WHETHER IT ACTUALLY WORKED.
     *
     * The camera answers StartRecording with status 0 even when it is not going to record —
     * observed on hardware: a start issued 2ms after a Single->Record mode switch
     * was acked as success, produced no RECORD_START event, and left no file on the SD card.
     * A pilot who trusts the button in that state believes they have footage they do not have,
     * which is the worst possible failure for this control. So the SDK's "OK" is treated as a
     * request receipt, not as evidence.
     *
     * Truth comes from [AutelProductHolder.isRecording], which only goes true on the camera's
     * own RECORD_START push. If that has not arrived within [RECORD_CONFIRM_MS] we retry once
     * (the settle delay may simply have been too short for a slower card or a busy camera),
     * and if it still has not arrived we TELL THE PILOT rather than leaving a dark pill and a
     * false sense of recording.
     *
     * The window is generous: when the camera is genuinely ready, RECORD_START lands in ~1ms.
     */
    private fun startRecordVerified(cam: AutelBaseCamera, isRetry: Boolean = false) {
        // WATCHDOG ARMED BEFORE THE CALL, NOT AFTER. Posted below the SDK call, a SYNCHRONOUS
        // THROW would skip the very safety net that exists to
        // catch a record that does not happen. That is not hypothetical: with the camera set to
        // internal flash, startRecordVideo throws NPE out of the SDK's own precondition check.
        // The SDK swallows it, no callback ever comes, and with the watchdog posted after the
        // call the pilot gets a dark pill and complete silence — the exact outcome this
        // whole verify-don't-trust design was written to prevent. Posting first makes the net
        // independent of how the call fails.
        val watchdog = Runnable {
            if (AutelProductHolder.isRecording) return@Runnable   // camera confirmed it
            if (!isRetry) {
                AppLog.w(TAG, "no RECORD_START ${RECORD_CONFIRM_MS}ms after startRecordVideo — retrying once")
                startRecordVerified(cam, isRetry = true)
            } else {
                AppLog.e(TAG, "recording did not start: camera accepted StartRecording twice " +
                    "but never reported RECORD_START")
                toast(recordFailureReason() ?: "Recording did not start. The camera did not confirm.")
            }
        }
        handler.postDelayed(watchdog, RECORD_CONFIRM_MS)
        // Guarded for the same reason: a throw here must not escape into the SDK's dispatcher,
        // where it is logged as a "parse error" and never reaches the pilot. On a throw we
        // already know the outcome, so the watchdog is cancelled — BY REFERENCE, never
        // removeCallbacksAndMessages(null), which would also take out the HUD refresh loop and
        // the notice auto-hide that share this handler.
        runCatching {
            cam.startRecordVideo(camCb(if (isRetry) "startRecordVideo(retry)" else "startRecordVideo"))
        }.onFailure {
            AppLog.e(TAG, "startRecordVideo threw: $it")
            handler.removeCallbacks(watchdog)
            toast(recordFailureReason() ?: "Recording could not start. The camera refused.")
        }
    }

    /**
     * The reason recording is not going to work, in words a pilot can act on — or null when
     * nothing known is wrong and the generic "camera did not confirm" is the honest answer.
     *
     * Storage is checked first because it is the failure that looks like nothing at all: a card
     * in the slot that the camera is not writing to (see [AutelProductHolder.recordingToInternal]).
     */
    private fun recordFailureReason(): String? {
        val h = AutelProductHolder
        return when {
            h.recordingToInternal && h.mmcState == com.autel.common.camera.base.MMCState.CARD_FULL ->
                "The camera is recording to its internal memory, which is full. " +
                    "Change the camera storage to the SD card."
            h.recordingToInternal ->
                "The camera is recording to its internal memory, not the SD card."
            h.sdCardState != null &&
                h.sdCardState != com.autel.common.camera.base.SDCardState.CARD_READY ->
                "The SD card is not ready (${h.sdCardState})."
            else -> null
        }
    }

    /**
     * Photo — still to the aircraft's SD card. Confirmation comes from the camera's
     * PHOTO_TAKEN_DONE event (surfaced as a notice by updateHud), not from the call returning.
     *
     * ANSWERED ON HARDWARE: the XT709
     * does NOT accept startTakePhoto while in VIDEO mode — it rejects it, so from the resting
     * mode this always takes the SINGLE-mode fallback below. The direct attempt is kept anyway:
     * it costs one fast rejection (~100ms) and self-heals if a firmware update ever allows it.
     *
     * VIDEO IS THE RESTING MODE, and the restore below is what maintains that. The camera sends
     * a different frame shape per mode (photo 4:3, video 16:9) and nothing can change that —
     * setAspectRatio is ignored by this camera and VideoResolution has no 4:3 option, both
     * verified — so SOME action has to pay the visible shape change. Design decision:
     * recordings are far more frequent than stills on this aircraft, so the cost
     * belongs on the photo path, not on record start/stop. Recording is the thing that must
     * stay visually still, because that is when the TAK team is watching the screen capture.
     */

    /**
     * Drives the LIVE pill from what the stream is ACTUALLY doing, not from whether a streamer
     * object exists. LIVE means video is leaving the controller; amber means we are trying;
     * off means nothing is running. A pilot uses this to decide whether the team can see what
     * they see, so "an object was constructed" is not good enough.
     */
    private fun refreshStreamToggle() {
        streamToggle.setState(
            when (VideoStreamerHolder.state) {
                VideoStreamerHolder.State.LIVE -> LiveToggleView.State.LIVE
                VideoStreamerHolder.State.CONNECTING -> LiveToggleView.State.RECONNECTING
                VideoStreamerHolder.State.OFF -> LiveToggleView.State.OFF
            }
        )
    }

    /** Transient green notice over the video, upper-left. */
    /**
     * Transient notice over the video, auto-hidden.
     *
     * [refused] tells the pilot the app did NOT do the thing, and makes the text amber instead
     * of green. The colour is set here and never at a call site, so a refusal cannot reach the
     * screen wearing the acknowledgement colour — a pilot reads the colour before the words.
     *
     * This goes to the notice and never to a Toast, because the flight screen IS the TAK video
     * feed: a Toast is not in the screen capture, so a refused marker would leave the team
     * waiting for a mark that is never coming.
     */
    private fun showNotice(text: String, refused: Boolean = false) {
        fpvNotice.text = text
        fpvNotice.setTextColor(androidx.core.content.ContextCompat.getColor(this,
            if (refused) R.color.tp_state_caution else R.color.tp_state_go))
        fpvNotice.visibility = View.VISIBLE
        handler.removeCallbacks(hideNotice)
        handler.postDelayed(hideNotice, NOTICE_MS)
    }

    private val hideNotice = Runnable { fpvNotice.visibility = View.GONE }

    /**
     * Told by [TakManager] whenever the Emergency Broadcast override starts, renews or ends.
     * This screen only PAINTS. The flight-record line belongs to
     * [EmergencyBroadcastPolicy], installed at application start so they run whichever screen
     * is open, or none.
     */
    private val emergencyBroadcastListener =
        TakManager.EmergencyBroadcastListener { active, expiresAtEpochMs, _ ->
            runOnUiThread { paintEmergencyBroadcast(active, expiresAtEpochMs) }
        }

    /** Shows or hides the running notice and keeps its countdown current. Called from the
     *  listener above on every change, and from [updateHud]'s tick while active so the mm:ss
     *  keeps moving. */
    private fun paintEmergencyBroadcast(active: Boolean, expiresAtEpochMs: Long) {
        // ⚠ THE GATE BELONGS HERE AND NOWHERE ELSE. This function sets the banner VISIBLE
        // unconditionally and the HUD tick calls it twice a second, so hiding the banner at
        // any other site is undone within half a second. Basic cannot have a broadcast
        // running — AppModeTransition cancels one before it tears the split down — and this
        // is the backstop that makes that true on screen as well as on the wire.
        if (!AppMode.emergencyBroadcastAllowed(modePrefs())) {
            emergencyBroadcastBanner.visibility = View.GONE
            return
        }
        if (!active) {
            emergencyBroadcastBanner.visibility = View.GONE
            return
        }
        // ⚠ **THE NOTICE MUST NOT CLAIM VIDEO THAT IS NOT GOING OUT.** The override only
        // decides which CONNECTIONS may carry the video url; it cannot conjure a url. With
        // nothing streaming, not one channel gets a link — while a naive notice says "video on
        // all channels". Seen in flight: LIVE stopped and restarted inside a broadcast,
        // and for 5 s the notice claimed video with none going out.
        //
        // Starting a broadcast starts the stream too (see wireEmergencyBroadcastRow); this
        // line is the BACKSTOP for the cases that cannot cover: the pilot stops LIVE, the
        // media server refuses, the retries run out, the encoder stalls, a retry is in progress.
        // ⚠ NOT a link that dies SILENTLY (a hotspot that loses its backhaul): neither client
        // detects that yet, so the stream still reads live (a known limitation). It is
        // read on the HUD tick, so it follows the stream rather than the button press.
        // A running stream is not enough: with the active server's TAK advertisement Off, no
        // url is published and no channel gets a link.
        val streaming = VideoStreamerHolder.isRunning && TakBridgeHolder.videoUrlOrNull() != null
        emergencyBroadcastBanner.text = if (streaming) {
            "EMERGENCY BROADCAST — video on all channels — ${remainingText(expiresAtEpochMs)}"
        } else {
            "EMERGENCY BROADCAST — NO VIDEO STREAM — ${remainingText(expiresAtEpochMs)}"
        }
        emergencyBroadcastBanner.setTextColor(androidx.core.content.ContextCompat.getColor(
            applicationContext,
            if (streaming) R.color.tp_state_emergency else R.color.tp_state_caution))
        emergencyBroadcastBanner.visibility = View.VISIBLE
    }

    /** mm:ss until [expiresAtEpochMs], never negative. */
    private fun remainingText(expiresAtEpochMs: Long): String {
        val remainingSec = ((expiresAtEpochMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0)
        return "%d:%02d".format(remainingSec / 60, remainingSec % 60)
    }

    /**
     * The Emergency Broadcast rows of the LIVE long-press menu. Shown only when an
     * Elevated account (video channel) is configured — with no split there is nothing to
     * override. No confirm step (design decision): the long-press and a button that says
     * what it does are the friction. While one runs, the flight-screen notice is the control
     * (tap renews, touch-and-hold stops), and the same two actions are offered here.
     */
    /**
     * True while a screen-capture consent request was raised BY an Emergency Broadcast rather
     * than by the LIVE pill. A denial on that path has to take the override back down with it —
     * see [onActivityResult].
     */
    private var projectionRequestedForEmergency = false

    /**
     * Makes sure a stream is running, or on its way, before an Emergency Broadcast starts.
     *
     * @return true if the broadcast may start. False means it must NOT: the pilot has been told
     * why, and starting a 15-minute authorisation that can never advertise anything is exactly
     * the false affirmative this exists to prevent.
     */
    private fun startStreamForEmergencyBroadcast(): Boolean {
        val p = getSharedPreferences("takpilot2_tak", MODE_PRIVATE)
        // With the active server's TAK advertisement Off, no url can reach any channel, so the
        // broadcast would authorise nothing.
        if (!p.getBoolean("video_adv_on", true)) {
            AppLog.w(TAG, "Emergency Broadcast refused — the TAK advertisement is Off")
            showNotice("The TAK advertisement is Off in Video Servers", refused = true)
            return false
        }
        // isActive is honest: a stream that gave up is dropped from the
        // holder, so this means streaming, connecting or retrying — not a dead stream.
        if (VideoStreamerHolder.isActive) return true

        // The same guard the LIVE pill applies. A broadcast cannot fix an unconfigured video
        // server, so it must not pretend to.
        if ((p.getString("video_host", "") ?: "").isEmpty() ||
            (p.getString("video_streamid", "") ?: "").isEmpty()
        ) {
            AppLog.w(TAG, "Emergency Broadcast refused — video server not configured")
            showNotice("Set up the video server in Pre-Flight Setup first", refused = true)
            return false
        }

        // ⚠ THE OVERRIDE STARTS NOW AND THE STREAM FOLLOWS, deliberately. The pilot pressed a
        // button in an incident; the override is an AUTHORISATION and does not have to wait on
        // a socket. The core reads the url at every send, so the link starts going out the
        // moment the stream has one — and until then the notice says NO VIDEO STREAM.
        AppLog.i(TAG, "Emergency Broadcast — starting the screen stream with it")
        projectionRequestedForEmergency = true
        val mpm = getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE)
            as android.media.projection.MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
        return true
    }

    private fun wireEmergencyBroadcastRow(view: View, dialog: AlertDialog) {
        val tm = TakManager.getInstance()
        // ⚠ EXPLICIT, NOT INHERITED. In Basic the split is already gone, so the line below
        // would hide these rows anyway — but that is a SIDE EFFECT of another subsystem's
        // state, and an ordering slip that left the split standing would leave a live Start
        // control on a build that has no second audience to broadcast to.
        if (!AppMode.emergencyBroadcastAllowed(modePrefs())) return
        if (!tm.isVideoChannelConfigured()) return // the rows stay GONE
        val caption = view.findViewById<TextView>(R.id.videoEmergencyCaption)
        val start = view.findViewById<android.widget.Button>(R.id.videoEmergencyButton)
        val stop = view.findViewById<android.widget.Button>(R.id.videoEmergencyStopButton)
        val active = tm.isEmergencyBroadcastActive()
        caption.text = if (active) {
            "Emergency Broadcast is running — ${remainingText(tm.emergencyBroadcastExpiresAtEpochMs())} left."
        } else {
            "Emergency Broadcast: every channel gets the aircraft's video link for 15 minutes. " +
                "It starts the video stream if it is not running. The aircraft marker carries " +
                "the link, so the aircraft must be reporting its position."
        }
        start.text = if (active) "Renew for 15 min" else "Start Emergency Broadcast (15 min)"
        caption.visibility = View.VISIBLE
        start.visibility = View.VISIBLE
        stop.visibility = if (active) View.VISIBLE else View.GONE
        start.setOnClickListener {
            AppLog.i(TAG, "LIVE menu: Emergency Broadcast ${if (active) "renew" else "start"}")
            // ⚠ A BROADCAST WITHOUT A STREAM ADVERTISES NOTHING.
            // Starting one therefore starts the stream. A renew does not — a running broadcast
            // already has whatever stream it has, and re-requesting a projection mid-incident
            // would put a system dialog over the flight screen for no gain.
            if (!active && !startStreamForEmergencyBroadcast()) {
                // Refused, and the pilot has been told why. The override does NOT start: a
                // 15-minute authorisation that can never advertise anything is the fault this
                // exists to remove.
                dialog.dismiss()
                return@setOnClickListener
            }
            tm.startOrRenewEmergencyBroadcast()
            dialog.dismiss()
        }
        stop.setOnClickListener {
            AppLog.i(TAG, "LIVE menu: Emergency Broadcast stop")
            tm.cancelEmergencyBroadcast()
            dialog.dismiss()
        }
    }

    /**
     * Gimbal look angle, coloured as a marker-accuracy cue. Ground error scales as
     * 1/sin²(pitch), so a shallow look angle is a real accuracy warning, not decoration, and
     * nothing else on screen tells the pilot that. Shares [CrosshairView.accuracyColorFor] with
     * the reticle ring so the number and the ring can never disagree about the state.
     *
     * SIGN CONVENTION, settled on hardware: Autel reports pitch DOWN POSITIVE (opposite to
     * the DJI SDK), and the bridge negates
     * it at ingest so everything downstream — this readout, the ring, the SPI look-point, the AR
     * overlay and the CoT pitch sent to TAK — shares one convention (down = negative).
     *
     * So the pitch arriving here is ALREADY normalised. Do not add another sign here; that would
     * put this cue out of step with the SPI math.
     */
    /**
     * Why a marker may NOT be placed right now, or null if it may.
     *
     * Returns a REASON rather than a boolean so the pilot is told which rule stopped them.
     * "The app did nothing" is the failure mode that makes people press harder and then stop
     * trusting the control.
     *
     * Two independent rules, both about whether a computed ground point means anything:
     *
     *  1. **Look angle.** Asks [CrosshairView.accuracyColorFor] — the SAME call that tints the
     *     reticle — so "red reticle" and "drop refused" can never disagree. A separate threshold
     *     here would eventually drift and the pilot would see a red reticle accept a drop.
     *  2. **Height above ground.** Below [MIN_DROP_AGL_FT] the geometry is worthless: ground
     *     range is height / tan(pitch), so as height goes to zero the solved point collapses
     *     onto the aircraft's own position no matter where the camera looks. A marker placed on
     *     take-off or during landing would land on the pilot, and it would look deliberate to
     *     everyone receiving it.
     *
     * Uses the same AGL the HUD shows — terrain-corrected where DTED covers the aircraft,
     * otherwise height above the take-off point — so the number the pilot reads is the number
     * being judged.
     */
    private fun dropRefusalReason(): String? {
        val hud = TakBridgeHolder.hud() ?: return "waiting on GPS + gimbal"
        hud.gimbalPitch ?: return "waiting on GPS + gimbal"
        // Judged on the pitch the drop is SOLVED with — the gimbal's plus the aim offset — not
        // the raw reading: a +2° offset would let a raw -10.5° pass the gate
        // while the solve used -8.5°, inside the zone the gate exists to refuse.
        val pitch = TakBridgeHolder.cameraPose()?.pitchDeg ?: return "waiting on GPS + gimbal"
        // The pose must be fresh, and the picture must be the one the crosshair is on:
        // a drop is built from the LIVE pose, and the pilot aimed on the picture.
        if (PictureClock.telemetryStale()) return "no fresh aircraft data — check the link"
        if (PictureClock.pictureFrozen()) return "the video is frozen — wait for the picture"

        val aglFt = Units.metersToFeet(TerrainAgl.reading(this, hud).meters)
        if (aglFt < MIN_DROP_AGL_FT) {
            return "too low — climb above ${MIN_DROP_AGL_FT.toInt()} ft AGL to place a marker"
        }

        val dtedAvailable = hud.hasFix &&
            DtedIndex.elevationAt(this, hud.lat, hud.lon) != null
        if (CrosshairView.accuracyColorFor(this, pitch, dtedAvailable) ==
            CrosshairView.accuracyPoorColor(this)) {
            return "look angle too shallow — tilt the gimbal down"
        }
        return null
    }

    /** True when a marker must not be placed. Kept as a predicate for the call sites that only
     *  need the yes/no; the reason itself comes from [dropRefusalReason]. */
    private fun aimTooPoorToDrop(): Boolean = dropRefusalReason() != null

    /** Shared refusal, so every drop route gives the pilot the same — and specific — reason. */
    private fun refuseDropForAim() {
        val why = dropRefusalReason() ?: return
        AppLog.w(TAG, "marker drop refused — $why")
        showNotice("Cannot place the marker. $why", refused = true)
    }

    /**
     * RTH altitude, as reported BY THE AIRCRAFT.
     *
     * Never the Pre-Flight value. Two sorties have been flown believing RTH was
     * set to 50 ft: the write had been rejected as out-of-range, the aircraft kept a different
     * value, and nothing anywhere said so. Echoing the requested number on the flight screen
     * would have reproduced that failure in the one place it matters most.
     *
     * Grey "RTH --" until the aircraft answers. Unknown must look unknown.
     */
    /**
     * Draws the exterior-lights button from the AIRCRAFT's reported lamp state.
     *
     * Plain bulb = lit, slashed bulb = dark, matching the IR button's convention that the icon
     * shows the state of the hardware rather than what the next tap would do.
     *
     * ⚠ **THE PILL COLOUR IS THE STATE, AND THE GLYPH ONLY AGREES WITH IT** (design decision).
     * This button sits in the actions capsule beside AR and IR, and those two say ON with a
     * green pill. A glyph alone would give three toggles in one capsule two conventions, and
     * the odd one out would be the only one that reports a state of the AIRCRAFT. Green means
     * the same thing across all three, at a glance, from across the screen.
     *
     * The third state is the one AR and IR do not have. The lamps are read BACK from the
     * aircraft, thus "we have not been told" is real and is AMBER — never collapsed into off,
     * which is the rule for every state readout in this application. A pilot must be able to
     * tell "confirmed lit" from "we do not know", because only one of those is safe to act on
     * at night.
     *
     * ⚠ **DO NOT SHOW UNKNOWN WITH A 45 % ALPHA.** Dimming reads as
     * disabled, and this button genuinely disables itself — with no colour change — while a
     * write is in flight. One appearance for two meanings. See bg_pill_unknown.
     */
    /**
     * Draws the camera's media mode from what the CAMERA reports, never from what was asked.
     *
     * ⚠ **THE HARDWARE SHUTTER LEAVES THE CAMERA IN SINGLE AND NOTHING ELSE SAYS SO**
     * (confirmed in the log). A press of the controller's shutter puts the camera in
     * SINGLE and it stays there until something asks for VIDEO — in practice REC. The pilot
     * sees the picture change shape and field of view, 4:3 against 16:9, with no explanation.
     * This readout is the explanation, and it costs nothing: the mode is already on the
     * camera's ~2 Hz status push. See [AutelProductHolder.mediaMode].
     *
     * ⚠ It is also why a lens change can come back INCONCLUSIVE — a 4:3 frame is neither lens
     * shape, and that has been seen in flight. A pilot who can see
     * PHOTO on the screen can see why.
     *
     * ⚠ **UNKNOWN IS ITS OWN STATE, IN AMBER.** Same rule as the RTH altitude and the exterior
     * lights: "we have not been told" must never be drawn as one of the real answers.
     *
     * The other MediaMode values the SDK defines are shown by NAME rather than mapped onto one
     * of the two this application knows. Autel Explorer can leave the camera in one of them,
     * and inventing a label for a mode we do not handle is how a readout starts lying.
     *
     * ⚠ **AND IT ANNOUNCES THE CHANGE.** The readout answers "what is it
     * now"; a pilot busy flying needs "it just moved", because the mode moves without them
     * asking. The notice fires on a CHANGE only, and never on the first answer — learning the
     * mode at connect is not a change, and announcing it there would fire on every connect for
     * something the pilot did not do.
     */
    /**
     * The pilot tapped a half of the media-mode pill. Refused while recording — the camera
     * will not change what it is writing, and the record path owns that state. Otherwise one
     * write; the camera's 2 Hz push moves the highlight, and [renderMediaMode] announces the
     * change and restores the zoom exactly as it does for a hardware-button change.
     */
    private fun onMediaModeRequested(wanted: MediaModeView.Mode) {
        val cam = AutelProductHolder.camera
        if (cam == null) { toast("The camera is not connected."); return }
        if (AutelProductHolder.isRecording) {
            AppLog.w(TAG, "media mode $wanted refused — the camera is recording")
            showNotice("Stop the recording to change the camera mode", refused = true)
            return
        }
        val target = if (wanted == MediaModeView.Mode.VIDEO) MediaMode.VIDEO else MediaMode.SINGLE
        if (AutelProductHolder.mediaMode == target) return
        AppLog.i(TAG, "tap: media mode -> $target")
        runCatching { cam.setMediaMode(target, camCb("setMediaMode($target)") {}) }
            .onFailure { AppLog.w(TAG, "setMediaMode($target) threw: ${it.message}") }
    }

    private fun renderMediaMode() {
        if (!::fpvMediaMode.isInitialized) return
        val mode = AutelProductHolder.mediaMode
        when (mode) {
            null -> fpvMediaMode.setMode(null)
            MediaMode.VIDEO -> fpvMediaMode.setMode(MediaModeView.Mode.VIDEO)
            MediaMode.SINGLE -> fpvMediaMode.setMode(MediaModeView.Mode.PHOTO)
            // A mode this application does not handle — Autel Explorer can leave the camera in
            // one — is shown by NAME rather than mapped onto one of the two it knows. Inventing
            // a glyph for a mode the code does not handle is how a readout starts lying.
            else -> fpvMediaMode.setMode(null, unhandledName = mode.name)
        }
        // ⚠ ANNOUNCE THE CHANGE, NEVER THE STATE. The mode moves without
        // the pilot asking — the hardware shutter puts the camera in stills and leaves it
        // there, and the picture changes shape and field of view with it. The readout above
        // answers "what is it now"; this answers "it just moved", which is the part a pilot
        // busy flying will otherwise miss.
        //
        // ⚠ NOT ON THE FIRST ANSWER. Learning the mode at connect is not a change, and a notice
        // there would fire on every connect for something the pilot did not do. Null is its own
        // state and the readout already carries it.
        if (mode != null && announcedMediaMode != null && mode != announcedMediaMode) {
            // The camera throws the zoom away on this change — see restoreZoomAfterModeChange.
            restoreZoomAfterModeChange()
            showNotice(when (mode) {
                MediaMode.VIDEO -> "The camera is now in video"
                MediaMode.SINGLE -> "The camera is now in photo"
                else -> "The camera is now in ${mode.name}"
            })
        }
        if (mode != null) announcedMediaMode = mode
        // The record pill ALWAYS reads REC (design decision): it records from any mode,
        // switching the camera to video itself — see onRecordToggleTapped. It never takes a
        // stills-mode shutter shape.
        if (::recordToggle.isInitialized) recordToggle.setPhotoMode(false)
    }

    /** The pending zoom restore, so a second mode change replaces the first. */
    private var zoomRestore: Runnable? = null

    /**
     * Puts the pilot's zoom back after the CAMERA has thrown it away on a mode change.
     *
     * ⚠ **THE CAMERA RESETS THE ZOOM TO 1x WHEN IT CHANGES MEDIA MODE** (measured in
     * flight):
     *
     *     16:27:12.688  zoomScaleRaw=300 focal=1.42 mode=VIDEO
     *     16:27:15.938  controller button event: TAKEN_PHOTO
     *     16:27:16.198  zoomScaleRaw=100 focal=0.47 mode=SINGLE
     *
     * The focal length moves with it, so the picture really does go wide — it is not a
     * reporting artefact. And the camera reports the reset honestly, which is what makes
     * writing the zoom back the right answer rather than a guess.
     *
     * ⚠ **THE PILOT'S INTENT IS [pendingZoomRaw], AND IT MUST BE READ BEFORE ANYTHING RE-SEEDS
     * IT.** Nothing in this application re-seeds on a mode change, which is why the
     * pill would go on claiming 3X without this — and it is also why the pilot's choice is
     * still there to restore. Re-seeding first would follow the camera to 1x and lose the very thing this is
     * for.
     *
     * ⚠ A WRITE THE PILOT DID NOT ASK FOR, ONCE PER MODE CHANGE. It is bounded — one write per
     * change, never a timer — and it goes to the CAMERA channel, not the fly-controller channel,
     * where unrequested writes are never made. It restores a setting the pilot chose and the camera discarded;
     * it does not invent one.
     *
     * Waits [MODE_SWITCH_SETTLE_MS] after the camera's own report of the new mode, for the
     * reason that constant exists: this camera acknowledges a change before it can act on one.
     * [applyZoomRaw]'s own check then says in the log whether it took. Measured in flight:
     * the write was accepted 94 ms after being issued, both directions, no retry.
     *
     * ⚠ **THE RESTORE LANDS AFTER THE CAPTURE, AND THAT IS FINE. DO NOT "FIX" IT.**
     * The shutter fires, the camera drops to 1x, and this restores a second or so
     * later — so the STILL on the card is taken wide while the live view comes back zoomed:
     *
     *     16:55:08.006  TAKEN_PHOTO
     *     16:55:08.191  zoomScaleRaw=100 mode=SINGLE
     *     16:55:09.439  zoomScaleRaw=200 mode=SINGLE   <- our restore
     *     16:55:10.084  PHOTO_TAKEN_DONE (MAX_0106.JPG)
     *
     * It does not matter, because this is DIGITAL zoom — a crop of the same sensor read. The
     * wide frame contains everything the zoomed one would and more, and it can be cropped
     * afterwards at no loss. What this restores is the PILOT'S VIEW and the framing they were
     * working to, which is the thing that was actually disrupted.
     *
     * So do not delay the shutter, do not reorder the write, and do not chase the capture. Each
     * of those trades a real risk against a problem that is not one.
     */
    private fun restoreZoomAfterModeChange() {
        val intended = pendingZoomRaw
        if (intended <= ZOOM_RAW_MIN) return          // nothing to put back
        zoomRestore?.let { handler.removeCallbacks(it) }
        val restore = Runnable {
            zoomRestore = null
            if (AutelProductHolder.xt706 == null) return@Runnable
            AppLog.i(TAG, "restoring ${zoomLabel(intended)} — the camera resets the zoom " +
                "when it changes media mode")
            applyZoomRaw(intended)
        }
        zoomRestore = restore
        handler.postDelayed(restore, MODE_SWITCH_SETTLE_MS)
    }

    private fun renderLightsButton() {
        if (!::lightsButton.isInitialized) return
        val dark = AutelLights.isDark
        lightsButton.setImageResource(
            if (dark == true) R.drawable.ic_led_off else R.drawable.ic_led_on)
        lightsButton.setBackgroundResource(when (dark) {
            false -> R.drawable.bg_pill_active   // lit
            true -> R.drawable.bg_zoom_pill      // dark
            null -> R.drawable.bg_pill_unknown   // the aircraft has not answered
        })
        // The GLYPH takes the pill's colour, the same way the AR and IR pills tint their label
        // A green frame around a white bulb reads as a frame that
        // happens to be green; a green bulb reads as a lamp that is on.
        //
        // ⚠ TINT ONLY THE LIT BULB, NEVER THE SLASHED ONE. ic_led_off draws its slash TWICE,
        // black under white, so that it reads as a gap cut through the glass rather than as a
        // second line — see that file. A tint is applied to every path of a vector, thus it
        // would flatten both passes to one colour and the slash would disappear into the bulb.
        // This is safe as written because the slashed bulb is shown ONLY for "dark", which is
        // the one state that takes the colourless idle pill. Keep those two facts together: if
        // the dark state ever gains a colour, the icon has to gain a second drawable first.
        lightsButton.imageTintList = when (dark) {
            false -> androidx.core.content.ContextCompat.getColorStateList(this, R.color.tp_state_go)
            null -> androidx.core.content.ContextCompat.getColorStateList(this, R.color.tp_state_unknown)
            true -> null
        }
        lightsButton.contentDescription = when (dark) {
            true -> "Exterior lights are off"
            false -> "Exterior lights are on"
            null -> "Exterior lights, state unknown"
        }
    }

    private fun updateRthAltitude() {
        val known = FlightLimitsController.aircraftReturnHeightM != null
        fpvRthAltitude.text = FlightLimitsController.rthHudLabel()
        fpvRthAltitude.setTextColor(androidx.core.content.ContextCompat.getColor(applicationContext,
            if (known) R.color.tp_text_secondary else R.color.tp_state_unknown))
    }

    /**
     * BVLOS antenna aim: the controller's antennas are directional,
     * and during authorized BVLOS work the pilot cannot see the aircraft to face it.
     *
     * The bearing is CONTROLLER→AIRCRAFT from the pilot's own GPS fix — home point would
     * be wrong the moment the pilot walks. The bottom-centre arc ([AntennaAimView]) carries
     * the whole story: pilot dot at the centre, aircraft marker riding the arc, marker at
     * the top = antennas on target. No text fallback (design decision): a bearing number
     * without an on-screen compass gives the pilot nothing to act on, and the Smart Controller's
     * rotation-vector sensor is verified present, so the arc is the indicator.
     */
    private fun updateAntennaAim(hud: AutelTakBridge.Hud?) {
        val fix = OperatorLocation.latest
        val facing = ControllerCompass.azimuthTrueDeg()
        if (hud == null || !hud.hasFix || fix == null || facing == null) {
            fpvAntennaArc.setRelativeBearing(null)
            return
        }
        val bearing = CameraSlantPoint.initialBearingDeg(
            fix.latitude, fix.longitude, hud.lat, hud.lon)
        // Signed relative turn, -180..+180: which way and how far the pilot must rotate.
        fpvAntennaArc.setRelativeBearing(((bearing - facing + 540.0) % 360.0) - 180.0)
    }

    private fun updateGimbalPitch(hud: AutelTakBridge.Hud?) {
        val pitch = hud?.gimbalPitch
        // Whether a marker dropped RIGHT NOW would get CameraSlantPoint's terrain-corrected
        // solve — DTED coverage at the aircraft's OWN position, not just "any DTED loaded".
        val dtedAvailable = hud != null && hud.hasFix &&
            DtedIndex.elevationAt(this, hud.lat, hud.lon) != null
        // The colour judges the pitch a drop would be solved with; the text below still
        // reads what the gimbal reports.
        val solvePitch = TakBridgeHolder.cameraPose()?.pitchDeg ?: pitch
        crosshairView.setGimbalPitch(solvePitch, dtedAvailable)
        // Look-point distance and bearing at the reticle's lower-right.
        // Null — and no text — when the camera is at/above the horizon or
        // telemetry is not ready; Units.distance keeps the range in the HUD's imperial
        // convention (ft, then mi). The bearing is the camera's own true bearing, the same
        // model the SPI and a marker drop use, so the reticle cannot disagree with them.
        crosshairView.setRangeText(
            TakBridgeHolder.lookRangeMeters()?.let { range ->
                val brg = TakBridgeHolder.cameraPose()?.bearingDeg
                if (brg == null) Units.distance(range)
                else "%s  %03.0f°T".format(Units.distance(range), brg)
            })
        if (pitch == null) {
            fpvGimbalPitch.text = "GIMBAL —"
            fpvGimbalPitch.setTextColor(androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))
            return
        }
        // Sign dropped in favour of an explicit DOWN/UP word: "-20" reads as a negative number
        // rather than as a look angle, and down is the only direction that matters for drops.
        fpvGimbalPitch.text = when {
            pitch <= -1.0 -> "GIMBAL %.0f° DOWN".format(-pitch)
            pitch >= 1.0 -> "GIMBAL %.0f° UP".format(pitch)
            else -> "GIMBAL LEVEL"
        }
        fpvGimbalPitch.setTextColor(CrosshairView.accuracyColorFor(this, solvePitch ?: pitch, dtedAvailable))
    }

    /**
     * FAA UASFM advisory ceiling for the cell the aircraft is over. Advisory only — nothing here
     * is wired to the aircraft's flight limits, by design.
     *
     * Judged against the terrain-corrected AGL when DTED allows (see [TerrainAgl]): a UASFM
     * ceiling is height above the ground *under the aircraft*, so comparing it to a
     * takeoff-relative altitude would misjudge the moment the aircraft leaves the elevation it
     * launched from. Without coverage the comparison falls back to the uncorrected figure and
     * marks itself `~` so the pilot can see the warning is only as good as flat ground.
     */
    private fun updateFaaCeiling(hud: AutelTakBridge.Hud?, agl: TerrainAgl.Reading) {
        // No UASFM data imported at all. Hiding the line entirely would leave the
        // pilot unable to tell "no ceiling data loaded" from "this build has no FAA feature" —
        // a blank space says nothing. Dashes in the slot the ceiling would occupy say the
        // number is unknown.
        if (!UasfmIndex.hasCoverage(this)) {
            showFaaUnknown()
            return
        }
        if (hud == null || !hud.hasFix) {
            fpvFaaCeiling.visibility = View.VISIBLE
            fpvFaaCeiling.text = "FAA — no fix"
            fpvFaaCeiling.setTextColor(androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))
            return
        }

        // Cell lookup is cached per grid cell, not per tick: it's a HashMap hit plus a bounds
        // check, but it runs on the HUD tick with video decoding alongside it.
        val row = UasfmIndex.gridRowFor(hud.lat)
        val col = UasfmIndex.gridColFor(hud.lon)
        if (row != lastFaaGridRow || col != lastFaaGridCol) {
            lastFaaGridRow = row
            lastFaaGridCol = col
            cachedFaaCeilingFt = UasfmIndex.ceilingFtAt(this, hud.lat, hud.lon)
            cachedFaaWithinDownloadedArea = UasfmIndex.isWithinDownloadedArea(this, hud.lat, hud.lon)
            AppLog.v(TAG, "FAA cell ($row,$col): ceiling=${cachedFaaCeilingFt ?: "none"} " +
                "withinDownloaded=$cachedFaaWithinDownloadedArea")
        }

        val aglFt = Units.metersToFeet(agl.meters)
        val approx = if (agl.terrainCorrected) "" else "~"
        val ceiling = cachedFaaCeilingFt
        fpvFaaCeiling.visibility = View.VISIBLE
        when {
            // "AGL" spelled out because the readout above shows MSL, and a bare "FAA 200 ft"
            // next to "413 ft MSL" invites reading the ceiling as an MSL figure.
            ceiling != null -> {
                fpvFaaCeiling.text = "FAA $ceiling ft AGL$approx"
                fpvFaaCeiling.setTextColor(
                    if (aglFt > ceiling) androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_btn_danger_dialog) else Color.WHITE
                )
            }
            // Inside what was downloaded but in no cell: the FAA publishes no facility map here,
            // which means uncontrolled airspace and the plain Part 107 ceiling. Grey + labelled
            // so it never reads as "the facility map says 400".
            cachedFaaWithinDownloadedArea -> {
                val part107 = UasfmIndex.PART_107_DEFAULT_CEILING_FT
                fpvFaaCeiling.text = "Class G · $part107 ft AGL$approx"
                fpvFaaCeiling.setTextColor(
                    if (aglFt > part107) androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_btn_danger_dialog) else androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_secondary)
                )
            }
            // Outside the downloaded box entirely — we genuinely do not know. Shown identically
            // to "nothing downloaded" (design decision). Flying OUT of coverage is more
            // surprising than never having had it, which argues for a distinct amber treatment.
            // But the pilot can do exactly the same
            // thing about both — nothing, in the air — so two treatments for one meaning would
            // be noise. Both read as an unknown ceiling.
            else -> showFaaUnknown()
        }
    }

    /** The ceiling is unknown: no data downloaded, or the aircraft is outside what was. Dashes
     *  in the number's own slot, grey — it must not read as a limit of any kind. */
    private fun showFaaUnknown() {
        fpvFaaCeiling.visibility = View.VISIBLE
        fpvFaaCeiling.text = "FAA --- ft AGL"
        fpvFaaCeiling.setTextColor(androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))
    }

    // ---- Markers list (drop-pin long-press) ----

    /**
     * The dropped-markers panel. Rebuilt from [TakDropMarkers.listPins] each time it opens and
     * after every action, so it cannot show a stale list.
     *
     * Deliberately reachable with zero pins: Clear All is still meaningful right after a
     * delete, and a panel that refuses to open when empty just makes the pilot wonder whether
     * the long-press registered.
     */
    /**
     * One row of the marker list. Markers this pilot dropped and markers the team shared appear
     * TOGETHER (design decision) — a pilot looking for "the marker by the north gate" does
     * not care who placed it, and a list that showed only their own would make the shared ones
     * look absent when they were on the map the whole time.
     *
     * The two are not interchangeable, though: only an own marker can be moved, renamed or
     * retyped, because those edit the marker on every other client. A shared one can be removed
     * from this map and RE-SENT — re-broadcasting a received marker is ordinary TAK behaviour
     * and goes out under that marker's own uid and type. [ownPin] being null marks a row shared.
     */
    private data class MarkerRow(
        val label: String,
        val iconRes: Int,
        val ownPin: TakDropMarkers.PinInfo?,
        val sharedUid: String?,
    )

    private fun buildMarkerRows(): List<MarkerRow> {
        val hud = TakBridgeHolder.hud()
        // Range/bearing from the AIRCRAFT to each marker, so the list is orderable by "what's
        // near me" in the air rather than just drop order.
        fun range(lat: Double, lon: Double): String =
            if (hud != null && hud.hasFix) {
                val d = CameraSlantPoint.distanceMeters(hud.lat, hud.lon, lat, lon)
                val b = CameraSlantPoint.initialBearingDeg(hud.lat, hud.lon, lat, lon)
                // Units.distance (not .feet): a marker has no geofence bound the way the
                // aircraft's own position does, so this can run to five digits of feet where
                // miles read better.
                "  ·  %s @ %03.0f°".format(Units.distance(d), b)
            } else ""

        val own = TakDropMarkers.listPins().map {
            MarkerRow("${it.affiliation.label}: ${it.name}${range(it.lat, it.lon)}",
                it.affiliation.res, it, null)
        }
        val shared = TakMapMarkers.listShared().map {
            // "Team:" prefix rather than an affiliation word — the useful distinction in this
            // list is who can edit it, and the affiliation is already carried by the icon.
            MarkerRow("Team: ${it.name}${range(it.lat, it.lon)}",
                TakMapMarkers.milMarkerRes(it.type) ?: R.drawable.marker_unknown, null, it.uid)
        }
        return own + shared
    }

    /**
     * The markers list, with a check box on every row and bulk Delete / Resend.
     *
     * SHORT TAP TICKS THE BOX, LONG PRESS EDITS. The five-action edit menu sits on the long
     * press, so the short tap can be the selection gesture that a check-box list needs.
     *
     * Not an AlertDialog.setAdapter list: that call offers no long-press hook and no
     * room for a check box, thus the list is a custom view — see dialog_markers.xml.
     *
     * The rows are rebuilt in place after an action rather than the dialog being closed and
     * reopened, so a pilot clearing several markers keeps their place.
     */
    private fun onMarkersListTapped() {
        // Themed inflater. A view built with the ACTIVITY's context
        // inherits the activity theme, not the dialog's, and the row label lands white on white.
        val themed = android.view.ContextThemeWrapper(this, R.style.TakDialogTheme)
        val view = android.view.LayoutInflater.from(themed)
            .inflate(R.layout.dialog_markers, null)
        val container = view.findViewById<android.widget.LinearLayout>(R.id.markersContainer)
        val empty = view.findViewById<TextView>(R.id.markersEmpty)
        val resendButton = view.findViewById<android.widget.Button>(R.id.markersResendButton)
        val deleteButton = view.findViewById<android.widget.Button>(R.id.markersDeleteButton)

        // Selection lives only as long as the dialog. It holds the ROW KEY — a pin key for an
        // own marker, a CoT uid for a shared one — because a list position stops being valid
        // the moment a row is deleted underneath it.
        val selected = mutableSetOf<String>()

        fun rowKey(row: MarkerRow): String? = row.ownPin?.key ?: row.sharedUid

        fun refreshButtons() {
            val any = selected.isNotEmpty()
            for (b in listOf(resendButton, deleteButton)) {
                b.isEnabled = any
                b.alpha = if (any) 1f else 0.45f
            }
        }

        fun populate() {
            container.removeAllViews()
            val rows = buildMarkerRows()
            // A key that no longer exists — its marker was deleted — must not stay selected, or
            // the next Delete would act on nothing and the count would lie.
            selected.retainAll(rows.mapNotNull { rowKey(it) }.toSet())
            empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            for (row in rows) {
                val key = rowKey(row) ?: continue
                val item = android.view.LayoutInflater.from(themed)
                    .inflate(R.layout.row_marker_select, container, false)
                val check = item.findViewById<android.widget.CheckBox>(R.id.markerRowCheck)
                item.findViewById<android.widget.ImageView>(R.id.markerRowIcon)
                    .setImageResource(row.iconRes)
                item.findViewById<TextView>(R.id.markerRowLabel).text = row.label
                check.isChecked = selected.contains(key)
                item.setOnClickListener {
                    if (!selected.add(key)) selected.remove(key)
                    check.isChecked = selected.contains(key)
                    refreshButtons()
                }
                item.setOnLongClickListener {
                    if (row.ownPin != null) onMarkerRowTapped(row.ownPin)
                    else onSharedMarkerRowTapped(row)
                    true
                }
                container.addView(item)
            }
            refreshButtons()
        }
        populate()

        val dialog = AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("Markers")
            .setView(view)
            .setNegativeButton("Close", null)
            .setNeutralButton("Clear All") { _, _ -> onClearAllMarkersTapped() }
            .create()

        // NO CONFIRMATION ON BULK DELETE (design decision). It is local-only: there is no
        // delete CoT in this application, thus the markers stay on the server until they go
        // stale and a shared one the team sends again comes straight back. Nothing here reaches
        // another user's screen.
        deleteButton.setOnClickListener {
            val rows = buildMarkerRows().filter { rowKey(it) in selected }
            AppLog.i(TAG, "markers: bulk delete of ${rows.size}")
            for (row in rows) {
                val pin = row.ownPin
                // deleteShared, NOT clearAllShared's path: the per-uid delete also marks the uid
                // hidden, without which the next inbound copy would put the marker straight back.
                if (pin != null) TakDropMarkers.delete(pin.key)
                else row.sharedUid?.let { TakMapMarkers.deleteShared(it) }
            }
            selected.clear()
            populate()
        }

        // SILENT (design decision) — no notice, no toast.
        resendButton.setOnClickListener {
            val rows = buildMarkerRows().filter { rowKey(it) in selected }
            AppLog.i(TAG, "markers: bulk re-send of ${rows.size}")
            for (row in rows) {
                val pin = row.ownPin
                if (pin != null) TakDropMarkers.resend(pin.key)
                else row.sharedUid?.let { TakMapMarkers.resendShared(it) }
            }
        }

        dialog.show()

        // Clear All in red, matching every other destructive control in the app. AlertDialog has
        // no per-button style, so it is tinted after show() — the button does not exist before.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            ?.setTextColor(androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_btn_danger_dialog))
    }

    /**
     * A marker somebody else shared: remove it from this map, or send it again.
     *
     * RE-SENDING A RECEIVED MARKER IS NORMAL — every TAK client can do
     * it, and it is how a marker that went stale on one screen is brought back for the team.
     * It goes out under the marker's OWN uid and OWN CoT type, thus it updates that marker
     * rather than making a second one — see [TakMapMarkers.resendShared].
     *
     * Rename, retype and move are still absent: [TakMapMarkers] has no mutators for them, and
     * editing another user's marker is a larger question than re-broadcasting one.
     */
    private fun onSharedMarkerRowTapped(row: MarkerRow) {
        val uid = row.sharedUid ?: return
        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle(row.label.substringAfter("Team: ").substringBefore("  ·"))
            .setItems(arrayOf("Re-send", "Remove from my map")) { _, index ->
                when (index) {
                    0 -> {
                        AppLog.i(TAG, "shared marker re-send: $uid")
                        TakMapMarkers.resendShared(uid)
                    }
                    1 -> {
                        TakMapMarkers.deleteShared(uid)
                        onMarkersListTapped()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onMarkerRowTapped(pin: TakDropMarkers.PinInfo) {
        val actions = arrayOf("Move to crosshair", "Rename", "Change type", "Re-send", "Delete")
        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle(pin.name)
            .setItems(actions) { _, index ->
                when (index) {
                    0 -> if (aimTooPoorToDrop()) refuseDropForAim() else onMoveMarkerTapped(pin)
                    1 -> onRenameMarkerTapped(pin)
                    2 -> onChangeTypeTapped(pin)
                    3 -> {
                        AppLog.i(TAG, "marker re-send: ${pin.key}")
                        TakDropMarkers.resend(pin.key)
                    }
                    4 -> onDeleteMarkerTapped(pin)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onMoveMarkerTapped(pin: TakDropMarkers.PinInfo) {
        val look = TakBridgeHolder.lookPoint()
        if (look == null) {
            AppLog.w(TAG, "marker move refused — no look-point")
            toast("Cannot move the marker yet. Wait for GPS and the gimbal.")
            return
        }
        val (lat, lon, elev) = look
        AppLog.i(TAG, "marker move: ${pin.key} -> $lat,$lon elev=$elev")
        TakDropMarkers.moveToLookPoint(pin.key, lat, lon, elev)
    }

    private fun onRenameMarkerTapped(pin: TakDropMarkers.PinInfo) {
        // Colours set explicitly: a setView() child is built with the ACTIVITY's context and
        // does NOT inherit TakDialogTheme, so it would otherwise render near-black on the dark
        // card. Same reason as DataSyncActivity's dialogEditText().
        val field = android.widget.EditText(this).apply {
            setText(pin.name)
            setSelection(pin.name.length)
            setTextColor(androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_primary))
            setHintTextColor(androidx.core.content.ContextCompat.getColor(applicationContext, R.color.tp_text_hint_dialog))
        }
        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("Rename Marker")
            .setView(field)
            .setPositiveButton("Rename") { _, _ ->
                TakDropMarkers.rename(pin.key, field.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onChangeTypeTapped(pin: TakDropMarkers.PinInfo) {
        val affs = TakDropMarkers.Affiliation.values()
        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("Change Type")
            .setAdapter(iconRowAdapter(affiliationRows(affs))) { _, i ->
                TakDropMarkers.changeType(pin.key, affs[i])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onDeleteMarkerTapped(pin: TakDropMarkers.PinInfo) {
        AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
            .setTitle("Delete Marker")
            .setMessage("Remove \"${pin.name}\" from your map? This is local-only — it stays " +
                "on the TAK server until it goes stale (3 days) and may still show on " +
                "other clients until then.")
            .setPositiveButton("Delete") { _, _ ->
                AppLog.i(TAG, "marker delete: ${pin.key}")
                TakDropMarkers.delete(pin.key)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Clears BOTH sets — the markers this pilot dropped and the markers the team shared.
     * Clearing only the pilot's own would leave a "cleared" map still carrying every shared
     * marker.
     *
     * The message states both counts before the pilot commits, because the two have different
     * consequences: an own marker stays on the team's screens until it goes stale, while a shared
     * one is only being removed from this aircraft.
     */
    private fun onClearAllMarkersTapped() {
        val ownCount = TakDropMarkers.listPins().size
        val sharedCount = TakMapMarkers.listShared().size
        AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
            .setTitle("Clear All Markers")
            .setMessage(
                "Remove all markers from this map?\n\n" +
                    "· $ownCount that you dropped\n" +
                    "· $sharedCount that your team shared\n\n" +
                    "This changes this aircraft only. Your own markers stay on the screens of " +
                    "your team until they go stale, 3 days. A marker that your team " +
                    "shares again will come back.")
            .setPositiveButton("Clear All Markers") { _, _ ->
                AppLog.i(TAG, "markers: clear all confirmed ($ownCount own, $sharedCount shared)")
                TakDropMarkers.clearAll()
                TakMapMarkers.clearAllShared()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Drop-pin UI ----

    /**
     * Adapter for the affiliation pickers: MIL-STD shape + name, not name alone.
     *
     * Not `AlertDialog.setItems()`, which renders text ONLY — so
     * `Affiliation.res` would never be drawn, and the pilot would choose an affiliation by
     * reading a word. The shape and colour are the whole point of these symbols; a pilot
     * scanning under time pressure recognises a red diamond faster than the string "Hostile",
     * and the icon here is the same drawable that ends up on the map, so what they pick is
     * literally what they get.
     */
    private fun iconRowAdapter(rows: List<Pair<Int, String>>):
        android.widget.ArrayAdapter<Pair<Int, String>> {
        // Themed context, so the row inflates against the dialog's dark background rather than
        // the activity's — otherwise the white label can land on white.
        val themed = android.view.ContextThemeWrapper(this, R.style.TakDialogTheme)
        return object : android.widget.ArrayAdapter<Pair<Int, String>>(
            themed, R.layout.item_marker_affiliation, R.id.affiliationLabel, rows
        ) {
            override fun getView(position: Int, convertView: android.view.View?,
                                 parent: android.view.ViewGroup): android.view.View {
                val row = super.getView(position, convertView, parent)
                val (iconRes, label) = getItem(position)!!
                row.findViewById<ImageView>(R.id.affiliationIcon).setImageResource(iconRes)
                row.findViewById<android.widget.TextView>(R.id.affiliationLabel).text = label
                return row
            }
        }
    }

    private fun affiliationRows(affs: Array<TakDropMarkers.Affiliation>) =
        affs.map { it.res to it.label }

    /**
     * The create-a-marker menu: pick an affiliation, then [then] places it.
     *
     * Opens even when the drop gate is closed, and says so in the title. Hiding the menu
     * whenever a marker could not be placed would make the whole create flow vanish during the
     * states it is most often in — on the ground, or at a shallow gimbal angle — and leave the pilot
     * with no way to see what the choices even were.
     */
    private fun pickAffiliationThen(then: (TakDropMarkers.Affiliation) -> Unit) {
        val affs = TakDropMarkers.Affiliation.values()
        val blocked = dropRefusalReason()
        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle(if (blocked == null) "Marker affiliation" else "Cannot place the marker: $blocked")
            .setAdapter(iconRowAdapter(affiliationRows(affs))) { _, which ->
                AppLog.v(TAG, "affiliation chosen: ${affs[which].label}")
                then(affs[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- TakDropMarkers.Ui ----

    override fun askSend(affiliationLabel: String, onChoice: (Boolean) -> Unit) {
        runOnUiThread {
            AlertDialog.Builder(this, R.style.TakDialogTheme)
                .setTitle("$affiliationLabel marker placed")
                .setMessage("Send this marker to the TAK server?")
                .setCancelable(false)
                .setPositiveButton("Send to TAK") { _, _ -> AppLog.i(TAG, "pin send: yes ($affiliationLabel)"); onChoice(true) }
                .setNegativeButton("Do not Send") { _, _ -> AppLog.v(TAG, "pin send: no ($affiliationLabel)"); onChoice(false) }
                .show()
        }
    }

    override fun pinMenu(title: String, onSend: () -> Unit, onDelete: () -> Unit, sendLabel: String?) {
        runOnUiThread {
            val b = AlertDialog.Builder(this, R.style.TakDialogTheme).setTitle(title)
            if (sendLabel != null) b.setPositiveButton(sendLabel) { _, _ -> AppLog.i(TAG, "pin menu: $sendLabel ($title)"); onSend() }
            b.setNegativeButton("Delete") { _, _ -> AppLog.i(TAG, "pin menu: delete ($title)"); onDelete() }
            b.setNeutralButton("Cancel", null)
            b.show()
        }
    }

    override fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        /** The at-connect settling time the camera and fly-controller calls all need. Matches
         *  the delay AutelProductHolder uses for the same reason. */
        private const val LAMP_READ_DELAY_MS = 4500L

        /** How long the aircraft is given to leave the go-home mode before the pilot is told
         *  the cancel did not take. Two telemetry pushes at the bridge's 2 Hz, so a mode that
         *  has changed has certainly been reported. */
        private const val RTH_CANCEL_CONFIRM_MS = 1500L

        private const val TAG = "FlightActivity"

        /** Which SDK custom button drives the quick marker: "A" or "B". Set from the
         *  logged event after a press — see installHardwareButtonListener. */
        /** Controller custom buttons, in the SDK's vocabulary. It has no notion of the
         *  physical "C1"/"C2" labels — the mapping below was confirmed on hardware
         *  by logging the events a press actually emits. */
        // C2 drives BOTH marker actions: a short press for the quick marker, a long press for a
        // new stationary Unknown marker. The name stays as it is — it is still the marker button.
        private const val QUICK_MARKER_BUTTON = "B"   // physical C2
        // C1 drives BOTH thermal actions: a short press changes the camera between visible and
        // thermal, a long press changes the thermal colours. Zoom is on the pill and the right
        // zoom rocker.
        private const val IR_BUTTON = "A"             // physical C1

        /** Minimum height above ground for a marker drop, feet. Below this the slant
         *  solve degenerates onto the aircraft's own position — see dropRefusalReason. */
        /**
         * Relative text sizes inside the telemetry readout.
         *
         * The height's FIGURE is large and its unit small, so the eye lands on the number a
         * pilot checks constantly instead of reading four same-sized lines to find it. Reference
         * figures shrink because they are looked up only on request.
         *
         * ⚠ THESE ARE A HEIGHT BUDGET, NOT A TASTE. The HUD column ends in the mini-map and
         * overflow clips that map SILENTLY — see flight_map_size in dimens.xml. The block is
         * four lines; at these values it grows by about 0.4 of a line, because the unit and
         * the reference figures give back most of what the figure takes. Raising the figure
         * without lowering something else spends budget a smaller screen does not have.
         * Re-measure with `dumpsys activity top` after any change here.
         */
        private const val HEIGHT_FIGURE_SCALE = 1.55f
        private const val HEIGHT_UNIT_SCALE = 0.80f
        private const val REFERENCE_SCALE = 0.85f

        private const val MIN_DROP_AGL_FT = 25.0

        /** 24-hour clock with seconds, for the HUD. Held as one instance rather than
         *  built per tick — updateHud runs twice a second for the whole flight. */
        private val clockFormat =
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

        /** Pause after a media-mode switch before commanding record — see [startRecordVerified].
         *  The failing case measured 2ms; this is deliberately far clear of it rather than tuned
         *  to the edge, since the cost of waiting is imperceptible and the cost of being early is
         *  a recording that silently never happens. */
        private const val MODE_SWITCH_SETTLE_MS = 800L

        /**
         * The settle for the HARDWARE record button's path, and it is NOT
         * [MODE_SWITCH_SETTLE_MS] — measured in flight.
         *
         * The pill's 800ms is counted from the camera's own ack of `setMediaMode`. This path has
         * no such ack to count from: the CAMERA does the mode change itself on the button press,
         * and it is slower to become ready than it is to say it has changed.
         *
         * ⚠ **THE MODE FLAG IS NOT THE READINESS SIGNAL, SO WAITING FOR IT WOULD NOT HELP.**
         * Two measured presses read `mode=VIDEO` within 180ms — and a start issued at 800ms,
         * more than six hundred milliseconds AFTER that, was still acked with OK and silently
         * ignored. Both times. The retry at about 2000ms was accepted, both times.
         *
         * So this is 2000: the value observed working, not a value between two guesses. The
         * watchdog in [startRecordVerified] stays behind it — this makes the first attempt the
         * one that works rather than replacing the net that proves it did.
         */
        private const val HW_RECORD_SETTLE_MS = 2000L

        /** How often [startRecordFromAnyMode] looks at the camera's pushed mode, and for how
         *  long, and how much more it waits once VIDEO is reported. The push is 2 Hz; 1200 ms
         *  after it says VIDEO is past every failure measured so far (800 ms after the ack
         *  failed; 2000 ms after the press failed once when the mode itself came late). */
        private const val MODE_POLL_MS = 250L
        private const val MODE_POLL_LIMIT_MS = 6000L
        private const val MODE_READY_SETTLE_MS = 1200L

        /** How long to wait for the camera's RECORD_START before assuming the start was ignored.
         *  When the camera is ready this arrives in ~1ms, so this is ~1000x margin. */
        /** Slowest the PIP blend slider may write to the camera WHILE THE THUMB IS MOVING.
         *  The lift always writes. See wireBlendSlider. */
        private const val BLEND_WRITE_MS = 250L
        private const val RECORD_CONFIRM_MS = 1200L

        /**
         * Fixed mini-map zoom. The map is locked, so this is the zoom for the whole flight.
         *
         * **Zoom numbers only compare between maps with the same tile size.** A MapLibre map
         * uses 512px tiles; this is osmdroid on MAPNIK at 256px (verified against the bundled
         * osmdroid 6.1.14). A 512px tile at zoom z covers the same ground as a 256px tile at
         * z+1, so MapLibre 15 == osmdroid 16. When matching another map, compare ground
         * covered, not the number.
         *
         * **Two levels, toggled by the button on the map** (design decision), rather than a
         * single compromise level. They serve different jobs and the numbers, on the Smart
         * Controller's 180x240dp map at xhdpi, are why these two:
         *
         *  - WIDE (15.5) covers ~586 x 781 m, so the home point stays on the map out to ~293 m
         *    laterally.
         *
         *    ⚠ **HOME LEAVES THE MAP BEFORE THE AIRCRAFT REACHES ITS DISTANCE LIMIT.** The
         *    max-distance limit is 1600 ft (488 m) and this view holds ~293 m. A zoom of 13
         *    would avoid that. It is accepted, because DOUBLE-TAPPING THE
         *    MAP doubles both dimensions — an expanded WIDE covers ~1172 x 1562 m, so home is
         *    back on the map to ~586 m, past the limit. That is the trade: the compact view is
         *    tighter, and the expanded view is the one that answers "where is home".
         *    The HUD's numeric HOME distance and bearing stay correct at any range regardless.
         *
         *    ⚠ **15.5 IS A FRACTIONAL LEVEL, SO IT IS SOFTER THAN 15 OR 16.** Tiles exist only
         *    at integer zooms; osmdroid renders 15.5 by upscaling z15 tiles ~1.41x, and street
         *    labels blur. There is no fractional level that avoids this. If labels read as too
         *    soft, the fix is 15 or 16, not a different fraction.
         *  - NEAR (18) covers ~104 x 138 m: the "what is directly below the aircraft" view.
         *    It is a NATIVE zoom level, so it renders sharp. Home leaves this
         *    view past ~52 m, which is expected — that is what WIDE is for.
         *
         * At NEAR the home point and red way-back line leave the view past ~104 m — that is
         * expected, and the reason WIDE exists rather than something to fix. The HUD's numeric
         * HOME distance and bearing remain correct in both modes.
         */
        /** Mini-map scale-bar lengths, in on-screen centimetres (osmdroid's unit for
         *  maxLength). Short keeps the bar off the zoom toggle on the 180dp map; full is
         *  osmdroid's default inch for the doubled map. */
        private const val SCALE_BAR_CM_SMALL = 1.27f
        private const val SCALE_BAR_CM_FULL = 2.54f

        /** Within this many degrees of the aircraft bearing, the antenna-aim marker reads
         *  GREEN — close enough for the controller's antenna lobe. Read by [AntennaAimView]
         *  so the arc and this screen judge "aligned" identically. */
        const val ANTENNA_ALIGNED_DEG = 10.0

        private const val MAP_ZOOM_WIDE = 15.5
        private const val MAP_ZOOM_NEAR = 18.0
        private const val KEY_MAP_WIDE = "flight_map_wide"

        /** Where the mini-map centers before the aircraft has a GPS fix. A neutral placeholder
         *  point; set it to your own area if you want the map to open there. Same point as the
         *  UASFM center hint. */
        private const val DEFAULT_LAT = 45.0000
        private const val DEFAULT_LON = -100.0000

        /** How long a transient notice ("Home Point Set") stays up. */
        private const val NOTICE_MS = 3000L

        /** Screen-capture permission request. Android will not let this grant be persisted, so
         *  the pilot sees the system dialog on every stream start. */
        private const val REQUEST_MEDIA_PROJECTION = 3001

        /** One tap of the AR FOV calibration +/- buttons, degrees. */
        private const val FOV_STEP_DEG = 0.5

    /** Raw digital-zoom units per 1x. The SDK's scale is hundredths; see [applyZoom]. */
    private const val ZOOM_RAW_PER_X = 100

    /** Zoom limits in raw units, 1x to 16x. Enforced on OUR side: setDigitalZoomScale does no
     *  range checking and forwards whatever int it is given straight to the camera. */
    private const val ZOOM_RAW_MIN = 100
    private const val ZOOM_RAW_MAX = 1600

    /** How many ticks a held rocker waits before it starts walking the ladder. Six ticks at the
     *  rocker's ~200ms repeat is about 1.2 seconds.
     *
     *  THIS IS WHAT SEPARATES A PRESS FROM A HOLD, and the number comes from measurement, not
     *  from taste. The rocker is stiff and spring-loaded, and a press on it is NOT one tick: of
     *  44 presses captured on hardware, the commonest length was 4 to 5 ticks (0.8
     *  to 1.0 second) and 19 of the 44 fell there. A value of 3 ticks
     *  would make those 19 presses climb TWO levels for one push.
     *
     *  Six clears every press measured. The cost is 1.2s of dead time before a hold starts to
     *  walk, which is the right way round: waiting is a nuisance, an unasked-for second level
     *  is a wrong picture.
     *
     *  ⚠ THE MEASUREMENT IS BIASED LONG. It was taken while the rocker drove a continuous
     *  zoom, thus the pilot was deliberately holding to watch the value park. Real taps on a
     *  ladder will be shorter. Re-measure before lowering this — the press length is in the log
     *  as the count of ZOOM_IN/ZOOM_OUT events with keyValue above 0. See [onZoomRocker]. */
    private const val ZOOM_LADDER_REPEAT_DELAY_TICKS = 6

    /** How many ticks between levels once a held rocker is walking: two, about 400ms, thus the
     *  nine levels of [ZoomLadder] take roughly four seconds end to end.
     *  Fast enough to cross the range without the control feeling stuck, slow enough to let go
     *  on the level wanted. */
    private const val ZOOM_LADDER_REPEAT_EVERY_TICKS = 2

    /** A gap longer than this means a NEW press, rather than one continuous hold. Two rocker
     *  repeats' worth: long enough not to trip on the jitter in the hardware capture
     *  (199-213ms), short enough to catch a real gap between presses.
     *
     *  This is not how a release is detected — keyValue 0 is, at the listener. All this
     *  does is mark where one press ends and the next begins, which is when [onZoomRocker]
     *  re-seeds from the camera and restarts the repeat delay. */
    private const val ZOOM_RELEASE_GAP_MS = 400L

    /** How long after this app writes a zoom the camera's own reading is treated as stale.
     *
     *  The reading is a ~2Hz push (AutelProductHolder), thus up to about 500ms behind a write
     *  we just made; 1500ms is three of those pushes and leaves room for the command round-trip
     *  in front of them. Inside this window [seedZoomFromCamera] keeps this app's own value.
     *  Outside it, the camera wins — which is the whole point, because some other application
     *  may have moved it. */
    private const val ZOOM_SEED_SETTLE_MS = 1500L

    /** How long rocker ticks are coalesced before the camera is written. Comfortably longer
     *  than the rocker's ~200ms repeat, because the point is to collapse a sustained HOLD — ten
     *  ticks in two seconds — into a few commands rather than ten. */
    private const val ZOOM_PUSH_MS = 150L

        private const val REQUEST_CODE_LOCATION = 4302

        /** A cached controller fix younger than this is used as-is; older forces a fresh read. */
        private const val FRESH_FIX_MS = 30_000L
        /** How long to wait for the controller's receiver to answer before giving up. */
        private const val LOCATION_TIMEOUT_MS = 12_000L
    }

    /**
     * The RTH menu (touch and hold RTH, or touch it during a return): cancel a return that is
     * running, or move the home point.
     *
     * ⚠ **THE AIRCRAFT CAN RETURN HOME ON ITS OWN AND THIS APPLICATION COULD NOT STOP IT.**
     * On a long mission an aircraft went home for a reason the pilot could not see, and the
     * pilot could only fight it on the sticks. `AutelFlyController.cancelReturn` is on the
     * interface — found by sweeping the aar rather than trusting a "the SDK cannot do this"
     * conclusion.
     *
     * ⚠ **CANCEL IS ALSO HOW THE PILOT GETS THE STICKS BACK.** The same mission reported that
     * fine adjustments were refused during a return but worked once the aircraft was descending
     * on its own. That is the airframe ignoring stick on the return leg and it is not ours to
     * change — but ending the return ends that.
     *
     * ⚠ **THERE IS NO CANCEL LANDING.** It was tried beside Cancel Return
     * and flown: requested twice during a landing, `cancelLand`
     * was accepted both times and the fly mode was still LANDING 1.5 s later both times. A
     * control that the aircraft does not obey is worse than none on the screen a pilot fights
     * from, so only the return can be cancelled. The SDK call stays on the
     * interface; do not put it back without a flight that shows it working.
     *
     * ⚠ **NO CONFIRMATION (design decision).** `goHome` has one and this
     * deliberately does not: this is the UNDO of something the aircraft started by itself, the
     * pilot is fighting it while they read this, and a dialog costs seconds they do not have.
     * Choosing it from a long-press menu is already a deliberate act, and the warning banner has
     * been telling them a return is running — so the tap is never blind.
     *
     * ⚠ **AN ITEM IS OFFERED ONLY WHEN IT APPLIES.** A menu that lists Cancel Return while
     * nothing is returning teaches a pilot that the menu does not mean what it says, on the one
     * screen where that must never be true.
     */
    private fun rthMenu() {
        val fc = AutelProductHolder.evo2?.flyController
        val mode = FlightWarnings.flyMode
        val returning = FlightWarnings.returningHome(mode)

        val b = AlertDialog.Builder(this, R.style.TakDialogTheme).setTitle("Return to Home")
        b.setMessage(if (returning) "The aircraft is returning home." else null)
        if (fc != null && returning) {
            b.setPositiveButton("Cancel Return") { _, _ -> cancelReturn(fc) }
        }
        b.setNeutralButton("Reset Home Point…") { _, _ -> confirmResetHome() }
        b.setNegativeButton("Close", null)
        b.show()
    }

    /**
     * Stops a return, then CHECKS THE AIRCRAFT AGREED — a success callback is not evidence,
     * least of all on this control.
     *
     * The `onSuccess` here means the command was taken, not that the aircraft stopped. Truth is
     * [FlightWarnings.returningHome] reading the fly mode off the telemetry the bridge already
     * publishes: if the aircraft is still returning after [RTH_CANCEL_CONFIRM_MS], the pilot is
     * told so rather than being left with a notice that says it worked.
     */
    private fun cancelReturn(fc: com.autel.sdk.flycontroller.AutelFlyController) {
        AppLog.w(TAG, "CANCEL RETURN requested by the pilot (mode=${FlightWarnings.flyMode})")
        showNotice("Cancelling the return")
        runCatching {
            fc.cancelReturn(object : com.autel.common.CallbackWithNoParam {
                override fun onSuccess() { AppLog.i(TAG, "cancelReturn: accepted") }
                override fun onFailure(error: AutelError?) {
                    AppLog.e(TAG, "cancelReturn FAILED: ${error?.description}")
                    runOnUiThread { showNotice("The aircraft refused to stop the return", refused = true) }
                }
            })
        }.onFailure { AppLog.e(TAG, "cancelReturn threw: ${it.message}") }
        handler.postDelayed({
            val still = FlightWarnings.returningHome(FlightWarnings.flyMode)
            if (still) {
                AppLog.e(TAG, "RETURN DID NOT STOP — the aircraft is still in " +
                    "${FlightWarnings.flyMode} ${RTH_CANCEL_CONFIRM_MS}ms after cancelReturn")
                showNotice("The return did not stop", refused = true)
            } else {
                AppLog.i(TAG, "return cancelled — the aircraft is in ${FlightWarnings.flyMode}")
                showNotice("Return cancelled. You have the aircraft.")
            }
        }, RTH_CANCEL_CONFIRM_MS)
    }

}
