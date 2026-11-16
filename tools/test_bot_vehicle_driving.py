"""Native pure-C driver steering and source integration checks.

These tests use the actual header compiled with a C compiler and the
production seat/action tests separately. Real network/vehicle physics still
requires Android gameplay, especially with multiple bots and passengers.
"""
import os
import shutil
import subprocess
import tempfile
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[1]
POLICY=ROOT/"port/linux/game/bot_vehicle_driver_policy.h"
GAME=ROOT/"port/linux/game/bots.c"
LAUNCHER=ROOT/"port/android/app/src/main/java/com/halo/decomp/LauncherActivity.java"
JAVA=LAUNCHER.parent
CONFIG=ROOT/"port/android/app/src/main/java/com/halo/decomp/LanConfig.java"

TEST=r"""
#include <stdio.h>
#include <math.h>
#include "bot_vehicle_driver_policy.h"
#define CHECK(x) do { if (!(x)) { fprintf(stderr, "line %d: %s\n", __LINE__, #x); return 1; } } while (0)
int main(void) {
    struct bot_driver_controls c;
    CHECK(bot_driver_supported("vehicles\\warthog\\warthog"));
    CHECK(bot_driver_supported("vehicles\\ghost\\ghost"));
    CHECK(bot_driver_supported("vehicles\\scorpion\\scorpion"));
    CHECK(!bot_driver_supported("vehicles\\banshee\\banshee"));
    CHECK(!bot_driver_supported("vehicles\\pelican\\pelican"));
    CHECK(!bot_driver_supported("weapons\\warthog_launcher"));
    CHECK(!bot_driver_supported(NULL));

    c=bot_driver_steering(0,0,0,20,0,0);
    CHECK(c.forward>=0.5f && c.forward<=0.56f && fabsf(c.desired_yaw)<0.01f);
    CHECK(c.lateral==0.0f);
    c=bot_driver_steering(0,0,0,0,20,0);
    CHECK(c.forward>0.0f && c.forward<0.25f);
    CHECK(fabsf(c.desired_yaw-1.5707963f)<0.02f);
    c=bot_driver_steering(0,0,0,-20,0,0);
    CHECK(c.forward>0.0f && c.forward<0.25f);
    c=bot_driver_steering(0,0,0,2,0,0);
    CHECK(c.forward==0.0f);
    c=bot_driver_steering(0,0,0,4,0,0);
    CHECK(c.forward>0 && c.forward<0.4f);
    c=bot_driver_steering(0,0,0,20,2,1);
    CHECK(c.forward<0.0f && c.forward>=-0.3f);
    CHECK(c.lateral==0.0f);
    c=bot_driver_steering(0,0,0,20,-2,1);
    CHECK(c.forward<0 && c.desired_yaw>0.6f && c.desired_yaw<0.7f);
    c=bot_driver_steering(NAN,0,0,20,0,0);
    CHECK(c.forward==0 && c.lateral==0);
    c=bot_driver_steering(0,0,INFINITY,20,0,0);
    CHECK(c.forward==0);
    puts("Bot driver steering policy PASS (ground seats, curves, reverse, invalid targets)");
    return 0;
}
"""

class GroundDriverTest(unittest.TestCase):
    def test_compiled_vehicle_steering_policy(self):
        cc=os.environ.get("CC") or shutil.which("cc") or shutil.which("clang")
        self.assertIsNotNone(cc)
        with tempfile.TemporaryDirectory(prefix="halo-drive-test-") as folder:
            file=Path(folder)/"drive.c";exe=Path(folder)/"drive"
            file.write_text(TEST)
            result=subprocess.run([cc,"-std=gnu99","-I",str(POLICY.parent),
                str(file),"-o",str(exe),"-lm"],
                capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr)
            executed=subprocess.run([str(exe)],capture_output=True,text=True)
            self.assertEqual(executed.returncode,0,executed.stderr)
            self.assertIn("PASS",executed.stdout)

    def test_production_driver_reservations_and_six_bot_capacity(self):
        try:
            from tools.harness import function
        except ModuleNotFoundError:
            from harness import function
        src=GAME.read_text()
        cc=os.environ.get("CC") or shutil.which("cc")
        with tempfile.TemporaryDirectory(prefix="halo-driver-reserve-") as folder:
            work=Path(folder)
            (work/"claim.inc").write_text(
                function(src,"bots_vehicle_driver_claimed"))
            (work/"capacity.inc").write_text(
                function(src,"bots_vehicle_driver_limit_reached"))
            fixture=ROOT/"tools/harness/tests/bot_vehicle_driver_reservations.c"
            result=subprocess.run([cc,"-std=gnu99","-Wall","-Wextra",
                "-Werror","-I",str(work),str(fixture),
                "-o",str(work/"test")],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr)
            verified=subprocess.run([str(work/"test")],
                capture_output=True,text=True)
            self.assertEqual(verified.returncode,0,verified.stderr)
            self.assertIn("PASS",verified.stdout)

    def test_authoritative_host_and_existing_player_action(self):
        s=GAME.read_text()
        for section in (
            "#include \"bot_vehicle_driver_policy.h\"",
            "config_boolean(\"bots.vehicle_driving\")",
            "vehicle->unit.driver_object_index == tick->player->unit_index",
            "bots_bot_driver_control(bot, bot_index, tick, vehicle_index, vehicle)",
            "bot_driver_steering(position.x, position.y,",
            "tick->action->desired_facing.yaw = steering.desired_yaw",
            "tick->action->throttle.i = steering.forward",
            "tick->action->throttle.j = steering.lateral",
            "tick->action->primary_trigger = 0.0f",
            "BOTS_DRIVER_PROGRESS_TICKS",
            "BOTS_DRIVER_REVERSE_TICKS",
            "driver_reverse_until_tick",
            "BOTS_DRIVER_MAX_ACTIVE 6",
            "bots_vehicle_driver_claimed(bot, iterator.index)",
            "bots_vehicle_driver_limit_reached(bot)",
            "unit_can_enter_seat(bot_unit_index, vehicle_index, seat_index, NULL)",
            "vehicle->unit.driver_object_index != NONE",
        ):
            self.assertIn(section,s,section)
        # No direct transforms or physics impulses inside the driver code.
        start=s.index("static void bots_bot_driver_control(")
        stop=s.index("/* Existing gunners",start)
        drive=s[start:stop]
        for forbidden in (
            "object_teleport(", "object_move(", "vehicle_accelerate(",
            "object_set_position(", "vehicle->object.translational_velocity =",
            "vehicle->vehicle.speed =", "unit_enter_seat(",
            "unit_seat_enter(", "player->machine_index =",
        ):
            self.assertNotIn(forbidden,drive,forbidden)
        self.assertIn("return TRUE;",s[s.index("static boolean bots_bot_vehicle_occupied("):])

    def test_beach_assault_resources_checked_without_map_writes(self):
        src=LAUNCHER.read_text()
        a=src.index("private String beachAssaultResourceStatus()")
        b=src.index("private String customMapDisplayName(",a)
        check=src[a:b]
        audit = (JAVA / "CeResourceAudit.java").read_text()
        for token in (
            '"CHECK BEACH ASSAULT RESOURCES"',
            'beachAssaultResourceStatus()',
            'CeResourceAudit.beachReport(dataRoot)',
            'new Thread(() ->',
            '"Halo-Beach-Assault-audit"',
        ):
            self.assertIn(token, src)
        for token in (
            'new File(appRoot, "custom_maps")',
            '"beach_assault.map"',
            '"bitmaps.map", "sounds.map", "loc.map"',
            'ref.index < entryNames.length',
            'soundNames.contains(ref.name)',
            'External resource references match installed files',
        ):
            self.assertIn(token, audit)
        for forbidden in ('Files.delete(', 'deleteRecursively(',
                          'Files.move(', 'rm -rf', 'new FileOutputStream('):
            self.assertNotIn(forbidden,check)

    def test_ci_maven_mirror_has_canonical_fallback(self):
        settings=(ROOT/"port/android/settings.gradle").read_text()
        mirror='https://maven-central.storage-download.googleapis.com/maven2/'
        # Two repository scopes: plugin classpath and app runtime dependencies.
        self.assertEqual(settings.count(mirror),2)
        self.assertEqual(settings.count("mavenCentral()"),2)
        self.assertEqual(settings.count("google()"),2)
        self.assertIn("gradlePluginPortal()",settings)
        self.assertLess(settings.index(mirror),settings.index("mavenCentral()"))
        self.assertNotIn("http://",settings)

    def test_toggle_is_explicit_off_by_default(self):
        launcher=LAUNCHER.read_text()
        config=CONFIG.read_text()
        self.assertIn('driverBots.setText("BOTS DRIVE GROUND VEHICLES (EXPERIMENTAL)")',launcher)
        self.assertIn("driverBots.setChecked(settings.vehicleDriving)",launcher)
        self.assertIn("hosting && driverBots.isChecked()",launcher)
        self.assertIn("boolean vehicleDriving = false;",config)
        self.assertIn("BOT_DRIVING_KEY",config)
        self.assertIn("Duplicate vehicle_driving in [bots]",config)
        self.assertIn("vehicle_driving = ",config)
        self.assertIn('config_boolean("bots.vehicle_driving")',GAME.read_text())

if __name__=="__main__":
    unittest.main()
