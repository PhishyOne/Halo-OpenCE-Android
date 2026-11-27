"""Exercise the same distance and fog policy compiled into the Android guest."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class MapDrawDistance(unittest.TestCase):
    def test_map_extents(self):
        program = r"""
#include <assert.h>
#include <math.h>
typedef int boolean;
typedef float real;
typedef struct { real x0, x1, y0, y1, z0, z1; } real_rectangle3d;
#define square_root sqrtf
#include "map_draw_distance.h"

int main(void) {
    real_rectangle3d box = {10, 13, -5, -1, 0, 12};
    real vis = 1024, gpu = 1024;
    real half = 4000.0f / 3.048f;
    real_rectangle3d royale = {-half, half, -half, half, 0, 100};
    real distance = map_draw_diagonal(&royale);
    assert(fabsf(distance * 3.048f - 11317.81f) < 1.0f);
    assert(map_draw_diagonal(&box) == 13);
    map_draw_apply(0, &vis, &gpu);
    assert(vis == 1024 && gpu == 1024);
    map_draw_apply(13, &vis, &gpu);
    assert(vis == 1024 && gpu == 1024);
    map_draw_apply(distance, &vis, &gpu);
    assert(vis == distance && gpu == distance);
    box.x1 = -3; assert(map_draw_diagonal(&box) == 0);
    box.x1 = NAN; assert(map_draw_diagonal(&box) == 0);
    box.x1 = FLT_MAX; assert(map_draw_diagonal(&box) == 0);
    return 0;
}
"""
        with tempfile.TemporaryDirectory() as td:
            exe = pathlib.Path(td) / "distance"
            subprocess.run(["cc", "-std=gnu99", "-Wall", "-Werror", "-x", "c", "-",
                            "-I", str(ROOT / "port/linux/game"), "-lm", "-o", str(exe)],
                           input=program, text=True, check=True)
            subprocess.run([exe], check=True)


if __name__ == "__main__":
    unittest.main()
