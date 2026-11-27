# Halo Royale gameplay baseline

Use PUBG: BATTLEGROUNDS as the reference for the battle royale loop. Players choose where to leave the transport, search for supplies after landing, and survive a shrinking play area. Evaluate Halo-specific additions against that experience. Echo 419 is our transport. [PUBG gameplay overview](https://pubg.com/en/game-info/overview)

## Echo 419 flight paths

The host chooses a straight crossing before anyone boards and announces which sides of the island Echo 419 will enter and leave. On the 8 km island, a route must span at least 7.2 km, which leaves at least 6.2 km between the opening of the ramp and the final jump. The route can skip Orchard Crossing. The pilot calls the village only if the flight actually passes nearby.

The host samples separated landing areas, varies the line between them, and extends it to the island edges. It checks that a full roster has room to land before accepting the crossing. A small map with insufficient reachable landing areas can use its authored fallback. The logged match seed reproduces the chosen route and the bot assignments.

## Bot drops

Players can read the flight path and decide whether to risk a busy settlement or take a quieter start. Give bots the same range of possible destinations. Nearby bots can contest the same clearing and building. Some places should be empty in any given small match.

Author clearings near indoor or outdoor supplies. Larger destinations can offer more clearings and attract more traffic. The host randomly selects reachable landing spots without replacement, varies the exit timing and leaves room between bodies.
