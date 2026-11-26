"""Native title readback: actual frame source, opaque color, row orientation, bounded requests."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
J=ROOT/"port/android/app/src/main/java/com/halo/decomp"
H=ROOT/"port/android/host"

class NativeTitleTest(unittest.TestCase):
    def test_pixels(self):
        with tempfile.TemporaryDirectory(prefix="halo-title-pixels-") as d:
            exe=Path(d)/"title_pixels"
            subprocess.run([os.environ.get("CC","cc"),"-std=c11","-Wall","-Wextra","-Werror",
                            "-I"+str(H),str(ROOT/"tools/lan/title_frame_pixels_test.c"),"-o",str(exe)],check=True)
            subprocess.run([str(exe)],check=True)

    def test_renderer_thread_reads_completed_frame_before_swap(self):
        s=(H/"host_sdl.c").read_text()
        m=s.split("int host_sdl_gl_swap_window(uint32_t window)",1)[1].split("/* ---------- events */",1)[0]
        self.assertIn("host_title_capture_mode && host_title_capture_requested()",m)
        self.assertLess(m.index("host_title_capture_present(width, height)"),m.index("SDL_GL_SwapWindow(object)"))
        g=(H/"host_gl.c").read_text()
        self.assertIn("atomic_exchange(&title_frame_requested, 0)",g)
        self.assertIn("glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, title_frame_rgba)",g)
        self.assertIn("glBindFramebuffer(GL_READ_FRAMEBUFFER, 0)",g)
        self.assertIn("glBindFramebuffer(GL_READ_FRAMEBUFFER, (GLuint)old_read)",g)
        self.assertIn("glBindBuffer(GL_PIXEL_PACK_BUFFER, 0)",g)
        self.assertIn("glBindBuffer(GL_PIXEL_PACK_BUFFER, (GLuint)old_pack)",g)
        for field in ("ALIGNMENT","ROW_LENGTH","SKIP_ROWS","SKIP_PIXELS"):
            self.assertIn("glGetIntegerv(GL_PACK_"+field,g)
            self.assertGreaterEqual(g.count("glPixelStorei(GL_PACK_"+field),2)
        self.assertIn("title_frame_convert(title_frame_rgba",g)
        jni=g.split("JNIEXPORT jint JNICALL Java_com_halo_decomp_HaloActivity_copyTitleFrame(",1)[1].split("void *host_gl_resolve",1)[0]
        self.assertNotIn("glReadPixels",jni)
        self.assertIn("if (!title_frame_inflight)",jni)
        self.assertIn("title_frame_result = 0;",jni)
        self.assertIn("SetIntArrayRegion",jni)
        self.assertIn("GetArrayLength",jni)

    def test_java_capture_and_diagnostics(self):
        c=(J/"TitleBackdropCapture.java").read_text()
        self.assertNotIn("PixelCopy.request",c)
        self.assertIn("activity.copyTitleFrame(pixels, width, height)",c)
        self.assertIn("bitmap.setHasAlpha(false)",c)
        self.assertIn("bitmap.setPixels(pixels",c)
        self.assertIn("else blankFrames++",c)
        self.assertIn("else if (code == 0) waitingFrames++",c)
        self.assertIn("lastReadError = code",c)
        self.assertIn("activity.titleAudioDuration()",c)
        self.assertIn("No visible native GL title frame received.",c)
        self.assertIn("copied < TitleBackdropFiles.FRAMES / 6 || changes < 12",c)
        self.assertIn("TitleBackdropFiles.commit(root)",c)
        self.assertIn("TitleBackdropFiles.clearPending",c)
        self.assertIn("Java_com_halo_decomp_HaloActivity_titleAudioDuration",(H/"host_sdl.c").read_text())

if __name__=="__main__":
    unittest.main()
