"""Surface-loss ordering and Android background-session integration."""
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from tools.harness import function

ROOT = Path(__file__).resolve().parents[1]

class BackgroundSessionTest(unittest.TestCase):
    def test_surface_can_disappear_during_event_pump(self):
        body = function((ROOT / 'port/android/host/host_sdl.c').read_text(),
                        'host_sdl_gl_swap_window')
        prefix = '''#include <assert.h>
#include <stdint.h>
typedef int SDL_Window;
static SDL_Window window;
enum { _handle_window };
static int surface, lose_during_pump, swaps;
static void *handle_get(uint32_t id, int kind) { (void)kind; return id ? &window : 0; }
static void SDL_PumpEvents(void) { if (lose_during_pump) surface = 0; }
static void *SDL_GL_GetCurrentContext(void) { return surface ? &window : 0; }
static int SDL_GL_SwapWindow(SDL_Window *w) { assert(w && surface); swaps++; return 1; }
'''
        test = '''
int main(void) {
    surface = 1; assert(host_sdl_gl_swap_window(1)); assert(swaps == 1);
    lose_during_pump = 1; assert(host_sdl_gl_swap_window(1)); assert(swaps == 1);
    assert(host_sdl_gl_swap_window(1)); assert(swaps == 1);
    lose_during_pump = 0; surface = 1;
    assert(host_sdl_gl_swap_window(1)); assert(swaps == 2);
    assert(!host_sdl_gl_swap_window(0)); assert(swaps == 2);
    return 0;
}
'''
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / 'test.c'; binary = Path(temp) / 'test'
            for broken in (False, True):
                candidate = body.replace('if (!SDL_GL_GetCurrentContext()) return 1;', '') if broken else body
                source.write_text(prefix + candidate + test)
                subprocess.run([os.environ.get('CC', 'cc'), '-std=gnu89', str(source), '-o', str(binary)], check=True)
                ran = subprocess.run([str(binary)], capture_output=True, timeout=10)
                self.assertEqual(ran.returncode == 0, not broken)

    def test_simulation_and_lifecycle_guards(self):
        main = (ROOT / 'source/main/main.c').read_text()
        loop = main[main.index('\twhile (TRUE)', main.index('void main_loop(')):]
        self.assertIn('if (!game_in_editor() && !halo_background_mode())', loop)
        self.assertIn('if (halo_server_mode() || halo_background_mode())', loop)
        self.assertIn('main_update_time_unthrottled()', loop)
        self.assertEqual(loop.count('if (render_frame && !debug_no_drawing && !halo_background_mode())'), 2)
        service = (ROOT / 'port/android/app/src/main/java/com/halo/decomp/NetworkSessionService.java').read_text()
        for required in ('PARTIAL_WAKE_LOCK', 'START_NOT_STICKY', 'setReferenceCounted(false)',
                         'wifi.release()', 'cpu.release()', 'stopForeground(STOP_FOREGROUND_REMOVE)'):
            self.assertIn(required, service)
        host = (ROOT / 'port/android/host/host_sdl.c').read_text()
        self.assertIn('SDL_SetHint(SDL_HINT_ANDROID_BLOCK_ON_PAUSE, "0")', host)
        manifest = (ROOT / 'port/android/app/src/main/AndroidManifest.xml').read_text()
        self.assertIn('android:foregroundServiceType="connectedDevice"', manifest)
        self.assertIn('android.permission.WAKE_LOCK', manifest)

if __name__ == '__main__':
    unittest.main()
