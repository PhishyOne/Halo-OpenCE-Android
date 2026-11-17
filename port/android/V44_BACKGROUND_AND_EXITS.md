# v44: screen-off LAN sessions and unfinished matches

LAN launches start a foreground service with CPU and Wi-Fi locks. SDL continues
processing events while the Android surface is hidden; the engine keeps network
simulation running, skips rendering, and defers map transitions until resume.
Offline play pauses without accumulating catch-up time. Closing the game releases
the locks; the service does not restart a dead game. The notification returns to
the existing game without changing the host's discovery/update configuration.

The host sends an unfinished career checkpoint approximately once per simulation
second. Each phone atomically replaces a small, fsynced pending record. A quit,
disconnect, or process death therefore retains the latest received checkpoint as
DNF / LOSS: participation and earned stats/XP count, but no completion or victory
bonus is awarded. Starting the next match preserves an outstanding pending record
in the history first. Completion upgrades the same stable record ID, preventing
double counting. Older checkpoints cannot overwrite a final receipt. Checkpoints
do not finalize highlight selection; completed-match highlights still include
events that happen later in the match.

The HC3 local format retains support for HC1/HC2 history. Wire protocol 54 requires
all peers to update together. This is local career accounting, not a shared,
tamper-proof leaderboard or a new skill-rating system.

## Validation

- Source regression suite: 134 tests and 78 subtests passed.
- Native ILP32 receipt/recovery tests cover checkpoint throttling, crash recovery,
  stable IDs, final replacement, stale receipts, and late highlight events.
- Java career tests cover pending-only recovery, DNF XP, final deduplication,
  torn records, and invalid unfinished wins.
- The swap test simulates surface loss during event pumping and verifies that
  swaps resume only after a context returns; removing the guard fails the test.
- Android Java sources compile against Android API 35; the web build passes.
- Signed Android packaging and its run/source identifiers are recorded in the
  delivered build report.

## Phone acceptance still required

1. Update host and guests, select LAN, and start a multiplayer match.
2. Lock the host for at least two minutes. Guests should keep playing. Unlock;
   verify movement, audio, touch controls, and rendering resume.
3. Repeat with a guest. Tap the session notification to return; verify host
   discovery and update sharing still work afterward.
4. Quit or terminate a guest during play. Open its career: one DNF / LOSS with
   retained stats, no finish/win bonus. Start and finish another match; confirm
   the previous DNF remains and the new match appears once.
5. Finish normally and confirm highlights include late events. Repeat offline
   lock/unlock: the world should remain paused without a catch-up burst.

Locks reduce Android background suspension; they cannot repair lost radio
connectivity, force-stop, or guarantee immunity to vendor battery management.
No reconnect grace period or host migration is introduced. A genuine disconnect
also counts as unfinished. The final second plus delivery latency may be absent
from the last checkpoint, and an exit before any checkpoint cannot be recorded.
Pending stats can appear as provisional DNF while the game is still running.
Long map-loading/screen-off races and per-device battery behavior require physical
testing. A corrupt pending record is preserved and blocks new career recording
instead of being silently overwritten. Checkpoint storage failures are logged
and retried at the next checkpoint rather than every rendered frame.
