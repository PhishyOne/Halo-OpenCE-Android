"""Execute production host finalization, receiver and journal, with hostile orderings."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest
from tools.harness import structure, function

ROOT = Path(__file__).resolve().parents[1]
GAME = ROOT / 'port/linux/game'

class CareerResultsTest(unittest.TestCase):
    def build(self, folder, mutation=None):
        work = Path(folder)
        (work / 'game').mkdir(exist_ok=True)
        header = (ROOT / 'source/game/game.h').read_text()
        names = ['slayer_statistics', 'ctf_statistics', 'oddball_statistics',
                 'king_statistics', 'race_statistics', 'game_statistics']
        # Union is extracted with the same brace matcher as structs.
        types = [structure(header, n) for n in names[:-1]]
        union = header[header.index('union multiplayer_statistics\n{'):]
        union = union[:union.index('};') + 2]
        (work / 'game/game.h').write_text('\n'.join(types + [union, structure(header, names[-1])]))
        source = (GAME / 'career_results.c').read_text()
        source = source[source.index('struct distributed_career_result_message'):]
        if mutation:
            old, new = mutation
            self.assertIn(old, source)
            source = source.replace(old, new, 1)
        (work / 'results.inc').write_text(source)
        (work / 'kill.inc').write_text(function(
            (ROOT / 'source/game/game_statistics.c').read_text(), 'game_statistics_record_kill'))
        (work / 'capture.inc').write_text(function(
            (ROOT / 'source/game/game_engine_ctf.c').read_text(), 'ctf_award_capture'))

        writer = (GAME / 'career_record.c').read_text()
        (work / 'writer.inc').write_text(writer[writer.index('enum { CAREER_MAX_BYTES'):
                                              writer.index('#endif /* HALO_ANDROID */')])
        # Use the Android game CRT's actual path macros. Any accidental Xbox
        # fopen/rename reference fails at link time. Host libc handles formatting.
        (work / 'stdio.h').symlink_to(ROOT / 'port/linux/include/stdio.h')
        compatibility = work / 'guest_stdio.h'
        compatibility.write_text('#include <stdio.h>\n' + ''.join(
            '#undef ' + name + '\n' for name in ('snprintf', 'fprintf', 'printf', 'fileno')))
        cmd = [os.environ.get('CC', 'cc'), '-DHALO_ANDROID', '-std=gnu89',
               '-Wall', '-Wextra', '-Werror', '-include', str(compatibility), '-I', str(work), '-I', str(GAME),
               str(ROOT / 'tools/harness/tests/career_results.c'),
               str(GAME / 'highlight_moments.c'), '-o', str(work / 'test')]
        built = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(built.returncode, 0, built.stderr)
        return work / 'test'

    def test_runtime(self):
        with tempfile.TemporaryDirectory() as temp:
            executable = self.build(temp)
            for case in ('ordering', 'replay_first', 'missing', 'capture', 'frozen', 'validation', 'death', 'checkpoint', 'checkpoint_final',
                         'coop_host', 'coop_guest', 'coop_exit'):
                with self.subTest(case=case):
                    data = Path(temp) / case; data.mkdir()
                    ran = subprocess.run([str(executable), case, str(data)],
                                         capture_output=True, text=True, timeout=10)
                    self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
                    if case in ('ordering', 'replay_first', 'capture'):
                        rows = (data / 'career-matches-v1.log').read_text().splitlines()
                        self.assertEqual(len(rows), 1)
                        fields = rows[0].split('|')
                        self.assertEqual(fields[5:13], ['1', '0', '1', '0', '0', '1', '40', '1'])
                        self.assertIn('|900|FLAG_CAPTURE|93|',
                                      (data / 'career-highlights-v1.log').read_text())

            damaged = Path(temp) / 'damaged'
            damaged.mkdir()
            (damaged / 'career-pending-v1.log').write_bytes(b'torn')
            subprocess.run([str(executable), 'ordering', str(damaged)], check=True)
            self.assertEqual([p.read_bytes() for p in damaged.glob('career-damaged-*.log')], [b'torn'])
            # Read these actual native result files with the launcher's reader.
            java = ROOT / 'port/android/app/src/main/java/com/halo/decomp'
            subprocess.run(['javac', '-d', temp, str(java / 'CareerRecords.java'),
                            str(ROOT / 'tools/lan/CareerJournalTest.java')], check=True)
            subprocess.run(['java', '-cp', temp, 'com.halo.decomp.CareerJournalTest', temp],
                           check=True)

    def test_engine_and_transport_integration(self):
        engine = function((ROOT / 'source/game/game_engine.c').read_text(), 'game_engine_update')
        self.assertLess(engine.index('object_delete(object_iterator.index)'),
                        engine.index('career_results_finish('))
        self.assertLess(engine.index('career_results_finish('),
                        engine.index('network_game_server_switch_to_postgame('))
        dispatch = function((GAME / 'network_distributed.c').read_text(),
                            'network_distributed_handle_message')
        guard = dispatch[dispatch.index('if (career_results_handle('):]
        guard = guard[:guard.index('return;')]
        self.assertIn('!distributed_handling_batch', guard)
        self.assertIn('machine_index == NONE', guard)
        self.assertIn('!network_connection_last_read_was_unreliable()', guard)

    def test_negative_controls(self):
        mutations = [
            ('validation', ('!reliable ||', '(reliable && 0) ||')),
            ('validation', ('message.generation != career_generation ||', '0 ||')),
            ('validation', ('!events->result.incomplete || message.result.end_tick < events->result.end_tick', 'message.result.end_tick < events->result.end_tick')),
            ('capture', ('if (events) halo_highlight_capture(&events->highlights, game_time_get());',
                         'if (events) halo_highlight_capture(&events->highlights, game_time_get() + 1);')),
        ]
        for case, mutation in mutations:
            with self.subTest(case=case, mutation=mutation[0]), tempfile.TemporaryDirectory() as temp:
                exe = self.build(temp, mutation)
                ran = subprocess.run([str(exe), case, temp], capture_output=True, timeout=10)
                self.assertNotEqual(ran.returncode, 0, 'Regression was not detected')

if __name__ == '__main__':
    unittest.main()
