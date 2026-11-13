# v25: Indoor fallback for the 10-kill support reward

**Status: source candidate.** This is based on the successfully built v24
lobby/UI + recon-voice source `11d2d18`; it does not replace an installed
Android APK until the native Android signing/build gate passes.

## Reported real-game problem

The player achieved ten enemy kills without dying and heard the private
"Orbital strike authorized" announcement. Inside a building the activation
failed. Investigation shows v21-v24 did **not** require open sky; the
original input system required Zoom held plus a new Flashlight press,
first-person camera, valid nearby flat **structure floor** under the aim,
and unobstructed head-to-floor line of sight. A ceiling, wall or bad
floor hit could fail silently. That result must not waste the reward.

## v25 design: one entitlement, two context-sensitive options

- **Outdoors:** same 10-kill orbital strike as before. Look at an accessible
  structure **floor**, hold Zoom and tap Flashlight. The host validates the
  floor and target within 128 metres. After the normal ~3.33-second public
  warning, one attack kills enemies within a 14-metre radius who lack
  intervening structure cover. Existing authorization/warning voice assets
  are byte-for-byte untouched.
- **Under a roof:** the same activation automatically becomes a **Breach
  Pulse**. No floor aiming is required; the pulse is anchored to the
  player's current host-validated head position when activated. After
  **45 simulation ticks (~1.5 seconds)** it strikes living enemies
  within **10 metres** with clear structure line of sight. Walls, friendlies
  and the owner are excluded. One earned entitlement authorizes either
  pulse or orbital, **never both**.
- Under-roof determination casts a structure-only vertical ray up to 32
  native world units (~98 metres) from the living player's head, checking
  front and back-facing BSP surfaces. The host independently performs
  the same geometric check, player/unit/machine/team/life validation,
  10-kill entitlement validation and proximity check before consuming
  an authenticated client's reliable request. No map-name blacklist, trust
  in a client-supplied "indoors" flag, or client-side damage is involved.
- An invalid request consumes nothing. The client can manually retry after
  a 90-tick timeout. Death, team/machine change, map reset or mode disable
  still cancel pending damage. Simultaneous impacts remain bounded.
- The HUD now labels the available mode:
  `BREACH PULSE READY: HOLD ZOOM + TAP FLASHLIGHT` under a roof, or
  `ORBITAL READY: AIM GROUND, ZOOM + FLASHLIGHT` outside. After
  accepted activation, public warning text distinguishes the two modes.

## Original offline radio audio

Both v25 Breach phrases were generated offline from the same British woman
voice model `bf_emma` (en-gb, 0.96 speed, Kokoro), 24-kHz mono PCM16.
A private 10-kill alert says **"Breach pulse ready"** when inside, and
the public announcement after host authorization says
**"Breach pulse incoming"**. Existing 3/5/10 outdoor/orbital announcements
stay unchanged. Both audio resources have 450ms head and 500ms tail padding.

- `halo_reward_breach_ready.wav` SHA-256:
  `ee704c7068e41df95141c3fc9694dc9ebe20f8a8902a4b0b0181c7a0033410ef`
- `halo_reward_breach_incoming.wav` SHA-256:
  `f258227c21c2877808178d5dca81119dfb507ff67b2fa83d51b3d9fb394c55b1`

Native HUD awards 12 (private) and 13 (public) now select these two
extra WAVs via the existing JNI-to-Android SoundPool bridge.
The ordinary 10/11 orbital cues remain available and unchanged.

## Wire compatibility

All three existing message types 98 (grant), 99 (request) and 100 (warning)
retain their 36-byte sizes. Only `kind` gains 4 (indoor request) and
5 (indoor public warning), both strictly scoped to the existing directions
and authenticated reliable streams. The host uses its *own* unit position
as the impact center, never client coordinates. The host awards at most
one effect and gates damage to enemies only.

Because the meaning of existing network messages changed, **the gameplay
network protocol increases from 47 to 48**. **All LAN players must install
v25 to join a v25 host.** The separate v23+ LAN update feature can offer
a newer version from the hosting phone before joining.

## Source qualification

```sh
CC=cc python3 -m unittest -q \
  tools.test_killstreak_orbital \
  tools.test_killstreak_recon \
  tools.test_killstreak_rewards \
  tools.test_lobby_bot_count \
  tools.test_commander_roles \
  tools.test_lan_apk_update
```

The C harness compiles the **actual** orbital/reward/input/instant-damage
source against a fake world, with explicit roof geometry. The new `breach`
case verifies floor-target independence, private/public audio ordinals,
warning timing, enemy-only damage, walls, retries, multiple event
presentation and rejection of missing roofs, stale points and indoor
orbital attempts. Negative-control source mutations must also fail.

**Physical Android/LAN acceptance is still required:** test a fully
indoors map, a ceilinged area within an outdoor map, an outdoor field,
roof transition after award, enemy behind a wall, teammate next to the
pulse, replay from guests, remote device with different player salts,
non-Halo Custom Edition maps, and speaker playback of the two new phrases.

Ceilings that have no usable structure collision or are beyond the
~98-metre upward ray may not classify as covered. This is a useful
geometric fallback, not a guarantee every custom indoor map is
compatible. The pulse currently has no new VFX particle; HUD and audio
signal activation. Do not claim physical success until tested.
