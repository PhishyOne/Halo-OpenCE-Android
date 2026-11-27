/* The host selects one straight crossing for the whole roster. Scenario flight
   markers supply the altitude and a fallback for sparsely authored maps. */
#include "battle_royale_scatter.h"
#include <math.h>

enum br_island_edge br_route_edge(const struct br_drop_route *r, struct battle_royale_point p)
{
	float distances[4] = {
		(p.x-r->lower.x)/(r->upper.x-r->lower.x), (r->upper.x-p.x)/(r->upper.x-r->lower.x),
		(p.y-r->lower.y)/(r->upper.y-r->lower.y), (r->upper.y-p.y)/(r->upper.y-r->lower.y)
	};
	int best=0, i;
	for (i=1; i<4; ++i) if (distances[i]<distances[best]) best=i;
	return (enum br_island_edge)best;
}

static float signed_draw(uint32_t *rng)
{
	return (int)(battle_royale_random(rng) >> 16) / 32768.f - 1.f;
}

/* Intersect the infinite line with one pair of sides, narrowing its interval. */
static int clip_axis(float origin, float delta, float lo, float hi, float *enter, float *exit)
{
	float a, b, tmp;
	if (delta==0.f) return origin>=lo && origin<=hi;
	a=(lo-origin)/delta;
	b=(hi-origin)/delta;
	if (a>b) { tmp=a; a=b; b=tmp; }
	if (a>*enter) *enter=a;
	if (b<*exit) *exit=b;
	return *enter<*exit;
}

static int draw_crossing(struct br_drop_route *r, const struct br_drop_pool *pool, uint32_t *rng, int use_pair)
{
	float width=r->upper.x-r->lower.x, depth=r->upper.y-r->lower.y;
	float size=width>depth ? width : depth;
	struct battle_royale_point a, b;
	float dx, dy, length, enter=-1.e16f, exit=1.e16f;
	if (pool->site_count) {
		a=pool->sites[battle_royale_random(rng) % pool->site_count];
	} else {
		a.x=r->lower.x + (.5f+.25f*signed_draw(rng))*width;
		a.y=r->lower.y + (.5f+.25f*signed_draw(rng))*depth;
	}
	if (use_pair) {
		b=pool->sites[battle_royale_random(rng) % pool->site_count];
		dx=b.x-a.x; dy=b.y-a.y;
		if (!(dx*dx+dy*dy >= .04f*size*size)) return 0;
		b.x+=12.f*signed_draw(rng);
		b.y+=12.f*signed_draw(rng);
	} else {
		b.x=a.x+width*signed_draw(rng);
		b.y=a.y+depth*signed_draw(rng);
	}
	a.x+=12.f*signed_draw(rng);
	a.y+=12.f*signed_draw(rng);
	dx=b.x-a.x; dy=b.y-a.y;
	length=(float)sqrt(dx*dx+dy*dy);
	if (!(length>0.f)) return 0;
	dx/=length; dy/=length;
	/* Leave one percent at each side for the hull and the rear ramp. */
	if (!clip_axis(a.x,dx,r->lower.x+.01f*width,r->upper.x-.01f*width,&enter,&exit) ||
	    !clip_axis(a.y,dy,r->lower.y+.01f*depth,r->upper.y-.01f*depth,&enter,&exit) ||
	    !(exit-enter >= .90f*size))
		return 0;
	r->start.x=a.x+enter*dx; r->start.y=a.y+enter*dy;
	r->end.x=a.x+exit*dx; r->end.y=a.y+exit*dy;
	r->forward_x=dx;
	r->forward_y=dy;
	return 1;
}

/* A whole clearing counts only if even its outside spots can be reached from
   the furthest seat on either side. Reserve another tick for release rounding. */
static int clearing_in_reach(const struct br_drop_route *r, struct battle_royale_point p)
{
	float across=(p.y-r->start.y)*r->forward_x-(p.x-r->start.x)*r->forward_y;
	float length=(r->end.x-r->start.x)*r->forward_x+(r->end.y-r->start.y)*r->forward_y;
	float altitude=r->start.z<r->end.z ? r->start.z : r->end.z;
	float reach=(altitude-p.z-1.5f)*(.22f/.52f)-3.f;
	int latest;
	float margin=1.7f+br_drop_side(15)+length/BR_ROUTE_TICKS;
	/* The 1.7-unit circle encloses the sixteen cell centers plus their jitter. */
	if (!(p.x-1.7f>=r->lower.x && p.x+1.7f<=r->upper.x &&
	      p.y-1.7f>=r->lower.y && p.y+1.7f<=r->upper.y &&
	      p.z>=r->lower.z && p.z<=r->upper.z && fabs(across)+margin<=reach))
		return 0;
	latest=br_pass_tick(r,p,br_drop_aft(HALO_PORT_MAXIMUM_NETWORK_PLAYERS-1)+1.7f);
	return latest>0 && latest<BR_LAST_JUMP && br_pass_tick(r,p,br_drop_aft(0)-1.7f)>BR_DROP_OPEN;
}

int br_choose_route(struct br_drop_route *route, const struct br_drop_pool *pool, uint32_t seed)
{
	struct br_drop_route candidate=*route;
	uint32_t rng=seed ^ 0xa511e9b3u; /* independent from the bot scatter stream */
	int required=(HALO_PORT_MAXIMUM_NETWORK_PLAYERS+BR_CLEARING_SPOTS-1)/BR_CLEARING_SPOTS;
	int i, attempt;
	if (required>pool->site_count) required=pool->site_count;
	if (!(route->upper.x>route->lower.x && route->upper.y>route->lower.y)) return 0;
	if (!rng) rng=1u;
	for (attempt=0; attempt<96; ++attempt) {
		int clearings=0;
		/* Prefer passes between distinct destinations. Maps with just one
		   settlement can still take a long flight from a random direction. */
		if (!draw_crossing(&candidate,pool,&rng,pool->site_count>=2 && attempt<80)) continue;
		for (i=0; i<pool->site_count && clearings<required; ++i)
			clearings+=clearing_in_reach(&candidate,pool->sites[i]);
		if (clearings>=required) {
			*route=candidate;
			return 1;
		}
	}
	return 0;
}
