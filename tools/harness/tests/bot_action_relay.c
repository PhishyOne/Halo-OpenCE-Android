/*
BOT_ACTION_RELAY.C

Source-extracted test: copy the host's simulated bot action into the
current authoritative update for its correct player slot. Never accept
stale, client-supplied, malformed or out-of-range actions. This test
does not run Halo's netcode: it checks the precise queue bridge.
*/

#include "harness.h"

#define MAXIMUM_NUMBER_OF_PLAYERS 128
#define _game_connection_network_server 1
#define _game_connection_network_client 2

/* A reduced fake action record; production copies the whole action struct. */
struct player_action
{
    long control_flags;
    long movement;
    boolean valid;
};

struct server_update
{
    short action_count;
    struct player_action actions[MAXIMUM_NUMBER_OF_PLAYERS];
};
struct update
{
    long update_number;
    struct server_update update;
};

static struct update current_update;
static short current_connection;
static long current_tick;
static boolean accept_action;
static boolean return_update;

static short game_connection(void)
{
    return current_connection;
}

static boolean distributed_action_valid(struct player_action const *action)
{
    return accept_action && action->valid;
}

static long update_server_ticked_update_number(void)
{
    return current_tick;
}

static struct update *update_server_get_update(long number)
{
    (void)number;
    return return_update ? &current_update : NULL;
}

/* Extracted directly from source/game/player_queues_new.c by the Python test. */
#include "under_test.inc"

static void reset_world(void)
{
    long index;
    memset(&current_update, 0, sizeof(current_update));
    current_update.update_number = 410;
    current_update.update.action_count = 10;
    current_connection = _game_connection_network_server;
    current_tick = 410;
    accept_action = TRUE;
    return_update = TRUE;
    for (index = 0; index < MAXIMUM_NUMBER_OF_PLAYERS; index++)
        current_update.update.actions[index].control_flags = 100 + index;
}

static void expect_unchanged(long index)
{
    CHECK(current_update.update.actions[index].control_flags == 100 + index,
          "slot %ld mutated even though its action must not be accepted", index);
}

int main(int argc, char **argv)
{
    char const *case_name = argc > 1 ? argv[1] : "";
    struct player_action bot = { 601, 42, TRUE };

    reset_world();

    CASE("host")
    {
        update_server_note_bot_action(3, &bot);
        CHECK(current_update.update.actions[3].control_flags == 601,
              "host action was not copied into correct player index");
        CHECK(current_update.update.actions[3].movement == 42,
              "host action was not copied intact");
        expect_unchanged(2);
        expect_unchanged(4);
        return 0;
    }

    CASE("client")
    {
        current_connection = _game_connection_network_client;
        update_server_note_bot_action(3, &bot);
        expect_unchanged(3);
        return 0;
    }

    CASE("bad-action")
    {
        bot.valid = FALSE;
        update_server_note_bot_action(3, &bot);
        expect_unchanged(3);
        return 0;
    }

    CASE("invalid-source")
    {
        accept_action = FALSE;
        update_server_note_bot_action(3, &bot);
        expect_unchanged(3);
        return 0;
    }

    CASE("wrong-update")
    {
        current_update.update_number = 411;
        update_server_note_bot_action(3, &bot);
        expect_unchanged(3);
        return 0;
    }

    CASE("missing-update")
    {
        return_update = FALSE;
        update_server_note_bot_action(3, &bot);
        expect_unchanged(3);
        return 0;
    }

    CASE("no-tick")
    {
        current_tick = NONE;
        update_server_note_bot_action(3, &bot);
        expect_unchanged(3);
        return 0;
    }

    CASE("null-action")
    {
        update_server_note_bot_action(3, NULL);
        expect_unchanged(3);
        return 0;
    }

    CASE("beyond-count")
    {
        update_server_note_bot_action(13, &bot);
        expect_unchanged(13);
        return 0;
    }

    CASE("negative-index")
    {
        update_server_note_bot_action(-1, &bot);
        expect_unchanged(0);
        return 0;
    }

    CASE("after-array")
    {
        update_server_note_bot_action(130, &bot);
        expect_unchanged(0);
        return 0;
    }

    fprintf(stderr, "unknown case %s\n", case_name);
    return 2;
}
