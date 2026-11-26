#ifndef HALO_LAUNCHER_MATCH_PRESETS_H
#define HALO_LAUNCHER_MATCH_PRESETS_H
/* The full native built-in catalogue, independent of dedicated-server code. */
static boolean launcher_match_preset_build(const char *base,
    struct game_variant *variant, struct game_variant_options *options)
{
    if(!base)return FALSE;
    if(game_modes_gametype_by_name(base,variant,options))return TRUE;
    if(!strcmp(base,"slayer")) build_game_variant_slayer(variant);
    else if(!strcmp(base,"team_slayer")) build_game_variant_team_slayer(variant);
    else if(!strcmp(base,"slayer_pro")) build_game_variant_slayer_pro(variant);
    else if(!strcmp(base,"elimination")) build_game_variant_elimination(variant);
    else if(!strcmp(base,"phantoms")) build_game_variant_phantoms(variant);
    else if(!strcmp(base,"endurance")) build_game_variant_endurance(variant);
    else if(!strcmp(base,"rockets")) build_game_variant_rockets(variant);
    else if(!strcmp(base,"snipers")) build_game_variant_snipers(variant);
    else if(!strcmp(base,"stalker")) build_game_variant_stalker(variant);
    else if(!strcmp(base,"ctf")) build_game_variant_ctf(variant);
    else if(!strcmp(base,"ironctf")) build_game_variant_iron_ctf(variant);
    else if(!strcmp(base,"ctf_pro")) build_game_variant_ctf_pro(variant);
    else if(!strcmp(base,"invasion")) build_game_variant_invasion(variant);
    else if(!strcmp(base,"oddball")) build_game_variant_oddball(variant);
    else if(!strcmp(base,"team_oddball")) build_game_variant_team_oddball(variant);
    else if(!strcmp(base,"reverse_tag")) build_game_variant_reverse_tag(variant);
    else if(!strcmp(base,"accumulation")) build_game_variant_accumulation(variant);
    else if(!strcmp(base,"juggernaut")) build_game_variant_juggernaut(variant);
    else if(!strcmp(base,"king")) build_game_variant_king(variant);
    else if(!strcmp(base,"team_king")) build_game_variant_team_king(variant);
    else if(!strcmp(base,"king_pro")) build_game_variant_king_pro(variant);
    else if(!strcmp(base,"crazy_king")) build_game_variant_crazy_king(variant);
    else if(!strcmp(base,"race")) build_game_variant_race(variant);
    else if(!strcmp(base,"team_race")) build_game_variant_team_race(variant);
    else if(!strcmp(base,"rally")) build_game_variant_rally(variant);
    else if(!strcmp(base,"team_rally")) build_game_variant_team_rally(variant);
    else return FALSE;
    game_variant_options_default(variant,options);
    return TRUE;
}
#endif
