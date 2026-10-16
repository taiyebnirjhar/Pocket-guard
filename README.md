# Pocket Guard

Android anti-pickpocket alarm. Arm it before you get into a crowd. If the phone
leaves your pocket or bag without being unlocked, it beeps (in your earbuds if
connected), then starts a full-volume siren that the volume buttons can't silence.

## How it works

1. **Armed**: waiting for the phone to go into a pocket (proximity sensor covered for 1.5 s).
2. **Protected**: a short buzz confirms it.
3. **Pulled out while locked** (proximity uncovered for 0.4 s, plus movement or a jump in light):
   warning beeps and vibration.
4. **No unlock within the grace period** (1–5 s): siren on the alarm stream, torch strobe,
   full-screen "THIS PHONE IS STOLEN" over the lock screen, volume locked at max.
5. **Unlock** (fingerprint, face, PIN) at any point stops everything and re-arms automatically.

## Install

Every push builds an APK with GitHub Actions and publishes it under
[Releases](../../releases) as `pocket-guard-build-N`. Open it on the phone, download
the APK, and allow installing from your browser when Android asks. Builds share one
signing key, so new builds install over old ones.

## Known limits (v0.1)

- No app can block power-off or a forced restart. Power-menu detection is planned for v0.2.
- Some phones only deliver proximity readings while the screen is on. Use the
  in-app **Sensor check** with the screen off in a pocket to find out.
- Xiaomi, Realme, Oppo and Vivo kill background apps: complete the setup checklist.
