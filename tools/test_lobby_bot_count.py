"""Lobby bot control: exact policy, generated XML, host/pregame security gates."""
from pathlib import Path
import subprocess, tempfile, shutil, sys, xml.etree.ElementTree as ET
import unittest

ROOT=Path(__file__).resolve().parents[1]
HEADER=ROOT/"port/linux/game/lobby_bot_count_policy.h"
NATIVE=ROOT/"port/linux/game/menu_functions.c"
SERVER=ROOT/"source/networking/network_server_manager.c"
sys.path.insert(0,str(ROOT/"tools"))
import port_settings

C=r"""
#include <stdio.h>
#include "lobby_bot_count_policy.h"
#define CHECK(x) do {if(!(x)){fprintf(stderr,"Bad line %d: %s\n",__LINE__,#x);return 1;}}while(0)
int main(void){
  int i;
  const int offline[]={0,4,8,12,16,24,32,48,64,96,127};
  const int lan[]={0,2,4,6,8,12,16};
  CHECK(halo_lobby_next_bot_count(0,0,0)==0);
  for(i=0;i<11;i++){
    CHECK(halo_lobby_next_bot_count(offline[i],-1,0)==offline[i==0?0:i-1]);
    CHECK(halo_lobby_next_bot_count(offline[i],1,0)==offline[i==10?10:i+1]);
  }
  for(i=0;i<7;i++){
    CHECK(halo_lobby_next_bot_count(lan[i],-1,1)==lan[i==0?0:i-1]);
    CHECK(halo_lobby_next_bot_count(lan[i],1,1)==lan[i==6?6:i+1]);
  }
  CHECK(halo_lobby_next_bot_count(63,1,0)==64);
  CHECK(halo_lobby_next_bot_count(63,-1,0)==48);
  CHECK(halo_lobby_next_bot_count(5,1,1)==6);
  CHECK(halo_lobby_next_bot_count(5,-1,1)==4);
  CHECK(halo_lobby_next_bot_count(999,1,0)==127);
  CHECK(halo_lobby_next_bot_count(-12,-1,0)==0);
  CHECK(halo_lobby_next_bot_count(127,1,1)==16);
  CHECK(halo_lobby_next_bot_count(127,-1,1)==16);
  puts("Lobby bot count preset/cap policy PASS");
  return 0;
}
"""

class LobbyBotCountTest(unittest.TestCase):
    def test_pure_presets(self):
        cc=shutil.which("cc") or shutil.which("clang")
        self.assertIsNotNone(cc)
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)
            (p/"test.c").write_text(C)
            result=subprocess.run([cc,"-std=c99","-Wall","-Wextra","-Werror","-I",str(HEADER.parent),
                str(p/"test.c"),"-o",str(p/"test")],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr)
            result=subprocess.run([str(p/"test")],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertIn("PASS",result.stdout)

    def test_generated_lobby_widgets(self):
        lobby_key=port_settings.MT.replace("/",".")+".lobby.xml"
        menus=port_settings.multiplayer_files()
        self.assertIn(lobby_key,menus)
        root=ET.fromstring("\n".join(menus[lobby_key]))
        names={w.get("name").split("/")[-1]: w for w in root.iter("widget")}
        for name,command in (("lobby_bot_less","port lobby bots fewer"),
                             ("lobby_bot_more","port lobby bots more")):
            self.assertIn(name,names)
            handler=[e.get("run") for e in names[name].iter("on")]
            self.assertIn(command,handler)
            self.assertEqual(names[name].get("width"),"59")
        self.assertIn("lobby_bot_count_label",names)
        root_text="\n".join(menus[lobby_key])
        self.assertIn('child widget="'+port_settings.MT+'/lobby/lobby_bot_less"',root_text)
        self.assertIn('child widget="'+port_settings.MT+'/lobby/lobby_bot_more"',root_text)
        for old in ("lobby_button_team","lobby_button_start","lobby_button_add","lobby_button_leave"):
            self.assertIn(old,root_text)

    def test_pregame_host_only_and_existing_reconciler(self):
        source=NATIVE.read_text()
        host=SERVER.read_text()
        check=source.split("static boolean lobby_bot_count_available(void)",1)[1].split(
            '/* "port lobby update" */',1)[0]
        self.assertIn('global_network_game_server_get()',check)
        self.assertIn('network_game_client_get_state(client, &state_data) != _client_state_pregame',check)
        self.assertIn('network_game_server_port_start_sent(server)',check)
        self.assertIn('(!network_game_is_splitscreen_local() && config_boolean("network.online"))',check)
        self.assertIn('controller != 0',check)
        self.assertIn('network_game_is_splitscreen_local()',check)
        self.assertIn('"bots.count"',check)
        self.assertIn('"bots.system_link_count"',check)
        self.assertIn('config_write(setting, value)',check)
        self.assertIn('lobby_bot_count_change(controller,',source)
        self.assertIn('visible_set(named(list, "lobby_bot_less", 0), bot_controls)',source)
        self.assertIn('visible_set(named(description, "lobby_bot_count_label", 0), bot_controls)',source)
        self.assertIn('server->state != _network_game_server_state_pregame',host)
        self.assertIn('network_game_server_port_bots(server);',host)
        self.assertIn('network_game_remove_player(&server->game, &leaving)',host)
        self.assertIn('network_game_add_player(&server->game, &joining)',host)

if __name__=="__main__":
    unittest.main()
