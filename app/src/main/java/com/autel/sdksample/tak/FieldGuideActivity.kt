package com.autel.sdksample.tak

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.autel.sdksample.R
import com.taklite.util.AppLog

/**
 * Pilot field guide — a read-in-the-field explanation of what every control does.
 *
 * **Audience is the pilot, not a developer.** No class names, no file paths, no SDK talk. Where
 * a limitation matters to a flight decision it IS stated plainly (what the FAA layer can't tell
 * you, when the altitude readout is approximate, what a local marker delete does and doesn't
 * do), because a guide that only lists happy paths is the kind that gets someone in trouble.
 *
 * **Every pilot-facing string in this file is written in ASD-STE100 Simplified Technical
 * English. Keep it that way when you edit.** STE is the aerospace controlled-language standard
 * (approved dictionary + 53 writing rules); it exists so that a reader who is tired, rushed, or
 * reading in a second language cannot mis-parse a safety-relevant instruction. The rules this
 * text is held to:
 *  - **One word, one meaning, one part of speech.** A short screen press is always "touch",
 *    never tap/press/hit. A long press is always "touch and hold". The airframe is always the
 *    "aircraft", never the drone. A marker is always a "marker", never a pin.
 *  - **Approved vocabulary.** "make sure" not ensure, "about" not approximately, "use" not
 *    utilize, "let" not allow, "get" not obtain, "but" not however, "because of" not due to.
 *  - **Active voice, simple tenses, no -ing forms.** "The app sends the marker", not "the
 *    marker is sent" or "sending the marker".
 *  - **Sentence length**: 20 words max for an instruction, 25 for description. Six sentences
 *    max per paragraph, one topic each.
 *  - **Conditions come first**: "If the signal is weak, select Low" — never the reverse.
 *  - **No idiom, metaphor, or humour.** They are the first thing to fail a tired reader and the
 *    first thing to fail a translator.
 *  - **Warnings open with the command**, then the reason.
 *
 * The one deliberate exception: **on-screen control labels are quoted verbatim** even when they
 * are not STE ("Drop Marker at Crosshair", "Enroll & Connect"). STE treats these as technical
 * names, and a guide that renames the button a pilot is hunting for is worse than useless.
 *
 * **The icon examples are live views, not pictures.** Each one is the real toolbar widget —
 * [BatteryGaugeView], [SignalBarsView], [LiveToggleView], [RecordToggleView], the TAK badge with
 * its status dot — constructed here and driven into the state being described. Screenshots or
 * hand-drawn copies would silently go stale the next time an icon changes; these can't, because
 * they ARE the icons.
 *
 * **Every control is documented, and every one of them works.** There is no "What this build
 * cannot do" section, so a regression to a placeholder has no standing home — add one as a
 * final section if that ever happens.
 *
 * Section 5 is a PROCEDURE, not a control reference: the aim calibration is periodic
 * maintenance the pilot performs, like a compass calibration, so it gets steps in order
 * rather than a description of each button.
 *
 * ## Length
 *
 * The guide is kept short on purpose. A guide long enough that a pilot does not read it is a
 * worse safety document than a short one that omits something. The rule: **keep every fact
 * that changes a flight decision, delete the explanation of why.** A pilot needs "land when
 * the ring is yellow", not a paragraph on how the gauge is computed.
 *
 * Section 3 (the controller buttons) is an index. Nothing in it is new INFORMATION — C1, C2
 * and the zoom rocker are documented in section 4 — but scattered through section 4's prose
 * the hardware read as undocumented. That is a findability problem, and the fix is an index,
 * not more words. When a hardware mapping changes, section 3 is the one place to change it,
 * and `FlightActivity.installHardwareButtonListener` is the ground truth to check it against.
 *
 * [ANCHOR_AR] is a tag, not a section number, so the deep link from the flight screen survives
 * a renumbering.
 */
class FieldGuideActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_field_guide)
        AppLog.v(TAG, "field guide opened")
        // Menu button on the left of the action bar, matching the flight screen and Pre-Flight
        // (was a back arrow) — returns to the home screen.
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_menu)
        }
        content = findViewById(R.id.fieldGuideContent)

        title("TAKPilot2 Field Guide")
        lede("What each control does, on the screen and on the controller. Read it before " +
            "you fly. This is the EVO II build.")

        sectionOne()
        sectionTwo()
        sectionControllerButtons()
        sectionThree()
        sectionFour()
        sectionFlightRecords()

        divider()
        spacer(24)

        scrollToAnchor(intent?.getStringExtra(EXTRA_SCROLL_TO))
    }

    /**
     * Jumps to the entry tagged [anchor], for a deep link from elsewhere in the app.
     *
     * Posted rather than called directly: the guide is built in onCreate and nothing has been
     * measured or laid out yet, so the card's y position is still 0 and an immediate scroll
     * would silently do nothing. Runs after the first layout pass instead.
     *
     * Fails OPEN — an absent or unrecognised anchor simply leaves the guide at the top, which
     * is what a reader wants if a link ever goes stale.
     */
    private fun scrollToAnchor(anchor: String?) {
        if (anchor.isNullOrEmpty()) return
        val scroll = findViewById<android.widget.ScrollView>(R.id.fieldGuideScroll) ?: return
        val target = content.findViewWithTag<View>(anchor)
        if (target == null) {
            AppLog.w(TAG, "field guide anchor '$anchor' not found — opening at the top")
            return
        }
        scroll.post {
            // Offset up by the section heading's worth so the reader lands with the entry's
            // title in view, not with the card's top edge flush against the action bar.
            scroll.scrollTo(0, (target.top - dp(12)).coerceAtLeast(0))
            AppLog.v(TAG, "field guide opened at '$anchor'")
        }
    }

    // ---------------------------------------------------------------- Section 1

    private fun sectionOne() {
        section("1. What this app is for")
        body("TAKPilot2 flies your Autel EVO II. At the same time it puts the aircraft on the " +
            "shared TAK map of your team.")

        bullet("Your team sees the position, the heading and the altitude of the aircraft.")
        bullet("Your team sees the point on the ground where the camera looks.")
        bullet("You put markers on what you see. Your team gets them in a few seconds.")
        bullet("The quick marker is a single marker that is dropped at the press of a button " +
            "and moves to a new location upon each subsequent press of the button.")
        bullet("The app can send live video to a server of your team.")

        note("Do the firmware updates, the compass calibration, the gimbal calibration and " +
            "the aircraft registration with the Autel app first. Do not do them here.")
    }

    // ---------------------------------------------------------------- Section 2

    private fun sectionTwo() {
        section("2. Pre-Flight Setup")
        body("Set these on the ground prior to flight. Change them for a new area, a new " +
            "server or a new task.")

        sub("1. Aircraft Settings")
        body("Max altitude, max distance and RTH altitude, in feet. The app sends them to " +
            "the aircraft at each connection.")

        sub("2. Video Streaming")
        body("Optional. Type the address of the video server of your team, the port, the " +
            "broadcast ID and the login. Then select the quality. Select Standard. If the " +
            "connection is weak, select Low. Select the codec H.264 for compatibility.")
        // The pointer to the screen is for every mode.
        body("Touch Configure Video Servers to set the address, the login and the codec.")
        if (AppMode.secondVideoServerAllowed(modePrefs())) {
            body("Elevated mode only. Active server selects between two servers, and each one " +
                "has its own address, login and codec.")
        }
        body("The video has two protocols: RTSP and SRT. RTSP gives the lowest delay on a " +
            "reliable network. SRT gives a low delay on a less reliable network, for example " +
            "a cellular network.")

        sub("3. TAK Server Connection")
        body("Pre-Flight shows who you are connected as, which channels are active and where " +
            "the live-video link goes. To change any of it, touch Configure TAK Server.")
        body("On that screen: type the address of the TAK server, your username, your password " +
            "and the callsign of the aircraft. Then touch Enroll & Connect.")
        body("Select the active channels there, or in flight with a touch and hold on the TAK " +
            "connection icon.")
        // ⚠ THE GUIDE MIRRORS THE SCREENS, thus it must not describe controls the mode removes.
        // A guide that names a control the pilot cannot find is worse than a shorter guide —
        // they go looking for it in the air. Each group OPENS with "Elevated mode only." (the
        // on-screen name of ENHANCED), so the text says which mode it belongs to wherever it is
        // read.
        if (AppMode.elevatedAccountAllowed(modePrefs())) {
        body("Elevated mode only. Elevated account (optional): a shared second TAK account. " +
            "Only the channel it " +
            "belongs to gets the live-video link. Every other channel still gets the position " +
            "and the markers. After Log Out, enroll the Elevated account again, or the video " +
            "link goes to every channel.")
        body("Tick and untick the channels of the Elevated account the same as your own. " +
            "Untick a channel to stop the aircraft reaching it, which is also how you stop " +
            "data arriving from it. ⚠ Every controller shares that account, thus no controller " +
            "sends video to a channel you untick until somebody ticks it again.")
        }
        // Its OWN predicate, not elevatedAccountAllowed: gating this paragraph on the Elevated
        // predicate works only while every predicate happens to agree.
        if (AppMode.emergencyBroadcastAllowed(modePrefs())) {
        // A start also starts the stream.
        body("Elevated mode only. Emergency Broadcast: touch and hold LIVE, then touch Start " +
            "Emergency Broadcast. It shows only when the Elevated account is on. If LIVE is " +
            "off, the app starts it and asks to capture the screen: allow it. If you refuse, " +
            "the broadcast stops. " +
            "Every channel gets the live-video link for 15 minutes. The aircraft marker carries " +
            "the link, thus the aircraft must have a GPS position. A notice with a timer shows " +
            "at the top of the flight screen. The notice is amber and reads NO VIDEO STREAM " +
            "while no video goes out. Touch the notice to add 15 minutes. Touch and " +
            "hold it to stop. Each start, renew and stop goes in the flight record.")
        }

        sub("4. Elevation Data (DTED)")
        body("The terrain data for your area. Import one file for each region. It improves " +
            "two things:")
        bullet("Marker accuracy.")
        bullet("The altitude shows the true height of the aircraft above the ground. Without " +
            "the data, it shows the height above your takeoff point.")

        sub("5. FAA Airspace Ceilings (UASFM)")
        body("This downloads the FAA ceiling data for an area. The flight screen then shows " +
            "the ceiling at the position of the aircraft.")

        // In BOTH modes: the control exists in both, and the "Elevated mode only" lines above
        // need something to refer to.
        sub("6. Application Mode")
        body("Elevated or Basic. Elevated has two TAK accounts, two video servers and " +
            "Emergency Broadcast. Basic has one of each and no Emergency Broadcast: every " +
            "active channel gets the aircraft and, when a video address is on, its video " +
            "link. The locks need no password.")
        body("Touch Change Mode. The screen says who will receive the aircraft after the " +
            "change. A change needs the password in both directions, and it cannot be made " +
            "while the aircraft flies or LIVE is on.")
    }

    /** The store every [AppMode] predicate in this guide reads. */
    private fun modePrefs(): android.content.SharedPreferences =
        getSharedPreferences(TakConnectActivity.PREFS, MODE_PRIVATE)

    // ------------------------------------------------- Section 3 (controller)

    /**
     * The hardware controls, in one place.
     *
     * Every fact here is also in the guide's prose, where it is spread across unrelated entries
     * — C1 inside the IR entry, the two halves of C2 under two different crosshair entries — so
     * a pilot who wanted "what does C2 do" would have to read the whole of section 4. Coverage
     * is not the problem; findability is.
     *
     * The prose entries keep the DETAIL and lose their hardware paragraph, so a fact still has
     * exactly one home. This section is the index; section 4 is the reference.
     *
     * Ground truth is `FlightActivity.installHardwareButtonListener` and [ZoomLadder.RUNGS_RAW],
     * NOT other guide text. Check it there when the mapping changes — the SDK names these
     * CUSTOM_BUTTON_{SHORT,LONG}_{A,B}, and which letter is which physical key is a constant in
     * that file.
     */
    private fun sectionControllerButtons() {
        section("3. The controller buttons")
        body("Most of these buttons do the same functions as the buttons on the screen. They " +
            "are the most commonly used features. The shutter button and the RTH button have " +
            "no button on the screen.")

        keyEntry(
            "C1",
            "Changes between the normal camera and PIP, the same as the PIP button. From " +
                "full thermal it goes back to PIP.",
            "Changes the thermal colours.",
        )

        keyEntry(
            "C2",
            "Puts the quick marker. If the quick marker already exists, it MOVES to what the " +
                "camera looks at now. The app sends it to the TAK server immediately.",
            "Puts a NEW static marker. This marker does not move. Each additional press and " +
                "hold puts a new static marker.",
        )

        // The zoom ladder lives in the press line itself: it is the fact a pilot most often
        // looks for in this section.
        keyEntry(
            "Zoom rocker (right side)",
            "Push it and release it. The zoom moves one level: 1X, 2X, 3X, 4X, 6X, 8X, 10X, " +
                "12X, 16X.",
            "Push it and hold it. The zoom moves through the levels. Release it to stop.",
        )

        // There is no screen shutter pill: this controller has a hardware shutter, and a second
        // way to do it is clutter on the one screen the pilot flies from. Section 3 is the ONE
        // place a hardware mapping is written down, thus the fact lives here.
        keyEntry(
            "Shutter button",
            "Takes a photo. The app saves the photo to the card in the aircraft, not to the " +
                "controller. The screen shows \"Photo Saved\". If the screen shows \"The photo " +
                "did not save\", the photo is NOT on the card. Take it again.\n\n" +
                "One press saves BOTH cameras: a normal photo and a thermal photo. It does " +
                "this for the camera you do not see, too.\n\n" +
                "WHILE THE AIRCRAFT RECORDS, ONE PRESS SAVES THE NORMAL PHOTO ONLY. You get " +
                "no thermal photo until the recording stops.",
            null,
        )

        keyEntry(
            "RTH button",
            "Sends the aircraft home. This is a function of the controller.",
            null,
        )
    }

    // ---------------------------------------------------------------- Section 4

    private fun sectionThree() {
        section("4. The Flight Screen")
        body("The live camera image fills the screen. The status icons are in the bar at the " +
            "top left. The buttons are in the column down the left side, under your thumb.")

        sub("Status bar (top left)")

        entry(
            listOf(icon(R.drawable.ic_menu) to "Menu"),
            "Menu",
            "This button closes the flight screen and shows the home screen. The aircraft " +
                "continues to fly and stays connected to TAK.",
        )

        entry(
            listOf(
                takBadge(connected = true) to "Connected",
                takBadge(connected = false) to "Not connected",
            ),
            "TAK connection",
            "A green dot shows that your aircraft is on the TAK map of your team. A red dot " +
                "shows that it is not on the map. Touch: connect or disconnect. Touch and " +
                "hold: the TAK Channels. If they are locked, touch Unlock. In Elevated mode it " +
                "asks for the password.",
        )

        entry(
            listOf(
                battery(85) to "85%",
                battery(24) to "24%",
                battery(9) to "9%",
            ),
            "Battery",
            "The charge in the battery of the aircraft. Land the aircraft when the ring is " +
                "yellow. Do not wait for red.",
        )

        entry(
            listOf(
                signal(90) to "Strong",
                signal(60) to "Medium",
                signal(20) to "Weak",
            ),
            "Controller signal",
            "The strength of the signal between the controller and the aircraft. If the bars " +
                "decrease, fly the aircraft nearer or move away from other sources of radio " +
                "signals. If the aircraft loses the signal, it returns to home.\n\n" +
                "The bars are grey at \"—%\" until the aircraft connects. If they stay grey " +
                "when the aircraft is connected, close the Autel Explorer app. Only one app " +
                "at a time can read the signal.",
        )

        entry(
            listOf(
                gps(hasFix = true) to "Position",
                gps(hasFix = false) to "No position",
            ),
            "GPS satellites",
            "The quantity of satellites that the aircraft receives. Green shows that the " +
                "aircraft has its position. Wait for green before you take off. Without a " +
                "position the aircraft cannot hold its position, cannot set a home point, and " +
                "cannot come home correctly.",
        )

        entry(
            listOf(
                image(R.drawable.ic_rth_home_set) to "Home set",
                image(R.drawable.ic_rth) to "No home",
            ),
            "Return to Home",
            "Touch: sends the aircraft home. The app asks you to confirm.\n\n" +
                "Touch and hold (or touch during a return): the Return to Home menu. Cancel " +
                "Return shows only while the aircraft returns. It stops the return at once and " +
                "gives you the sticks — no confirmation. The app then tells you if the return " +
                "did not stop. Reset Home Point moves the home point to your position, from " +
                "the GPS of the controller. Use it if you moved away from the takeoff point. " +
                "The app shows the coordinates and asks you to confirm.\n\n" +
                "The house is green when the home point is set.",
        )

        sub("Buttons (left column, top to bottom)")

        entry(
            listOf(image(R.drawable.ic_drop_pin) to "Marker"),
            "Put a marker",
            "Touch: puts a marker at the center of the camera image. Point the camera at the " +
                "target first. Select the type (Friendly, Hostile, Neutral or Unknown). The " +
                "app names the marker and sends it to your team.\n\n" +
                "Touch and hold: opens the \"Markers\" list, with your markers and the markers " +
                "of your team. Here you can change them or remove them. Clear All removes all " +
                "your markers.\n\n" +
                "DELETE REMOVES A MARKER FROM THIS AIRCRAFT ONLY. It stays on the screens of " +
                "your team for about 3 days.",
        )

        entry(
            listOf(
                lightsPill(false) to "Lights on",
                lightsPill(true) to "Lights off",
                lightsPill(null) to "Not known",
            ),
            "Exterior lights",
            "This turns the navigation lights of the aircraft on and off. Turn them off to " +
                "make the aircraft difficult to see at night. The button shows what the " +
                "AIRCRAFT reports, not what you last asked for. Amber means the aircraft has " +
                "not answered yet: do not read it as off.",
        )

        entry(
            listOf(arPill(on = false) to "Off", arPill(on = true) to "On"),
            "AR: markers on the video",
            "This draws the markers on the live image near their positions. The button is " +
                "green when it is on. It is ON when you open the flight screen.\n\n" +
                "A marker outside the camera image shows as an arrow at the edge. The arrow " +
                "shows the direction to turn the camera.\n\n" +
                "Touch and hold: adjust the filters and the range where markers show.",
            listOf(
                "THE AR VIEW IS NOT ACCURATE FOR A POINT. It shows the general area of a " +
                    "marker. Do not use it to choose between objects that are close together, " +
                    "such as one house in a tight row of houses.",
            ),
            anchor = ANCHOR_AR,
        )


        entry(
            listOf(modePill("PIP", false) to "Normal", modePill("PIP", true) to "PIP"),
            "PIP: the thermal camera",
            "A touch changes between the normal camera and PIP. PIP is the thermal image in " +
                "the centre of the normal image. In PIP, the pill under it makes the thermal " +
                "image full screen; touch it again to go back to PIP. " +
                "Zoom does not work in PIP.\n\n" +
                "YOU CANNOT CHANGE THE CAMERA WHILE THE AIRCRAFT RECORDS. The app tells you " +
                "to stop the recording. The aircraft records the camera you were on when the " +
                "recording started. In PIP it records three files: normal, thermal and PIP. " +
                "Select the camera you want BEFORE you start to record.",
        )


        entry(
            listOf(zoomPill("1X") to "Normal", zoomPill("2X") to "2X view"),
            "Zoom",
            "Touch: changes between 1X and 2X. Touch and hold: 4X. From 4X, a touch or a " +
                "touch and hold goes back to 1X, so one touch always gets you to the widest " +
                "view.",
        )

        entry(
            listOf(
                live(LiveToggleView.State.OFF) to "Off",
                live(LiveToggleView.State.LIVE) to "Video on",
                live(LiveToggleView.State.RECONNECTING) to "Connects again",
            ),
            "LIVE: video to your team",
            "This button starts and stops the live video to the video server of your team. " +
                "First, set the server data in Pre-Flight Setup.\n\n" +
                "A yellow button that flashes shows that the connection stopped. The app " +
                "tries to connect again without your command. Do not touch the button. If you " +
                "touch it, the app stops and does not try again.",
        )

        entry(
            listOf(
                rec(recording = false) to "Off",
                rec(recording = true) to "Records",
            ),
            "REC: record to the aircraft",
            "This button records video to the card in the aircraft. It is independent of the " +
                "live video. You can use one function, both functions, or no function. The " +
                "card keeps the full quality video without the HUD.\n\n" +
                "REC works from photo mode too: it changes the camera to video and records. " +
                "The camera stays in video after you stop.",
        )

        sub("On the video image")

        entry(
            emptyList(),
            "The crosshair",
            // No fixed foot figures per colour: they hold only near 100 ft AGL, and at 400 ft
            // a GREEN drop measured about 70 ft off.
            "The crosshair is the center of the camera image, and the position where a marker " +
                "goes. The colour of the ring shows how much the camera angle limits the " +
                "accuracy.\n\n" +
                "WITH terrain data: GREEN 25° down or more, YELLOW 10° to 25° down.\n" +
                "WITHOUT terrain data: GREEN 30° down or more, YELLOW 15° to 30° down.\n" +
                "RED: do not place a marker.\n\n" +
                "The error also grows with your height and the distance. At 400 ft above the " +
                "target a GREEN marker can be 70 ft off. The compass error moves a marker to " +
                "the side: at 1,000 ft it can be 80 ft. Compare a marker with the map before " +
                "you send it.",
        )

        entry(
            emptyList(),
            "Video / photo switch (under the EV slider)",
            "The switch shows which mode the camera is in: the movie camera for video, the " +
                "still camera for photo. Touch the other side to change the mode. This takes " +
                "no photo and records nothing. The shutter button changes the camera to " +
                "photo; REC changes it to video. It does not work while the aircraft records.",
        )

        entry(
            emptyList(),
            "Warnings (bottom left)",
            "A box in the bottom left corner shows a warning. RED means act now. AMBER means " +
                "know it. IF THE MOTORS DO NOT START, READ THIS BOX FIRST.\n\n" +
                "The warnings are for the compass, the GPS, the battery, the flight limits, " +
                "the wind, a missing home point, and an automatic return home. The box shows " +
                "the most important one, with a count if there are more.\n\n" +
                "Touch the box to read all of them; the list opens upward. Touch it again to " +
                "close the list.\n\n" +
                "Touch the ✕ to remove the box. The box comes back when the warnings change.\n\n" +
                "DEBUG LOG ON means the debug log is on and the picture can stutter. The ✕ " +
                "does not remove it. Turn the log off in Debug Log from the menu.",
        )

        entry(
            emptyList(),
            "Quick marker: touch the crosshair",
            "Touch the crosshair to put a marker immediately, with no questions. The name is " +
                "always ${TakDropMarkers.QUICK_NAME}.\n\n" +
                "THERE IS ONLY ONE QUICK MARKER. Point the camera at a new location and touch " +
                "the crosshair again: the marker MOVES to the new location, on the screens of " +
                "all your team. Press the C2 button for the same function.",
        )

        entry(
            emptyList(),
            "Static marker: touch and hold the crosshair",
            "Touch the crosshair and hold it to put a static marker of the type Unknown. " +
                "THIS MARKER DOES NOT MOVE. A second touch and hold puts a SECOND marker.\n\n" +
                "The name of the static marker is the callsign of the aircraft and a number, " +
                "for example EVO2-07. Press and hold the C2 button for the same function.",
        )

        entry(
            emptyList(),
            "FAA ceiling line",
            "This line is above the small map. It shows the published ceiling at the position " +
                "of the aircraft, and it becomes red if you fly above the ceiling.\n\n" +
                "FAA 300 ft AGL - the published ceiling here.\n" +
                "Class G - no facility map here. The usual 400 ft limit is applicable.\n" +
                "FAA --- ft AGL - the app does not know. You did not download the data, or " +
                "you flew out of the area you downloaded.\n" +
                "FAA - no fix - the aircraft has no position yet.",
        )

        entry(
            emptyList(),
            "The map",
            "The small map in the bottom right corner. The red line goes from the home point " +
                "to the aircraft. The map also shows the markers of your team. There are two " +
                "zoom levels, near and wide. Touch the map two times to make the map larger, " +
                "and two times again to make it small.",
        )

    }

    // ---------------------------------------------------------------- Section 4

    /**
     * The aim calibration.
     *
     * The second-TAK-device method only, as two bullets: watch the SPI point on another device
     * and tune the offsets until it sits on the target. An ordered step-by-step procedure was
     * deliberately not used.
     */
    private fun sectionFour() {
        section("5. How to correct the position of a marker")

        body("If the aim of the camera has a small error, the markers go to a position that " +
            "is not correct. \"Aim Offsets\" corrects this. The error belongs to the " +
            "aircraft, not to the app: a gimbal can move after a hard landing or a repair.")

        body("Do this one time for each aircraft. Do it again after a hard landing, after a " +
            "repair of the gimbal or the camera, or when you use a different aircraft.")

        body("You need a second device with iTAK, ATAK, TAK Aware or CloudTAK that can see " +
            "the markers of the aircraft.")

        sub("Before you start")
        bullet("Select a target that you see clearly in the video and can find on the map of " +
            "the second device. Center the reticle of the controller on that object. A mark " +
            "on a road or a corner of a building is good. The aircraft sends its camera look " +
            "point to TAK about two times each second. On the second TAK device, find that " +
            "point. Its name ends with \"-SPI\" and its icon looks like the reticle. Watch " +
            "the SPI while you change the offsets on the controller. Change the offsets until " +
            "the SPI on the map lines up with the object the reticle points at in the video.")
        bullet("The point moves a few seconds after each change. Change one control at a " +
            "time. Stop when the point is on the target.")
    }

    // Flight path records. A DESCRIPTION of automatic behaviour, not a control
    // reference — there is no switch, and the section says so first, because a pilot who
    // reads about a new function will otherwise go looking for its setting.
    private fun sectionFlightRecords() {
        section("6. Flight path records")
        body("The app records the path of each flight automatically. There is no switch, and " +
            "nothing to start or stop. A TAK server and a network are not necessary.")

        // The motors, not the height (AirborneRule).
        body("The recording starts when the motors start. It stops when the motors are off " +
            "for 10 seconds, thus a short touch on the ground does not divide the flight into " +
            "two records.")

        body("Open Downloads/TAKPilotFlights on the controller. Each flight makes two files:")
        bullet(".gpx — the track. Import it into ATAK or Google Earth.")
        bullet(".csv — one row each second: time, position, altitude, speed, heading, " +
            "battery and satellite count. Open it in a spreadsheet.")
    }

    // ------------------------------------------------------- content builders

    private fun title(text: String) = content.addView(TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE); textSize = 24f
        setTypeface(null, android.graphics.Typeface.BOLD)
    })

    private fun lede(text: String) = content.addView(TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_secondary)); textSize = 14f
        setPadding(0, dp(6), 0, dp(4))
    })

    private fun section(text: String) {
        divider()
        content.addView(TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE); textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(8))
        })
    }

    private fun sub(text: String) = content.addView(TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_accent)); textSize = 15f
        setTypeface(null, android.graphics.Typeface.BOLD)
        letterSpacing = 0.03f
        setPadding(0, dp(18), 0, dp(6))
    })

    private fun body(text: String) = content.addView(TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_light)); textSize = 14f
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(0, 0, 0, dp(8))
    })

    private fun bullet(text: String) = content.addView(TextView(this).apply {
        this.text = "•  $text"
        setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_light)); textSize = 14f
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(dp(8), 0, 0, dp(6))
    })

    /** Neutral aside — worth knowing, not a hazard. */
    private fun note(text: String) =
        calloutView(text, R.color.tp_accent, R.color.tp_surface_guide_note)

    // There is no `warn()` callout because the guide currently has no warning callouts. If a
    // hazard callout is wanted, add it as one line over [calloutView], the same shape as
    // [note], with a red tint bar on tp_surface_guide_warn; its colour tokens are in
    // takpilot_colors.xml.

    /**
     * Callout row: a coloured tint bar against a low-saturation background of the same hue.
     *
     * Takes colour RESOURCES, not hex strings — a colour literal in Kotlin is easy to reach for
     * and easy to miss, so this file follows the same colour-token rule as the layouts.
     */
    private fun calloutView(text: String, @ColorRes barColor: Int, @ColorRes bgColor: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ContextCompat.getColor(applicationContext, bgColor))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dp(4); bottomMargin = dp(10)
            }
        }
        row.addView(View(this).apply {
            setBackgroundColor(ContextCompat.getColor(applicationContext, barColor))
            layoutParams = LinearLayout.LayoutParams(dp(3), MATCH)
        })
        row.addView(TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_dim)); textSize = 13f
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        })
        content.addView(row)
    }

    private fun divider() = content.addView(View(this).apply {
        setBackgroundColor(ContextCompat.getColor(applicationContext, R.color.tp_border))
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply {
            topMargin = dp(20); bottomMargin = dp(12)
        }
    })

    private fun spacer(heightDp: Int) = content.addView(View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(heightDp))
    })

    /**
     * One control: its icon in each state worth recognising, its name, what it does, and any
     * caveats. [icons] may be empty for parts of the screen that aren't a button.
     */
    private fun entry(
        icons: List<Pair<View, String>>,
        name: String,
        what: String,
        caveats: List<String> = emptyList(),
        anchor: String? = null,
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(applicationContext, R.color.tp_surface_guide))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) }
            // Deep-link target — see EXTRA_SCROLL_TO. A tag rather than a generated view id
            // because the whole guide is built in code and ids would have to be kept unique
            // by hand against a resource file that has no other reason to exist.
            if (anchor != null) tag = anchor
        }

        if (icons.isNotEmpty()) {
            val strip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    bottomMargin = dp(10)
                }
            }
            for ((view, caption) in icons) {
                val captionView = TextView(this@FieldGuideActivity).apply {
                    text = caption
                    setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_muted)); textSize = 11f
                    gravity = Gravity.CENTER
                    setSingleLine(true)
                    setPadding(0, dp(5), 0, 0)
                    layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
                }
                // Size the chip from the MEASURED caption rather than leaving it to wrap_content.
                // Letting the layout work it out doesn't survive here: the icon is a fixed-width
                // child, so the cell settles on the icon's width and a longer caption
                // ("Not connected") gets silently cut to something that reads as a different
                // state ("Not conn"). Measuring the text and flooring the chip to it is the only
                // version that can't clip.
                val captionWidth = captionView.paint.measureText(caption).toInt()
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    // Toolbar-dark chip behind each example: these icons are drawn to sit on
                    // the flight toolbar, and judging them against a lighter card would be
                    // misleading about how they actually read in the air.
                    setBackgroundColor(ContextCompat.getColor(applicationContext, R.color.tp_surface_guide_code))
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    minimumWidth = captionWidth + dp(20)
                    layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                        rightMargin = dp(8)
                    }
                }
                cell.addView(view)
                cell.addView(captionView)
                strip.addView(cell)
            }
            card.addView(strip)
        }

        card.addView(TextView(this).apply {
            text = name
            setTextColor(Color.WHITE); textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(5))
        })
        card.addView(TextView(this).apply {
            text = what
            setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_light)); textSize = 13f
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        for (c in caveats) {
            card.addView(TextView(this).apply {
                text = "!  $c"
                setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_warn)); textSize = 12f
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(0, dp(8), 0, 0)
            })
        }
        content.addView(card)
    }

    /**
     * One hardware control: its name, what a press does, what a press-and-hold does.
     *
     * A fixed two-row shape rather than a paragraph, because this section exists to be SCANNED.
     * The labels are always in the same place, thus a pilot reads down the "Press and hold"
     * column and does not parse a sentence to find out whether a long press is mentioned.
     * [long] is null for a control that has no long press — the row is then omitted rather than
     * filled with "nothing", which would read as a documented behaviour.
     */
    private fun keyEntry(name: String, short: String, long: String?, note: String? = null) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(applicationContext, R.color.tp_surface_guide))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) }
        }
        card.addView(TextView(this).apply {
            text = name
            setTextColor(Color.WHITE); textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        })

        fun row(label: String, text: String) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    bottomMargin = dp(4)
                }
            }
            line.addView(TextView(this@FieldGuideActivity).apply {
                this.text = label
                setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_accent))
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                // Fixed width so the two labels line up and the descriptions start on one
                // column. wrap_content would step the text in and out by the label's length.
                layoutParams = LinearLayout.LayoutParams(dp(104), WRAP)
            })
            line.addView(TextView(this@FieldGuideActivity).apply {
                this.text = text
                setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_light))
                textSize = 13f
                setLineSpacing(dp(3).toFloat(), 1f)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            })
            card.addView(line)
        }

        row("Press", short)
        if (long != null) row("Press and hold", long)

        if (note != null) {
            card.addView(TextView(this).apply {
                text = "!  $note"
                setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_warn))
                textSize = 12f
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(0, dp(8), 0, 0)
            })
        }
        content.addView(card)
    }

    // ------------------------------------------------- live icon examples

    private fun iconParams(sizeDp: Int = 34) = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))

    private fun icon(res: Int) = image(res)

    private fun image(res: Int): View = ImageView(this).apply {
        setImageResource(res)
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = iconParams()
    }

    /** The TAK badge exactly as the toolbar builds it, dot tinted to the state described. */
    private fun takBadge(connected: Boolean): View {
        val frame = android.widget.FrameLayout(this).apply { layoutParams = iconParams() }
        frame.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_tak_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(34), dp(34))
        })
        frame.addView(ImageView(this).apply {
            setImageResource(R.drawable.bg_status_dot)
            setColorFilter(if (connected) CONNECTED_GREEN else DISCONNECTED_RED)
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(12), dp(12)).apply {
                gravity = Gravity.BOTTOM or Gravity.START
            }
        })
        return frame
    }

    private fun battery(pct: Int): View =
        BatteryGaugeView(this).apply { layoutParams = iconParams(); setPercent(pct) }

    private fun signal(pct: Int?): View =
        SignalBarsView(this).apply { layoutParams = iconParams(); setPercent(pct) }

    private fun rec(recording: Boolean): View =
        RecordToggleView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(66), dp(34))
            setRecording(recording)
        }

    private fun zoomPill(label: String): View = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.bg_zoom_pill)
        setTextColor(Color.WHITE); textSize = 12f
        setTypeface(null, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(26))
    }

    /** The IR pill in a state, built from the same drawables the toolbar uses. The label
     *  follows the view the way the zoom pill's label follows its level: "IR" lit for thermal,
     *  "PIP" lit for thermal in the centre. */
    private fun modePill(label: String, on: Boolean): View = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setBackgroundResource(if (on) R.drawable.bg_pill_active else R.drawable.bg_zoom_pill)
        setTextColor(if (on) CONNECTED_GREEN else Color.WHITE)
        textSize = 12f
        setTypeface(null, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(26))
    }

    /** The AR pill in either state, built from the same drawables the toolbar uses. */
    private fun arPill(on: Boolean): View = TextView(this).apply {
        text = "AR"
        gravity = Gravity.CENTER
        setBackgroundResource(if (on) R.drawable.bg_pill_active else R.drawable.bg_zoom_pill)
        setTextColor(if (on) CONNECTED_GREEN else Color.WHITE)
        alpha = if (on) 1f else 0.45f
        textSize = 12f
        setTypeface(null, android.graphics.Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(26))
    }

    /**
     * The exterior-lights pill in all THREE of its states, built from the same drawables and
     * the same colours the toolbar uses.
     *
     * Not two bare white bulbs labelled "Lights on" / "Lights off": the button says its state
     * with colour, like AR and IR beside it, so a guide that drew a white bulb for "on" would
     * disagree with the screen the pilot is holding.
     *
     * The amber state is the reason this is worth a helper rather than a tint on an icon: it is
     * the only pill on the flight screen that can say "the aircraft has not answered", and a
     * pilot who has never been shown it has no way to read it at night.
     *
     * ⚠ The lit and unknown states share the PLAIN bulb and take a tint; the dark state keeps
     * the slashed bulb UNTINTED. See FlightActivity.renderLightsButton for why those two facts
     * must stay together.
     */
    private fun lightsPill(dark: Boolean?): View = ImageView(this).apply {
        setImageResource(if (dark == true) R.drawable.ic_led_off else R.drawable.ic_led_on)
        setBackgroundResource(when (dark) {
            false -> R.drawable.bg_pill_active
            true -> R.drawable.bg_zoom_pill
            null -> R.drawable.bg_pill_unknown
        })
        imageTintList = when (dark) {
            false -> androidx.core.content.ContextCompat.getColorStateList(
                this@FieldGuideActivity, R.color.tp_state_go)
            null -> androidx.core.content.ContextCompat.getColorStateList(
                this@FieldGuideActivity, R.color.tp_state_unknown)
            true -> null
        }
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(4), dp(4), dp(4), dp(4))
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(26))
    }

    private fun gps(hasFix: Boolean): View = ImageView(this).apply {
        setImageResource(R.drawable.ic_gps)
        setColorFilter(if (hasFix) CONNECTED_GREEN else NO_FIX_GREY)
        layoutParams = iconParams()
    }

    private fun live(state: LiveToggleView.State): View =
        LiveToggleView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(66), dp(34))
            setState(state)
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Action-bar back arrow behaves the same as the system back gesture. */
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        private const val TAG = "TP2Guide"

        /**
         * Optional intent extra: open the guide scrolled to one entry rather than at the top.
         * Value is one of the ANCHOR_* constants, matched against the tag [entry] puts on its
         * card. An unknown or absent value opens at the top, which is the safe default — a
         * deep link that stops matching must not open a blank-looking screen.
         */
        const val EXTRA_SCROLL_TO = "scrollTo"

        /** The AR overlay entry, reached from the flight screen's AR menu. */
        const val ANCHOR_AR = "ar"

        /** Opens the guide at [anchor] (or the top if null). */
        @JvmStatic
        fun intent(context: android.content.Context, anchor: String? = null) =
            android.content.Intent(context, FieldGuideActivity::class.java).apply {
                if (anchor != null) putExtra(EXTRA_SCROLL_TO, anchor)
            }
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        // Same values the flight screen tints these with, so a state shown here is the state
        // the pilot will actually see.
        private val CONNECTED_GREEN = 0xFF4CAF50.toInt()
        private val DISCONNECTED_RED = 0xFFF44336.toInt()
        private val NO_FIX_GREY = 0xFFAAAAAA.toInt()
    }
}
