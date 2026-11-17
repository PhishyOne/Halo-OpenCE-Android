"""v33 regression checks for bot crews and armored ground-vehicle weapons.

Compile the pure production crew policy with a host C compiler, then
assert source wiring retains the game's legitimate seat actions,
host-authoritative target choice and unit driver/gunner control masks.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
HEADER=ROOT/"port/linux/game/bot_vehicle_crew_policy.h"
BOTS=(ROOT/"port/linux/game/bots.c").read_text()
UNITS=(ROOT/"source/units/units.c").read_text()
UNIT_HEADER=(ROOT/"source/units/units.h").read_text()

HARNESS=r"""
#include <math.h>
#include <stdio.h>
#include "bot_vehicle_crew_policy.h"
#define CHECK(x) do{ if(!(x)) { fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #x);return 1; } }while(0)
int main(void) {
    /* One in four objective bots can help with a turret; other bots
       still try to capture flags, hills, balls and checkpoints. */
    CHECK(!bot_crew_objective_candidate(-1,1));
    CHECK(!bot_crew_objective_candidate(0,0));
    CHECK(bot_crew_objective_candidate(0,1));
    CHECK(bot_crew_objective_candidate(1,0));
    CHECK(!bot_crew_objective_candidate(2,0));
    CHECK(!bot_crew_objective_candidate(3,0));
    CHECK(bot_crew_objective_candidate(5,1));

    /* Driver seats are limited to designated bots, turret and passenger
       seats remain governed by Halo's animation and Action prompt. */
    CHECK(!bot_crew_seat_role_allowed(1,0,0));
    CHECK(bot_crew_seat_role_allowed(1,0,1));
    CHECK(bot_crew_seat_role_allowed(0,1,0));
    CHECK(bot_crew_seat_role_allowed(0,0,0));

    /* Previously 0.02 units/tick even when a human drove. Permit
       nearby low-speed turrets only, not high-speed boarding. */
    CHECK(bot_crew_speed_safe(0,0,0));
    CHECK(bot_crew_speed_safe(0.0004f,1,0));
    CHECK(!bot_crew_speed_safe(0.001f,1,0));
    CHECK(bot_crew_speed_safe(0.01f,1,1));
    CHECK(!bot_crew_speed_safe(0.02f,1,1));
    CHECK(!bot_crew_speed_safe(0.01f,0,1));
    CHECK(!bot_crew_speed_safe(NAN,1,1));
    CHECK(!bot_crew_speed_safe(-1,1,1));

    CHECK(bot_crew_seat_score(5,0,1,1)<bot_crew_seat_score(4,1,0,0));
    CHECK(bot_crew_seat_score(5,0,1,0)>bot_crew_seat_score(5,0,1,1));
    CHECK(bot_crew_seat_score(5,0,0,0)>bot_crew_seat_score(5,0,1,0));
    CHECK(bot_crew_seat_score(NAN,0,1,1)>1000000.0f);

    /* A tank bot needs the actual gunner seat, real weapon,
       recent enemy LOS and a safe, forward-facing target. */
    CHECK(bot_crew_tank_fire_allowed(1,1,30.0f,0.1f,0.0f,1));
    CHECK(!bot_crew_tank_fire_allowed(0,1,30.0f,0.0f,0.0f,1));
    CHECK(!bot_crew_tank_fire_allowed(1,0,30.0f,0.0f,0.0f,1));
    CHECK(!bot_crew_tank_fire_allowed(1,1,3.0f,0.0f,0.0f,1));
    CHECK(!bot_crew_tank_fire_allowed(1,1,80.0f,0.0f,0.0f,1));
    CHECK(!bot_crew_tank_fire_allowed(1,1,30.0f,0.6f,0.0f,1));
    CHECK(!bot_crew_tank_fire_allowed(1,1,30.0f,0.0f,0.5f,1));
    CHECK(!bot_crew_tank_fire_allowed(1,1,30.0f,0.0f,0.0f,8));
    CHECK(!bot_crew_tank_fire_allowed(1,1,NAN,0.0f,0.0f,0));
    puts("Bot crew policy PASS (role, low-speed turret, priority, tank fire gate)");
    return 0;
}
"""

class CrewRegression(unittest.TestCase):
    def test_pure_c_rule_compiles_and_passes(self):
        cc=os.environ.get("CC") or shutil.which("cc")
        self.assertIsNotNone(cc)
        with tempfile.TemporaryDirectory(prefix="halo-crew-") as temp:
            folder=Path(temp)
            (folder/"test.c").write_text(HARNESS)
            compiled=subprocess.run([cc,"-std=gnu99","-Wall","-Wextra",
                "-Werror","-I",str(HEADER.parent),str(folder/"test.c"),
                "-lm","-o",str(folder/"test")],capture_output=True,text=True)
            self.assertEqual(compiled.returncode,0,compiled.stderr)
            done=subprocess.run([str(folder/"test")],capture_output=True,text=True)
            self.assertEqual(done.returncode,0,done.stderr)
            self.assertIn("PASS",done.stdout)

    def test_exact_production_driver_gun_action(self):
        try:
            from tools.harness import function
        except ModuleNotFoundError:
            from harness import function
        cc=os.environ.get("CC") or shutil.which("cc")
        self.assertIsNotNone(cc)
        with tempfile.TemporaryDirectory(prefix="halo-gun-action-") as temp:
            folder=Path(temp)
            (folder/"under_test_gun.inc").write_text(
                function(BOTS,"bots_bot_ground_gun_engage"))
            fixture=ROOT/"tools/harness/tests/bot_vehicle_gun.c"
            # Only one production function is extracted here. Other
            # static helpers in the included policy header are used by
            # bots.c, but intentionally unused by this single-function
            # fixture. Preserve -Werror for all other warnings.
            compile=subprocess.run([cc,"-std=gnu99","-Wall","-Wextra",
                "-Werror","-Wno-unused-function",
                "-I",str(folder),"-I",str(HEADER.parent),
                str(fixture),"-lm","-o",str(folder/"test")],
                capture_output=True,text=True)
            self.assertEqual(compile.returncode,0,compile.stderr)
            result=subprocess.run([str(folder/"test")],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertIn("Vehicle gun action PASS",result.stdout)

    def test_human_driver_warthog_gunner_gets_priority(self):
        for phrase in (
            '"bot_vehicle_crew_policy.h"',
            'bot_crew_objective_candidate(',
            'if (objective && !crew_candidate)',
            '(!fighting || crew_candidate) && now >= bot->next_vehicle_scan_tick',
            '!fighting && bots_vehicle_pick_flip(bot, position)',
            'bot_crew_speed_safe(speed_squared, gunner,',
            'bot_crew_seat_score(',
            'friendly_human_driver',
            'bots_is_enemy(player_get(bot->player_index), driver_player)',
            'driver_player->network_player_data.machine_index <',
            'bot_crew_seat_role_allowed(driver, gunner, driver_candidate)',
            'bots_vehicle_driver_limit_reached(bot)',
            'unit_can_enter_seat(bot_unit_index, vehicle_index, seat_index, NULL)',
            '_player_action_result_enter_vehicle',
            'UNIT_CONTROL_PORT_ACTION_ONLY_BIT',
        ):
            self.assertIn(phrase,BOTS,phrase)
        self.assertIn('bots_vehicle_pick(bot, player_get(player_index)->unit_index, position)',BOTS)

    def test_scorpion_ghost_fire_only_via_actual_gunner(self):
        for phrase in (
            'static void bots_bot_ground_gun_engage(',
            'bots_bot_ground_gun_engage(bot, tick, vehicle)',
            'vehicle->unit.gunner_object_index != tick->player->unit_index',
            'vehicle->unit.driver_object_index != tick->player->unit_index',
            'bots_current_weapon(vehicle)',
            'bots_bot_look(bot, tick)',
            'bots_is_enemy(tick->player, target)',
            'bot_crew_tank_fire_allowed(TRUE, TRUE, distance,',
            'tick->action->control_flags |= FLAG(_unit_control_weapon_primary_trigger_bit)',
            'tick->action->primary_trigger = 1.0f',
            'bot->burst_until_tick = tick->now + TICKS_PER_SECOND',
            'TEST_FLAG(vehicle->object.damage_flags, _object_dead_bit)',
        ):
            self.assertIn(phrase,BOTS,phrase)
        self.assertIn('unit->unit.control_flags |= gunner_unit->unit.control_flags&UNIT_CONTROL_GUNNER_MASK',UNITS)
        self.assertIn('unit->unit.throttle = driver_unit->unit.throttle',UNITS)
        self.assertIn('FLAG(_unit_control_weapon_primary_trigger_bit)',UNIT_HEADER)
        start=BOTS.index('static void bots_bot_ground_gun_engage(')
        end=BOTS.index('/* Existing gunners',start)
        function=BOTS[start:end]
        for dangerous in ('unit_enter_seat(', 'object_set_position(',
                'object_teleport(', 'object_move(',
                'vehicle->unit.gunner_object_index =',
                'vehicle->vehicle.speed =',
                'object_cause_damage('):
            self.assertNotIn(dangerous,function)
        # Ensure only the gunner predicate can authorize a vehicle shot.
        self.assertLess(function.index('vehicle->unit.gunner_object_index !='),
            function.index('tick->action->control_flags |='))
        # The v43 career receipt adds protocol 53 independently of vehicle AI.

if __name__=="__main__":
    unittest.main()
