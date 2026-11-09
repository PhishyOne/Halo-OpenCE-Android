/* Objective goal selection for experimental multiplayer bots.
   Pure, host-only policy; the game engine owns the objectives and scoring.
   Each bit in active_goals marks a game_engine goal index in use. */
#ifndef HALO_BOTS_OBJECTIVES_POLICY_H
#define HALO_BOTS_OBJECTIVES_POLICY_H

#define BOTS_OBJECTIVE_MAX_KING_HILLS 5

/* -1 means that there is no usable objective. Distribute bot indices across
   live hills, so Multi-Hill does not concentrate everybody in one place. */
static int bots_king_goal_slot(int bot_index, unsigned int active_goals)
{
    int count = 0, hill, order;
    for (hill = 0; hill < BOTS_OBJECTIVE_MAX_KING_HILLS; hill++)
        if (active_goals & (1u << hill))
            count++;
    if (!count)
        return -1;
    order = bot_index % count;
    for (hill = 0; hill < BOTS_OBJECTIVE_MAX_KING_HILLS; hill++)
        if ((active_goals & (1u << hill)) && order-- == 0)
            return hill;
    return -1;
}

/* CTF goal indices 0/1 are the actual flag positions by owning team.
   When an enemy flag has a carrier, index (enemy+2) is its capture stand.
   A carrying bot must go home; one in three other bots defends/intercepts
   at our flag, and the rest attack/escort at the enemy flag. */
static int bots_ctf_goal_slot(int bot_index, int team_index,
    int carrying_enemy_flag, unsigned int active_goals)
{
    int enemy;
    if (team_index != 0 && team_index != 1)
        return -1;
    enemy = 1 - team_index;
    if (carrying_enemy_flag) {
        if (active_goals & (1u << (enemy + 2)))
            return enemy + 2;
        /* If the capture marker has not been published yet, go to our
           own flag (or intercept whoever took it), never the enemy base. */
        return (active_goals & (1u << team_index)) ? team_index : -1;
    }
    if (bot_index % 3 == 0 && (active_goals & (1u << team_index)))
        return team_index;
    if (active_goals & (1u << enemy))
        return enemy;
    return (active_goals & (1u << team_index)) ? team_index : -1;
}

#endif
