"""Compile the actual stock Breach visual tag selection without retail maps.

A two-grenade fake-world is enough to catch bad tag references, missing map
assets and accidental gameplay side effects. Android rendering still needs
physical LAN acceptance.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
GAME = ROOT / "port/linux/game"
SOURCE = GAME / "killstreak_breach_vfx.c"
ORBITAL = GAME / "killstreak_orbital.c"

TEST_HARNESS = r"""
#include <stdio.h>
#include <stddef.h>
#include <string.h>
#define NONE (-1)
#define TRUE 1
#define PROJECTILE_DEFINITION_TAG 1
#define EFFECT_DEFINITION_TAG 2
#define _unit_grenade_human_fragmentation 0
#define _unit_grenade_covenant_plasma 1
#define CHECK(c) do { if(!(c)){ fprintf(stderr,"line %d: %s\n",__LINE__,#c); return 1;} } while(0)
typedef struct { float x,y,z; } real_point3d;
typedef struct { float i,j,k; } real_vector3d;
struct tag_reference { unsigned long group_tag; long index; };
struct tag_block { long count; void *address; };
struct game_globals_grenade { struct tag_reference projectile; };
struct game_globals { struct tag_block grenades; };
struct projectile_definition { struct { struct tag_reference effect; } projectile; };
static struct game_globals globals;
static struct game_globals_grenade grenades[2];
static struct projectile_definition projectiles[2];
static long requested_effect;
static int effects;
static real_point3d last_position;
static struct game_globals *scenario_get_game_globals(void) { return &globals; }
#define TAG_BLOCK_GET_ELEMENT(b,i,t) (&((t *)((b)->address))[i])
#define projectile_definition_get(i) (&projectiles[i])
static long effect_new_unattached_from_markers(long tag,long owner,
    real_vector3d const *velocity,short count,char const **markers,
    real_point3d const *points,real_vector3d const *directions,
    float a,float b,void const *color,void const *impulse,int deterministic)
{
    (void)owner; (void)velocity; (void)color; (void)impulse;
    if(count!=2 || !markers || strcmp(markers[0],"")!=0 ||
       strcmp(markers[1],"gravity")!=0 || a!=0 || b!=0 || !deterministic ||
       directions[0].k!=1 || directions[1].k!=-1)
        return NONE;
    effects++;
    requested_effect=tag;
    last_position=points[0];
    return 1;
}
#include "original_stock_vfx.inc"
static void reset(void)
{
    memset(&globals,0,sizeof(globals));
    memset(grenades,0,sizeof(grenades));
    memset(projectiles,0,sizeof(projectiles));
    globals.grenades.count=2;
    globals.grenades.address=grenades;
    grenades[0].projectile.group_tag=PROJECTILE_DEFINITION_TAG;
    grenades[0].projectile.index=0;
    grenades[1].projectile.group_tag=PROJECTILE_DEFINITION_TAG;
    grenades[1].projectile.index=1;
    projectiles[0].projectile.effect.group_tag=EFFECT_DEFINITION_TAG;
    projectiles[0].projectile.effect.index=123;
    projectiles[1].projectile.effect.group_tag=EFFECT_DEFINITION_TAG;
    projectiles[1].projectile.effect.index=456;
    effects=0;
    requested_effect=NONE;
    memset(&last_position,0,sizeof(last_position));
}
int main(void)
{
    real_point3d origin={5.0f,-8.0f,2.5f};
    reset();
    killstreak_breach_vfx_spawn(&origin);
    CHECK(effects==1 && requested_effect==456 &&
        last_position.x==5.0f && last_position.z==2.5f);
    reset(); projectiles[1].projectile.effect.index=NONE;
    killstreak_breach_vfx_spawn(&origin);
    CHECK(effects==1 && requested_effect==123); /* stock frag fallback */
    reset(); grenades[1].projectile.group_tag=0;
    killstreak_breach_vfx_spawn(&origin);
    CHECK(effects==1 && requested_effect==123);
    reset(); projectiles[0].projectile.effect.index=NONE;
    projectiles[1].projectile.effect.index=NONE;
    killstreak_breach_vfx_spawn(&origin);
    CHECK(effects==0);
    reset(); globals.grenades.address=NULL;
    killstreak_breach_vfx_spawn(&origin);
    CHECK(effects==0);
    reset(); globals.grenades.count=0;
    killstreak_breach_vfx_spawn(&origin);
    CHECK(effects==0);
    reset(); killstreak_breach_vfx_spawn(NULL);
    CHECK(effects==0);
    puts("PASS stock plasma/frag fallback, safe missing tags, exact visual point");
    return 0;
}
"""

class StockBreachVisualTest(unittest.TestCase):
    def test_reuse_stock_effect_tag_and_no_new_damage(self):
        content = SOURCE.read_text()
        self.assertIn("scenario_get_game_globals()", content)
        self.assertIn("_unit_grenade_covenant_plasma", content)
        self.assertIn("_unit_grenade_human_fragmentation", content)
        self.assertIn("projectile->projectile.effect.index", content)
        self.assertIn("effect_new_unattached_from_markers(", content)
        self.assertIn('markers[2] = { "", "gravity" }', content)
        self.assertNotIn("damage_kill_object_for_player", content)
        self.assertNotIn("area_of_effect_cause_damage", content)
        self.assertNotIn("distributed_send(", content)
        orbital = ORBITAL.read_text()
        self.assertIn("killstreak_breach_vfx_spawn(&visual->effect_position)", orbital)
        self.assertIn("effect_message.kind = 6;", orbital)
        self.assertIn("w->serial != m->serial || w->effect_seen", orbital)
        self.assertIn("m->header.game_time < w->end", orbital)
        self.assertIn("effect_message.target = strike.target", orbital)
        self.assertIn("m.kind == 3 || m.kind == 5 || m.kind == 6", orbital)

    def test_stock_vfx_c_harness(self):
        cc = os.environ.get("CC") or shutil.which("gcc") or shutil.which("cc")
        self.assertIsNotNone(cc, "C compiler needed")
        with tempfile.TemporaryDirectory(prefix="halo-stock-breach-") as temp:
            directory = Path(temp)
            source = "\n".join(line for line in SOURCE.read_text().splitlines()
                if not line.strip().startswith("#include "))
            (directory / "original_stock_vfx.inc").write_text(source)
            (directory / "main.c").write_text(TEST_HARNESS)
            exe = directory / "test"
            build = subprocess.run([cc, "-std=gnu89", "-Wall", "-Wextra",
                "-Werror", str(directory / "main.c"), "-o", str(exe)],
                capture_output=True, text=True)
            self.assertEqual(build.returncode, 0, build.stderr)
            result = subprocess.run([str(exe)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("PASS", result.stdout)

if __name__ == "__main__":
    unittest.main()
