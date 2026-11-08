# Android System Link: preserve guests between matches

## Evidence and uncertainty

A user-reported eight-bot LAN game had ten players on two phones and ran about
nine minutes. The guest machine left eight seconds after game over; the host
reset to pregame five seconds later. A separate round logged a connection
reset while the host attempted to send updated lobby settings.

The host log alone cannot establish whether the guest tapped Leave,
lost its connection, or mishandled a transition message. The guest phone's
diagnostic log remains the missing conclusive evidence.

## Targeted fixes

- Previously Android nonhosts saw only LEAVE on the results screen; hosts
  saw both CONTINUE and LEAVE. The LEAVE button triggers the B-button path,
  which aborts the client session. Now guest phones show WAIT FOR HOST
  (a safe A-button no-op for clients) and LEAVE (explicit B-button exit).
  Host controls still say CONTINUE and LEAVE.
- The client-side transition-message handler used to reject a valid repeated
  switch-to-pregame packet after the client was already in pregame. The
  failure propagates out of the message loop and could end the connection.
  Valid repeated packets from the expected host are now no-ops.
- Invalid transition packets must never change the game state. A malformed
  packet is rejected; messages from other senders remain ignored.
- Protocol version 45 is unchanged. There are no wire-format changes;
  the updated guest can still connect to an Alpha 3 host.

## Tests

Source-extracted C tests cover a normal postgame-to-pregame transition,
valid duplicate, malformed transition, malformed duplicate, foreign sender,
and unexpected ingame transition. Two deliberately broken versions are
rejected by the same tests. Android guest button display is checked by
source regression assertions. An isolated Android build and real device
round-trip testing remain required.

## Two-phone acceptance test

1. Back up the guest phone's maps and saves before installing a newly
   debug-signed APK. Keep the currently working host on Alpha 3.
2. Join a System Link Slayer match, let it end normally.
3. On the guest tap WAIT FOR HOST, or simply wait. Do not tap LEAVE.
4. On the host tap CONTINUE. Verify the guest remains in the same session
   and both phones see the lobby and can start another match.
5. Repeat at least three full rounds including one zero-bot control.
6. If it fails, save debug.txt from BOTH phones before reinstalling; note
   whether the guest is at results, multiplayer list or main menu.

This remains a candidate mitigation until the on-device tests pass.
The separately identified corrupt Sidewinder map is not changed here.
