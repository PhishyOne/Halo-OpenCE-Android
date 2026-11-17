/* Execute the actual Android highlight-selection/writer code using the
 * host C compiler, without Halo renderer, SDL, network, or a JVM. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <assert.h>
#include "highlight_moments.h"

int main(int argc, char **argv)
{
    const char *root;
    char path[1024], line[300];
    FILE *in;
    int count = 0;
    if (argc != 2) return 2;
    root = argv[1];
    halo_highlights_start();
    halo_highlights_kill(1, 1, 300);
    halo_highlights_kill(0, 2, 350);
    halo_highlights_kill(0, 3, 600);
    halo_highlights_kill(0, 5, 1200);
    halo_highlights_ctf_capture(1320);
    halo_highlights_kill(0, 10, 2100);
    halo_highlights_finish(root,
        "0123456789abcdef0123456789abcdef", "bloodgulch", 2400, 1);
    halo_highlights_finish(root,
        "0123456789abcdef0123456789abcdef", "bloodgulch", 2400, 1);
    snprintf(path, sizeof(path), "%s/career-highlights-v1.log", root);
    in = fopen(path, "rb");
    assert(in != NULL);
    while (fgets(line, sizeof(line), in)) {
        if (count == 0) {
            assert(strstr(line, "|1200|STREAK_5|76|1050|1290") != NULL);
        } else if (count == 1) {
            assert(strstr(line, "|1320|FLAG_CAPTURE|93|1170|1410") != NULL);
        } else if (count == 2) {
            assert(strstr(line, "|2100|STREAK_10|100|1950|2190") != NULL);
        } else {
            assert(0 && "Duplicate or more than three highlights per match");
        }
        assert(strstr(line, "HLM1|0123456789abcdef0123456789abcdef|bloodgulch|"));
        count++;
    }
    fclose(in);
    assert(count == 3);
    halo_highlights_start();
    halo_highlights_kill(0, 3, 400);
    halo_highlights_finish(root, "BADID", "bloodgulch", 1000, 1);
    halo_highlights_start();
    halo_highlights_finish(root,
        "abcdef0123456789abcdef0123456789", "sidewinder", 600, 0);
    in = fopen(path, "rb");
    count = 0;
    while (fgets(line, sizeof(line), in)) count++;
    fclose(in);
    assert(count == 3);
    puts("PASS highlight moments: correct top three, times, no duplicate, invalid ID and empty match excluded");
    return 0;
}
