#ifndef HALO_TITLE_FRAME_PIXELS_H
#define HALO_TITLE_FRAME_PIXELS_H

#include <stdint.h>
#include <stddef.h>

#define TITLE_FRAME_MAX_WIDTH 640
#define TITLE_FRAME_MAX_HEIGHT 480

/* Bound the source readback to at most 36 MiB of RGBA, even on a device
 * with an unexpectedly large display. Output is at most 1.2 MiB. */
static inline int title_frame_source_valid(int width, int height)
{
    return width > 0 && height > 0 && width <= 4096 && height <= 4096 &&
        (size_t)width * height <= 4096u * 2304u;
}

static inline int title_frame_output_valid(int width, int height)
{
    return width >= 160 && width <= TITLE_FRAME_MAX_WIDTH &&
        height >= 90 && height <= TITLE_FRAME_MAX_HEIGHT &&
        !(width & 1) && !(height & 1);
}

/* GL window rows run bottom-up; Android Bitmap.setPixels expects top-down
 * packed ARGB. The game's alpha is scratch, not image transparency. Use
 * the real RGB, with alpha forced opaque. Pixel-center nearest sampling. */
static inline void title_frame_convert(const unsigned char *rgba,
    int width, int height, uint32_t *argb, int out_width, int out_height)
{
    int x, y;
    for (y = 0; y < out_height; ++y)
    {
        int sy = height - 1 - (2 * y + 1) * height / (2 * out_height);
        for (x = 0; x < out_width; ++x)
        {
            int sx = (2 * x + 1) * width / (2 * out_width);
            const unsigned char *p = rgba + ((size_t)sy * width + sx) * 4;
            argb[(size_t)y * out_width + x] = UINT32_C(0xff000000) |
                ((uint32_t)p[0] << 16) | ((uint32_t)p[1] << 8) | p[2];
        }
    }
}

#endif
