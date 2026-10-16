package com.taiyeb.pocketguard

import kotlinx.coroutines.flow.MutableStateFlow

enum class Phase(val title: String, val hint: String) {
    OFF("Guard is off", "Tap ARM before you get into a crowd"),
    WAITING_FOR_POCKET("Armed", "Put your phone in your pocket or bag"),
    IN_POCKET("Protected", "Take it out and unlock to use it normally"),
    WARNING("Phone left your pocket!", "Unlock now or the siren starts"),
    SIREN("Theft alarm!", "Unlock the phone to stop the siren"),
}

object GuardState {
    val phase = MutableStateFlow(Phase.OFF)
}
