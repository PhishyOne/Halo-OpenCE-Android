/* The active BSP's 3D diagonal in Halo world units. A zero result keeps stock. */
#ifndef MAP_DRAW_DISTANCE_H
#define MAP_DRAW_DISTANCE_H
#include <float.h>

static inline real map_draw_diagonal(const real_rectangle3d *box)
{
	real x = box->x1 - box->x0;
	real y = box->y1 - box->y0;
	real z = box->z1 - box->z0;
	real squared = x*x + y*y + z*z;

	if (!(x >= 0 && y >= 0 && z >= 0 && squared > 0 && squared <= FLT_MAX))
		return 0;
	return square_root(squared);
}

/* Local fog volumes (including underwater) retain their authored range. */
static inline void map_draw_apply(real distance, real *visibility_far, real *projection_far,
	boolean outdoors, real *atmosphere_end)
{
	if (distance > 0)
	{
		if (*visibility_far < distance) *visibility_far = distance;
		if (*projection_far < distance) *projection_far = distance;
		if (outdoors && *atmosphere_end > 0 && *atmosphere_end < distance)
			*atmosphere_end = distance;
	}
}
#endif
