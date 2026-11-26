#include "title_frame_pixels.h"
#include <assert.h>
#include <stdio.h>

int main(void)
{
    /* OpenGL bottom row: red, green. Top row: blue, yellow.
     * Real RGB must survive alpha=0; alpha is scratch, not transparency. */
    const unsigned char rgba[] = {
        255,0,0,0,  0,255,0,7,
        0,0,255,0,  255,255,0,42
    };
    uint32_t out[16];
    title_frame_convert(rgba,2,2,out,2,2);
    assert(out[0]==UINT32_C(0xff0000ff));
    assert(out[1]==UINT32_C(0xffffff00));
    assert(out[2]==UINT32_C(0xffff0000));
    assert(out[3]==UINT32_C(0xff00ff00));
    title_frame_convert(rgba,2,2,out,4,4);
    assert(out[0]==UINT32_C(0xff0000ff) && out[5]==out[0]);
    assert(out[2]==UINT32_C(0xffffff00) && out[7]==out[2]);
    assert(out[8]==UINT32_C(0xffff0000) && out[13]==out[8]);
    assert(out[10]==UINT32_C(0xff00ff00) && out[15]==out[10]);
    assert(title_frame_source_valid(1536,686));
    assert(title_frame_source_valid(4096,2304));
    assert(!title_frame_source_valid(4096,2305));
    assert(!title_frame_source_valid(0,480));
    assert(!title_frame_source_valid(-1,480));
    assert(!title_frame_source_valid(1000000,1000000));
    assert(title_frame_output_valid(480,214));
    assert(title_frame_output_valid(640,480));
    assert(!title_frame_output_valid(481,214));
    assert(!title_frame_output_valid(480,89));
    assert(!title_frame_output_valid(642,480));
    assert(!title_frame_output_valid(480,482));
    puts("PASS native title conversion: opaque alpha, vertical flip, resampling, dimension caps (19 checks)");
    return 0;
}
