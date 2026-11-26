#ifndef HALO_NETWORK_GROUP_RATING_H
#define HALO_NETWORK_GROUP_RATING_H
#ifdef HALO_ANDROID
void group_rating_start(void);
void group_rating_update(long mode);
void group_rating_finish(long mode);
void group_rating_abandon(void);
void group_rating_host_end(void);
int group_rating_handle(long machine,const void *data,unsigned short size,int reliable);
#endif
#endif
