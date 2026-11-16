/* The exact production sight function with a collision-query fixture.
 * Collision exclusions skip one selected object's subtree, matching
 * object_test_vector in source/physics/collisions.c. */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

typedef int boolean;
typedef struct { float x,y,z; } real_point3d;
typedef struct { float i,j,k; } real_vector3d;
#define NONE (-1L)
#define TRUE 1
#define FALSE 0
#define _object_mask_vehicle 4
#define _collision_test_for_line_of_sight_flags 7
#define _collision_result_object 1
#define _collision_result_structure 2
struct object_datum { struct { long parent_object_index; int type; } object; };
struct collision_result { int type; long object_index; };
static struct object_datum objects[8];
static struct { int vehicle_los_visible, vehicle_los_blocked; } bots_globals;
static int obstruction; /* 0 none, 1 wall, 2 target vehicle, 3 unrelated vehicle */
static long last_ignored;
static struct object_datum *object_get(long index) {
    if(index<0 || index>=8) abort();
    return &objects[index];
}
static struct object_datum *object_try_and_get(long index) {
    return index>=0 && index<8 ? &objects[index] : NULL;
}
static struct object_datum *object_try_and_get_and_verify_type(long index,int mask) {
    struct object_datum *object=object_try_and_get(index);
    return object && object->object.type==mask ? object : NULL;
}
static boolean collision_test_vector(int flags,real_point3d const *start,
    real_vector3d const *ray,long ignore,struct collision_result *result) {
    (void)flags;(void)start;(void)ray;
    last_ignored=ignore;
    /* Shooter=0. His own parent vehicle=1 obstructs any ray from the
     * seated Spartan unless that vehicle subtree is explicitly excluded. */
    if(objects[0].object.parent_object_index==1 && ignore!=1) {
        result->type=_collision_result_object; result->object_index=1; return TRUE;
    }
    if(obstruction==1) {
        result->type=_collision_result_structure; result->object_index=NONE; return TRUE;
    }
    if(obstruction==2 || obstruction==3) {
        result->type=_collision_result_object;
        result->object_index=obstruction==2 ? 3 : 4; return TRUE;
    }
    return FALSE;
}
#include "production_vehicle_los.inc"
#define CHECK(x) do { if(!(x)){fprintf(stderr,"line %d: %s\n",__LINE__,#x); return 1;} }while(0)
static void reset(void) {
    int i;
    memset(objects,0,sizeof(objects));memset(&bots_globals,0,sizeof(bots_globals));
    for(i=0;i<8;i++) objects[i].object.parent_object_index=NONE;
    objects[1].object.type=objects[3].object.type=objects[4].object.type=_object_mask_vehicle;
    obstruction=0;last_ignored=NONE;
}
int main(void) {
    real_point3d eye={0,0,1}, target={30,0,1};
    reset();
    CHECK(bots_line_of_sight(0,&eye,&target,2));
    CHECK(last_ignored==0); /* on foot is unchanged */

    reset();objects[0].object.parent_object_index=1;
    CHECK(bots_line_of_sight(0,&eye,&target,2));
    CHECK(last_ignored==1); /* do not see own tank hull as an obstacle */

    obstruction=1;
    CHECK(!bots_line_of_sight(0,&eye,&target,2)); /* still blocked by a wall */
    obstruction=3;
    CHECK(!bots_line_of_sight(0,&eye,&target,2)); /* other vehicles still block */

    objects[2].object.parent_object_index=3;obstruction=2;
    CHECK(bots_line_of_sight(0,&eye,&target,2));
    CHECK(bots_globals.vehicle_los_visible==1);
    obstruction=1;
    CHECK(!bots_line_of_sight(0,&eye,&target,2));
    CHECK(bots_globals.vehicle_los_blocked==1);

    reset();objects[0].object.parent_object_index=1;objects[1].object.type=0;
    CHECK(!bots_line_of_sight(0,&eye,&target,2));
    CHECK(last_ignored==0); /* nonvehicle parent does not gain an exception */

    reset();objects[0].object.parent_object_index=99;
    CHECK(bots_line_of_sight(0,&eye,&target,2));
    CHECK(last_ignored==0); /* invalid parent safely ignored by lookup */

    reset();
    CHECK(bots_line_of_sight(NONE,&eye,&target,2));
    CHECK(last_ignored==NONE);
    puts("PASS vehicle sight: own hull excluded, walls/other objects preserved, infantry unchanged");
    return 0;
}
