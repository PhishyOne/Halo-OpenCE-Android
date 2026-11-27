"""Round-trip the authored island through the same extractor used by the private map build."""
import shutil
import sys
from pathlib import Path

import pytest
import runner

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
pytest.importorskip('reclaimer', reason='the HEK map builder needs Reclaimer')
import royale_release as release

SKY = r'sky\clear afternoon\clear afternoon'


@pytest.mark.skipif(not shutil.which('invader-extract'), reason='needs Invader')
def test_authored_multiplayer_tags(stagekit, tmp_path):
    geometry = runner.generate(stagekit, 'royale_crossroads', tmp_path)
    authored = tmp_path / 'tags'
    authored.mkdir()
    expected = release.prepare_geometry(geometry, authored, SKY)
    assert all(release.tag_file(authored, name).is_file() for name in expected)
    scenario_path = release.tag_file(authored, release.SCENARIO + '.scenario')
    scenario = release.scnr_def.build(filepath=scenario_path).data.tagdata
    assert scenario.type.enum_name == 'multiplayer'
    assert [item.sky.filepath for item in scenario.skies.STEPTREE] == [SKY]

    source = release.scnr_def.build(filepath=release.tag_file(authored, release.STAGE)).data.tagdata
    assert len(scenario.sceneries.STEPTREE) == len(source.sceneries.STEPTREE)
    flags = {flag.name: flag for flag in scenario.cutscene_flags.STEPTREE}
    assert set(flags) == {flag.name for flag in source.cutscene_flags.STEPTREE}
    landings = [f for f in flags if f.startswith('br_land_')]
    assert len(landings) == 36
    assert len(scenario.player_starting_locations.STEPTREE) == 4 * len(landings)
    vehicles = scenario.vehicles.STEPTREE
    palette = [entry.name.filepath for entry in scenario.vehicles_palette.STEPTREE]
    assert palette.count(release.PELICAN) == 1
    pads = [f for f in flags.values() if f.name.startswith('br_vehicle_')]
    assert len(vehicles) == len(pads) == 5
    for vehicle, marker in zip(vehicles, pads):
        kind = marker.name.removeprefix('br_vehicle_').rsplit('_', 1)[0]
        assert palette[vehicle.type] == release.VEHICLES[kind]
        assert list(vehicle.position) == list(marker.position)
        assert vehicle.rotation.y == marker.facing.y
        assert vehicle.multiplayer_spawn_flags.data == 0x0F0F
    for entries, stock in ((scenario.weapons_palette, release.WEAPONS),
                           (scenario.equipments_palette, release.SUPPLIES)):
        assert [entry.name.filepath for entry in entries.STEPTREE] == stock
    bsp = release.sbsp_def.build(filepath=release.tag_file(authored, release.BSP)).data.tagdata
    assert bsp.clusters.STEPTREE and all(cluster.sky == 0 for cluster in bsp.clusters.STEPTREE)


def test_missing_stock_keeps_existing_map(tmp_path):
    destination = tmp_path / 'halo_royale.map'
    destination.write_bytes(b'previous playable map')
    with pytest.raises(ValueError, match='Missing stock HEK tags'):
        release.private_map(tmp_path / 'geometry.map', tmp_path, SKY, destination)
    assert destination.read_bytes() == b'previous playable map'
