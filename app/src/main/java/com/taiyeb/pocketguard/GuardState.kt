package com.taiyeb.pocketguard

import kotlinx.coroutines.flow.MutableStateFlow

enum class Phase {
    OFF,
    WAITING_FOR_POCKET,
    IN_POCKET,
    WARNING,
    SIREN,
    ;

    val isAlarm get() = this == WARNING || this == SIREN
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
}

object GuardState {
    val phase = MutableStateFlow(Phase.OFF)

    /**
     * False when the alarm started while the phone wasn't locked with a PIN/pattern (screen-lock
     * delay, Smart Lock): then unlocking proves nothing and only DISARM in the app stops it.
     */
    val unlockStopsAlarm = MutableStateFlow(true)
}
