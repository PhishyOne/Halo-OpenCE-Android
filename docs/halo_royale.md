# Halo Royale gameplay baseline

Use PUBG: BATTLEGROUNDS as the reference for the battle royale loop. Players choose where to leave the transport, search for supplies after landing, and survive a shrinking play area. Evaluate Halo-specific additions against that experience. Echo 419 is our transport. [PUBG gameplay overview](https://pubg.com/en/game-info/overview)

## Echo 419 flight paths

The host chooses a straight crossing before anyone boards and announces which sides of the island Echo 419 will enter and leave. On the 8 km island, a route must span at least 7.2 km, which leaves at least 6.2 km between the opening of the ramp and the final jump. The route can skip Orchard Crossing. The pilot calls the village only if the flight actually passes nearby.

The host samples separated landing areas, varies the line between them, and extends it to the island edges. It checks that a full roster has room to land before accepting the crossing. A small map with insufficient reachable landing areas can use its authored fallback. The logged match seed reproduces the chosen route and the bot assignments.

## Bot drops

Players can read the flight path and decide whether to risk a busy settlement or take a quieter start. Give bots the same range of possible destinations. Nearby bots can contest the same clearing and building. Some places should be empty in any given small match.

Author clearings near indoor or outdoor supplies. Larger destinations can offer more clearings and attract more traffic. The host randomly selects reachable landing spots without replacement, varies the exit timing and leaves room between bodies.

## East Millworks

The east road leaves Orchard Crossing, crosses a 34 m bridge over the millrace, and passes six furnished mill buildings before continuing to East Farm. A side path joins both stairways of a buried shelter. Supplies are placed inside the mill, in the shelter storeroom and around loading piles. Nine woodlots break up the open ground. The water level and afternoon sky stay fixed. The Highlands revision adds rolling collision terrain, a tiled grass surface, shrubs, grass tussocks and scattered trees. Ground contacts use the original dirt and stone footstep materials.

Across the island, 36 clearings give the host 576 possible bot landing spots. The host places 57 pickups among 96 marked positions each round. The multiplayer scenario has three Warthogs, two Ghosts and one unplaced campaign Pelican which the host creates when the flight starts.

## Play East Millworks offline on Android

In Tools, choose **Import Maps** and select `East-Millworks-Highlands.zip`. Open **Offline Match → Bot Match**, select **East Millworks (Highlands)** as the map and choose **Elimination** for a one-life free-for-all. Choose **Slayer** to explore with respawns. Three bots make a small first match. Tap **Play**, then press **Jump** when Echo 419 announces the open drop zone. The end of the flight releases anyone still aboard.

Players and bots board empty-handed. Supplies placed before the flight stay on the island until claimed. Dropped gear follows the usual cleanup rules. In Elimination the five shrinking circles start after insertion. The upper-right map shows your position, the current circle and the next safe area. Tap it to open the island map. Watch the upper-left timer and the blue haze when outside the circle. Slayer keeps the island open for exploration.

## Build the multiplayer map

Generate a `royale_crossroads` cache with StageKit. Compile it with Invader's `invader-extract` and `invader-build` on PATH, Python with `reclaimer==2.11.2`, and a private Custom Edition tag set containing the campaign Pelican. The English Custom Edition release r220 of `halopc-restored` supplies the tested stock tags. The map embeds the resources it uses.

```sh
stagekit royale_crossroads ./millworks-stage.map
python3 tools/stagekit/royale_release.py \
  --geometry ./millworks-stage.map --tags /path/to/hek/tags \
  --sky 'sky\clear afternoon\clear afternoon' \
  --output ./custom_maps/east_millworks.map
```

The builder validates the source tag table, preserves the authored collision and placement markers, and checks that the compiled multiplayer cache contains the required stock and authored tags before replacing the destination. The source repository contains the island and builder. Keep the compiled map and retail tags local.
