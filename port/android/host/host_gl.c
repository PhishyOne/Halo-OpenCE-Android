/*
HOST_GL.C

OpenGL ES for the guest. Its generated entry points (guest_gl.c) import
hostgl_<function>, resolved here to the driver's function; the arguments
already have host types by then. Only strings need copying back.
*/

#include "host.h"

#include <EGL/egl.h>
#include <GLES3/gl32.h>
#include <dlfcn.h>
#include <string.h>
#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include "title_frame_pixels.h"


/* A single bounded, demand-driven title frame exchange. Java asks on its
 * capture worker; only the GL thread reads a framebuffer. No GL calls from
 * the Java thread, no per-frame JNI callbacks, no queue of GPU readbacks.
 * Each request yields one fresh frame or a specific readback failure. */
extern int host_title_capture_mode;
static pthread_mutex_t title_frame_lock = PTHREAD_MUTEX_INITIALIZER;
static _Atomic int title_frame_requested;
static int title_frame_inflight, title_frame_result;
static int title_frame_width, title_frame_height;
static uint32_t title_frame_argb[TITLE_FRAME_MAX_WIDTH * TITLE_FRAME_MAX_HEIGHT];
/* Owned solely by the GL thread and released with the title process. */
static unsigned char *title_frame_rgba;
static size_t title_frame_rgba_capacity;

int host_title_capture_requested(void)
{
    return atomic_load(&title_frame_requested);
}

void host_title_capture_present(int width, int height)
{
    int out_width, out_height, result = -1;
    GLint old_read, old_pack, alignment, row_length, skip_rows, skip_pixels;
    GLint read_buffer;
    size_t bytes;
    unsigned int i;

    if (!host_title_capture_mode || !atomic_exchange(&title_frame_requested, 0))
        return;
    pthread_mutex_lock(&title_frame_lock);
    out_width = title_frame_width;
    out_height = title_frame_height;
    pthread_mutex_unlock(&title_frame_lock);

    if (!title_frame_source_valid(width, height) ||
        !title_frame_output_valid(out_width, out_height))
        goto complete;
    bytes = (size_t)width * height * 4;
    if (bytes > title_frame_rgba_capacity)
    {
        unsigned char *next = realloc(title_frame_rgba, bytes);
        if (!next) goto complete;
        title_frame_rgba = next;
        title_frame_rgba_capacity = bytes;
    }
    /* The game has just blitted its scene to the default framebuffer.
     * Preserve readback state: a bound pixel pack buffer would make the
     * pointer below a buffer offset, and a stale row stride would overflow. */
    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &old_read);
    glGetIntegerv(GL_PIXEL_PACK_BUFFER_BINDING, &old_pack);
    glGetIntegerv(GL_PACK_ALIGNMENT, &alignment);
    glGetIntegerv(GL_PACK_ROW_LENGTH, &row_length);
    glGetIntegerv(GL_PACK_SKIP_ROWS, &skip_rows);
    glGetIntegerv(GL_PACK_SKIP_PIXELS, &skip_pixels);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
    glGetIntegerv(GL_READ_BUFFER, &read_buffer);
    if (read_buffer != GL_BACK) glReadBuffer(GL_BACK);
    glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glPixelStorei(GL_PACK_ROW_LENGTH, 0);
    glPixelStorei(GL_PACK_SKIP_ROWS, 0);
    glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
    /* Ignore errors left over from the preceding scene only in this
     * dedicated title process; report actual readback errors separately. */
    for (i = 0; i < 8 && glGetError() != GL_NO_ERROR; ++i) {}
    glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, title_frame_rgba);
    result = glGetError() == GL_NO_ERROR ? 1 : -2;
    if (read_buffer != GL_BACK) glReadBuffer((GLenum)read_buffer);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, (GLuint)old_read);
    glBindBuffer(GL_PIXEL_PACK_BUFFER, (GLuint)old_pack);
    glPixelStorei(GL_PACK_ALIGNMENT, alignment);
    glPixelStorei(GL_PACK_ROW_LENGTH, row_length);
    glPixelStorei(GL_PACK_SKIP_ROWS, skip_rows);
    glPixelStorei(GL_PACK_SKIP_PIXELS, skip_pixels);

complete:
    pthread_mutex_lock(&title_frame_lock);
    if (result == 1)
        title_frame_convert(title_frame_rgba, width, height,
            title_frame_argb, out_width, out_height);
    title_frame_result = result;
    title_frame_inflight = 0;
    pthread_mutex_unlock(&title_frame_lock);
}

JNIEXPORT jint JNICALL Java_com_halo_decomp_HaloActivity_copyTitleFrame(
    JNIEnv *env, jobject activity, jintArray pixels, jint width, jint height)
{
    int result;
    (void)activity;
    if (!host_title_capture_mode || !pixels ||
        !title_frame_output_valid(width, height) ||
        (*env)->GetArrayLength(env, pixels) != width * height)
        return -1;
    pthread_mutex_lock(&title_frame_lock);
    result = title_frame_result;
    if (result == 1)
    {
        if (title_frame_width == width && title_frame_height == height)
            (*env)->SetIntArrayRegion(env, pixels, 0, width * height,
                (const jint *)title_frame_argb);
        else
            result = -1;
    }
    title_frame_result = 0;
    if (!title_frame_inflight)
    {
        title_frame_width = width;
        title_frame_height = height;
        title_frame_inflight = 1;
        atomic_store(&title_frame_requested, 1);
    }
    pthread_mutex_unlock(&title_frame_lock);
    return (jint)result;
}

void *host_gl_resolve(const char *name)
{
	static void *library;
	void *function = NULL;

	if (!library)
		library = dlopen("libGLESv3.so", RTLD_NOW | RTLD_GLOBAL);
	if (library)
		function = dlsym(library, name);
	if (!function)
		function = (void *)eglGetProcAddress(name);
	return function;
}

void host_gl_get_string(uint32_t name, int index, char *buffer, uint32_t size)
{
	const GLubyte *text = index >= 0 ? glGetStringi(name, (GLuint)index) : glGetString(name);

	if (!size)
		return;
	buffer[0] = 0;
	if (text)
	{
		strncpy(buffer, (const char *)text, size - 1);
		buffer[size - 1] = 0;
	}
}

int host_gl_has_extension(const char *name)
{
	GLint count = 0, index;

	glGetIntegerv(GL_NUM_EXTENSIONS, &count);
	for (index = 0; index < count; index++)
	{
		const char *extension = (const char *)glGetStringi(GL_EXTENSIONS, (GLuint)index);

		if (extension && !strcmp(extension, name))
			return 1;
	}
	return 0;
}

/* copies size bytes of a buffer object (the snapshots of the visibility
tests' counters, d3d8_gl.c); ES has no glGetBufferSubData, and the mapping
it offers instead is a host pointer. Binds GL_COPY_READ_BUFFER, which the
renderer uses only for its copies, and leaves it unbound. */
void host_gl_read_buffer(uint32_t buffer, uint32_t offset, uint32_t size, void *data)
{
	const void *mapping;

	glBindBuffer(GL_COPY_READ_BUFFER, buffer);
	mapping = glMapBufferRange(GL_COPY_READ_BUFFER, offset, size, GL_MAP_READ_BIT);
	if (mapping)
	{
		memcpy(data, mapping, size);
		glUnmapBuffer(GL_COPY_READ_BUFFER);
	}
	else
	{
		memset(data, 0, size);
	}
	glBindBuffer(GL_COPY_READ_BUFFER, 0);
}

/* The renderer streams each frame's vertices and indices into the next of
a ring of buffers (d3d8_gl.c). A fence marks the end of each frame's work,
and a buffer is written again only once the GPU has passed the fence of the
frame that last used it: drivers queue several frames, and a draw still
waiting to run would otherwise read a later frame's vertices. */
#define FRAME_FENCE_SLOTS 8

static GLsync frame_fences[FRAME_FENCE_SLOTS];

void host_gl_fence_frame(uint32_t slot)
{
	if (slot >= FRAME_FENCE_SLOTS)
		return;
	if (frame_fences[slot])
		glDeleteSync(frame_fences[slot]);
	frame_fences[slot] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
}

void host_gl_wait_frame(uint32_t slot)
{
	if (slot >= FRAME_FENCE_SLOTS || !frame_fences[slot])
		return;
	/* at most a second: a lost context must not hang the game */
	glClientWaitSync(frame_fences[slot], GL_SYNC_FLUSH_COMMANDS_BIT, 1000000000ull);
	glDeleteSync(frame_fences[slot]);
	frame_fences[slot] = NULL;
}

/* writes data into the buffer bound to target. The renderer streams a
range per draw, so a frame makes hundreds of these, and the cost per call
rather than per byte is what a frame is made of.

GL_MAP_INVALIDATE_RANGE_BIT was what made that cost ruinous. It tells the
driver the range's previous contents are undefined and must be discarded,
which is the very work GL_MAP_UNSYNCHRONIZED_BIT exists to avoid: that one
promises the caller that no queued draw is reading the range. Asked to do
both, Adreno pays for the discard - about 0.85 ms a call on a Galaxy Z
Flip 4, whatever the range written - and with a few hundred calls a frame
that came to 96% of a 550 ms frame at 1.8 fps, with the GPU idle throughout.

Without the invalidation the same call is well under a microsecond and the
same game runs at the display's refresh rate. The promise unsynchronized
makes still holds: the renderer only writes ranges that no queued draw reads,
because host_gl_wait_frame releases the ring slot first. */
void host_gl_buffer_write(uint32_t target, uint32_t offset, uint32_t size, const void *data)
{
	void *mapping = glMapBufferRange(target, offset, size,
		GL_MAP_WRITE_BIT | GL_MAP_UNSYNCHRONIZED_BIT);

	if (!mapping)
	{
		glBufferSubData(target, offset, size, data);
		return;
	}
	memcpy(mapping, data, size);
	glUnmapBuffer(target);
}
