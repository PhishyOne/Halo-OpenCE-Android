/* Read-only extracted engine postgame packet handler: bounded fake client.
   Requires no licensed maps and no live network. */
#include "harness.h"
typedef unsigned short word;
struct network_game_client { short state; };
struct transport_address { int from_host; };
struct message_server_switch_to_pregame { long unused; };
enum {
    _network_game_client_state_pregame = 1,
    _network_game_client_state_ingame = 2,
    _network_game_client_state_postgame = 3,
    _message_server_switch_to_pregame = 10,
    _network_game_packet_class_postgame = 2,
    NETWORK_GAME_MESSAGE_VERSION = 46
};
static int decode_ok, decode_calls, transitions;
#define network_event(...) ((void)0)
#define decode_network_game_message(...) (decode_calls++, decode_ok)
static boolean network_game_client_address_matches_server(
    struct network_game_client *client, struct transport_address *source)
{
    (void)client;
    return source->from_host;
}
static short network_game_client_get_state(struct network_game_client *client, void *unused)
{
    (void)unused;
    return client->state;
}
static boolean network_game_client_switch_to_pregame(struct network_game_client *client)
{
    transitions++;
    client->state = _network_game_client_state_pregame;
    return TRUE;
}
#include "under_test.inc"

int main(int argc, char **argv)
{
    char const *case_name = argc > 1 ? argv[1] : "";
    struct network_game_client client = { _network_game_client_state_postgame };
    struct transport_address host = { 1 };
    word packet[] = {0, 0, 0, 0};
    boolean result;

    decode_ok = 1;
    transitions = decode_calls = 0;
    CASE("normal")
    {
        result = network_game_client_handle_message_server_switch_to_pregame(
            &client, packet, sizeof(packet), &host);
        CHECK(result && client.state == _network_game_client_state_pregame &&
            transitions == 1 && decode_calls == 1,
            "normal transition failed: %d, state=%d, transitions=%d, decode=%d",
            result, client.state, transitions, decode_calls);
        return 0;
    }
    CASE("duplicate")
    {
        client.state = _network_game_client_state_pregame;
        result = network_game_client_handle_message_server_switch_to_pregame(
            &client, packet, sizeof(packet), &host);
        CHECK(result && client.state == _network_game_client_state_pregame &&
            transitions == 0 && decode_calls == 1,
            "valid duplicate must not disconnect/reset: %d, %d, %d, %d",
            result, client.state, transitions, decode_calls);
        return 0;
    }
    CASE("malformed-postgame")
    {
        decode_ok = 0;
        result = network_game_client_handle_message_server_switch_to_pregame(
            &client, packet, sizeof(packet), &host);
        CHECK(!result && client.state == _network_game_client_state_postgame &&
            transitions == 0 && decode_calls == 1,
            "invalid packet forced a state transition");
        return 0;
    }
    CASE("malformed-duplicate")
    {
        client.state = _network_game_client_state_pregame;
        decode_ok = 0;
        result = network_game_client_handle_message_server_switch_to_pregame(
            &client, packet, sizeof(packet), &host);
        CHECK(!result && client.state == _network_game_client_state_pregame &&
            transitions == 0 && decode_calls == 1,
            "invalid duplicate packet incorrectly accepted");
        return 0;
    }
    CASE("foreign")
    {
        host.from_host = 0;
        result = network_game_client_handle_message_server_switch_to_pregame(
            &client, packet, sizeof(packet), &host);
        CHECK(result && client.state == _network_game_client_state_postgame &&
            transitions == 0 && decode_calls == 0,
            "nonhost sender affected client state");
        return 0;
    }
    CASE("ingame")
    {
        client.state = _network_game_client_state_ingame;
        result = network_game_client_handle_message_server_switch_to_pregame(
            &client, packet, sizeof(packet), &host);
        CHECK(!result && client.state == _network_game_client_state_ingame &&
            transitions == 0 && decode_calls == 0,
            "ingame client accepted a postgame transition packet");
        return 0;
    }
    fprintf(stderr, "unknown test case: %s\n", case_name);
    return 2;
}
