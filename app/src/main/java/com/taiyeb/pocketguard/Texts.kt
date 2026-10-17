package com.taiyeb.pocketguard

import androidx.annotation.StringRes

@StringRes
fun Phase.titleRes() = when (this) {
    Phase.OFF -> R.string.phase_off
    Phase.WAITING_FOR_POCKET -> R.string.phase_waiting
    Phase.IN_POCKET -> R.string.phase_in_pocket
    Phase.ON_TABLE -> R.string.phase_on_table
    Phase.WARNING -> R.string.phase_warning
    Phase.SIREN -> R.string.phase_siren
}

/** One or two words, for the status chip. */
@StringRes
fun Phase.shortRes() = when (this) {
    Phase.OFF -> R.string.tile_off
    Phase.WAITING_FOR_POCKET -> R.string.phase_waiting
    Phase.IN_POCKET -> R.string.phase_in_pocket
    Phase.ON_TABLE -> R.string.status_on_table
    Phase.WARNING -> R.string.status_warning
    Phase.SIREN -> R.string.history_level_siren
}

@StringRes
fun Phase.hintRes() = when (this) {
    Phase.OFF -> R.string.phase_off_hint
    Phase.WAITING_FOR_POCKET -> R.string.phase_waiting_hint
    Phase.IN_POCKET -> R.string.phase_in_pocket_hint
    Phase.ON_TABLE -> R.string.phase_on_table_hint
    Phase.WARNING -> R.string.phase_warning_hint
    Phase.SIREN -> R.string.phase_siren_hint
}

@StringRes
fun Trigger.labelRes() = when (this) {
    Trigger.POCKET_REMOVAL -> R.string.trigger_pocket
    Trigger.AIRPLANE_MODE -> R.string.trigger_airplane
    Trigger.CHARGER_UNPLUGGED -> R.string.trigger_charger
    Trigger.EARBUDS_DISCONNECTED -> R.string.trigger_earbuds
    Trigger.RESTARTED -> R.string.trigger_restarted
    Trigger.POWER_MENU -> R.string.trigger_power_menu
    Trigger.TABLE_MOVED -> R.string.trigger_table
    Trigger.SOS -> R.string.trigger_sos
}
