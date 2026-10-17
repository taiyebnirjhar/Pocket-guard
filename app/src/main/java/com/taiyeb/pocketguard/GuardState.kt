package com.taiyeb.pocketguard

import kotlinx.coroutines.flow.MutableStateFlow

enum class Phase {
    OFF,
    WAITING_FOR_POCKET,
    IN_POCKET,

    /** Lying face-down on a table or desk: lifting, tilting or pushing it off sets it off. */
    ON_TABLE,
    WARNING,
    SIREN,
    ;

    val isAlarm get() = this == WARNING || this == SIREN

    /** Watching for theft: in a pocket or bag, or on a table. */
    val isProtected get() = this == IN_POCKET || this == ON_TABLE
}

/** Which rule confirmed a theft, for the log and for how the warning sounds. */
enum class Evidence {
    LIGHT, JOLT, PROXIMITY_ONLY, UNCOVERED, TILT_OUT, OUT_AND_IN,
    TABLE_TILT, TABLE_LIFT, TABLE_HELD, TABLE_POSE, TABLE_FALL, TABLE_UNCOVER, TABLE_SLIDE, MOTION_UNKNOWN,
    ;

    /** The person holding it is likely looking at it: give the owner a moment to unlock in silence. */
    val ownerLikelyLooking get() = this == UNCOVERED || this == TILT_OUT || name.startsWith("TABLE_")
}

/** What caused an alarm. */
enum class Trigger {
    POCKET_REMOVAL,
    AIRPLANE_MODE,
    CHARGER_UNPLUGGED,
    EARBUDS_DISCONNECTED,
    /** The phone was restarted (or the app killed) while an alarm was running. */
    RESTARTED,

    /** Only in history saved by older builds, which had a power-menu alarm. */
    POWER_MENU,

    /** Lifted, tilted or pushed off the table it lay face-down on. */
    TABLE_MOVED,

    /** The owner called for help (both volume keys held, the notification, the tile or the app). */
    SOS,
}

/** What the alarm screen shows about the alerts to emergency contacts. */
data class AlertUi(
    val names: String,
    val secondsLeft: Int,
    val sent: Boolean,
    /** The Play build can't send SMS itself: the owner taps to open the SMS app, prefilled. */
    val needsTap: Boolean,
)

object GuardState {
    val phase = MutableStateFlow(Phase.OFF)

    /** [android.os.SystemClock.elapsedRealtime] when the guard was armed; null while off. */
    val armedAt = MutableStateFlow<Long?>(null)

    /**
     * False when the alarm started while the phone wasn't locked with a PIN/pattern (screen-lock
     * delay, Smart Lock): then unlocking proves nothing and only DISARM in the app stops it.
     */
    val unlockStopsAlarm = MutableStateFlow(true)

    /** What the running alarm is for (null when none), so the alarm screen can say the right thing. */
    val alarmTrigger = MutableStateFlow<Trigger?>(null)

    /** The SOS alerts to emergency contacts, while SOS runs and they are on (null otherwise). */
    val alert = MutableStateFlow<AlertUi?>(null)
}
