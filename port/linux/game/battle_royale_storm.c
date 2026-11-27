#include "battle_royale_storm.h"

struct storm_stage { float radius_fraction; int hold, close, damage; };
/* Seconds of looting/relocation, seconds of movement, raw environmental damage per second.
   The final clearing is chosen from the loaded scenario. Interpolating all centres toward it
   keeps every target contained in the previous circle, including between simulation ticks. */
static const struct storm_stage stages[] = {
	{.60f, 90, 150, 3}, {.32f, 45, 135, 5}, {.14f, 35, 105, 8},
	{.045f, 25, 75, 12}, {0.f, 20, 60, 18}
};

void br_storm_sample(const struct battle_royale_circle *island, const struct battle_royale_point *finish,
	int elapsed_ticks, struct br_storm_view *view)
{
	struct battle_royale_circle start = *island;
	int i;
	for (i = 0; i < (int)(sizeof(stages)/sizeof(stages[0])); ++i) {
		const struct storm_stage *s = &stages[i];
		struct battle_royale_zone zone;
		int close_end = (s->hold + s->close) * 30;
		zone.start = start;
		zone.end.x = finish->x + (island->x - finish->x) * s->radius_fraction;
		zone.end.y = finish->y + (island->y - finish->y) * s->radius_fraction;
		zone.end.radius = island->radius * s->radius_fraction;
		zone.hold_ticks = s->hold * 30;
		zone.close_ticks = s->close * 30;
		zone.damage_interval_ticks = 30;
		view->next = zone.end;
		view->stage = i + 1;
		view->damage = s->damage;
		if (elapsed_ticks < close_end) {
			int holding = elapsed_ticks < zone.hold_ticks;
			view->state = holding ? BR_HOLDING : BR_CLOSING;
			view->seconds_left = ((holding ? zone.hold_ticks : close_end) - elapsed_ticks + 29) / 30;
			view->current = battle_royale_circle_at(&zone, elapsed_ticks);
			return;
		}
		start = zone.end;
		elapsed_ticks -= close_end;
	}
	view->state = BR_FINAL;
	view->seconds_left = 0;
	view->current = view->next;
}
