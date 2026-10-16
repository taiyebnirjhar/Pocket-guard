package com.taiyeb.pocketguard

import kotlinx.coroutines.flow.MutableStateFlow

enum class Phase {
    OFF,
    WAITING_FOR_POCKET,
    IN_POCKET,
    WARNING,
    SIREN,
    /** Fake shutdown: the phone pretends to be off while it keeps tracking. */
    STEALTH,
    ;

    val isAlarm get() = this == WARNING || this == SIREN || this == STEALTH
}

/** What caused an alarm. */
enum class Trigger {
    POCKET_REMOVAL,
    POWER_MENU,
    AIRPLANE_MODE,
    CHARGER_UNPLUGGED,
    EARBUDS_DISCONNECTED,
}

object GuardState {
    val phase = MutableStateFlow(Phase.OFF)
}
