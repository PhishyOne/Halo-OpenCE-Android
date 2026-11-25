/* Android game/Java recording timestamps share the kernel monotonic clock.
 * Keep POSIX declarations in this runtime translation unit; the game is
 * intentionally compiled with strict XDK/MSVC-compatible headers. */
#include <time.h>

long long halo_android_monotonic_millis(void)
{
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return -1;
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}
