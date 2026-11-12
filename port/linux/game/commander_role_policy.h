/* Portable, allocation-free role identity checks shared by host and guests.
 * A network message cannot grant a different human or a recycled roster slot.
 * Never compare player datum salts across machines; the roster slot, team and
 * UTF-16 name are compared against the local authenticated game roster. */
#ifndef HALO_COMMANDER_ROLE_POLICY_H
#define HALO_COMMANDER_ROLE_POLICY_H

#define COMMANDER_ROLE_NONE 0xFFFFu
#define COMMANDER_ROLE_NAMES 12

static int commander_role_identity_valid(unsigned slot, unsigned team,
    wchar_t const *name, unsigned player_capacity)
{
    int i;
    if (!name)
        return 0;
    if (slot == COMMANDER_ROLE_NONE)
    {
        if (team != COMMANDER_ROLE_NONE)
            return 0;
        for (i=0; i<COMMANDER_ROLE_NAMES; i++)
            if (name[i] != 0)
                return 0;
        return 1;
    }
    return slot < player_capacity && team <= 1 && name[0] != 0;
}

static int commander_role_identity_matches(unsigned slot, unsigned team,
    wchar_t const *name, unsigned player_slot, unsigned player_team,
    wchar_t const *player_name, unsigned player_capacity)
{
    int i;
    if (!player_name ||
        !commander_role_identity_valid(slot,team,name,player_capacity) ||
        slot == COMMANDER_ROLE_NONE || slot != player_slot || team != player_team)
        return 0;
    for(i=0; i<COMMANDER_ROLE_NAMES; i++)
        if (name[i] != player_name[i])
            return 0;
    return 1;
}

#endif
