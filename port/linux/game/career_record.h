/* Android's local journal of host-frozen multiplayer results. */
#ifndef HALO_CAREER_RECORD_H
#define HALO_CAREER_RECORD_H
void career_record_start(void);
void career_record_update(long game_type, const char *map_name);
/* Called on postgame; waits for a final receipt and commits at most once. */
void career_record_postgame(long game_type, const char *map_name);
#endif
