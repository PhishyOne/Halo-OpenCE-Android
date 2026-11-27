"""Build the playable Halo Royale cache from an authored StageKit map and private Halo HEK tags."""
import argparse
import os
import subprocess
import tempfile
from pathlib import Path, PureWindowsPath

from reclaimer.hek.defs.sbsp import sbsp_def
from reclaimer.hek.defs.scnr import scnr_def
from reclaimer.meta.halo_map import get_map_header, get_tag_index
from supyr_struct.buffer import get_rawdata

SCENARIO = r'levels\halo_royale\halo_royale'
STAGE = r'stagekit\void.scenario'
BSP = r'stagekit\chamber.scenario_structure_bsp'
EMPTY_MODEL = r'stagekit\empty.gbxmodel'
PELICAN = r'vehicles\pelican\pelican'
VEHICLES = {'warthog': r'vehicles\warthog\mp_warthog', 'ghost': r'vehicles\ghost\ghost_mp'}
WEAPONS = [fr'weapons\{name}\{name}' for name in
           ('pistol', 'assault rifle', 'shotgun', 'plasma pistol', 'plasma rifle', 'sniper rifle')]
WEAPONS.append(r'weapons\needler\mp_needler')
SUPPLIES = [r'powerups\health pack', r'weapons\frag grenade\frag grenade',
            r'weapons\plasma grenade\plasma grenade'] + [
                fr'powerups\{name} ammo\{name} ammo'
                for name in ('pistol', 'assault rifle', 'shotgun', 'sniper rifle', 'needler')]


def tag_file(root, name):
    path = PureWindowsPath(name)
    if path.anchor or '..' in path.parts or not path.name:
        raise ValueError(f'Invalid Halo tag path: {name!r}')
    return root.joinpath(*path.parts)


def cache_index(path, expected_type):
    with get_rawdata(filepath=str(path)) as data:
        header = get_map_header(data)
        if (header is None or header.version.enum_name != 'halo1ce'
                or header.map_type.enum_name != expected_type):
            raise ValueError(f'{path} must be a Custom Edition {expected_type} cache')
        index = get_tag_index(data, header)
        return {f'{tag.path}.{tag.class_1.enum_name}' for tag in index.tag_index}


def add(block):
    block.STEPTREE.append()
    return block.STEPTREE[-1]


def stock_tags(sky):
    return {sky + '.sky', PELICAN + '.vehicle', r'globals\globals.globals',
            *(v + '.vehicle' for v in VEHICLES.values()),
            *(w + '.weapon' for w in WEAPONS), *(e + '.equipment' for e in SUPPLIES)}


def configure_multiplayer(scenario, sky):
    # The compiled fixture owns every collision placement, heading and marker.
    # Taking them from that same cache also lets older island revisions keep their layout.
    flags = scenario.cutscene_flags.STEPTREE
    names = {f.name for f in flags}
    if len(names) != len(flags) or not {'echo419_entry', 'echo419_exit', 'br_poi_orchard_crossing'} <= names:
        raise ValueError('Missing or duplicate Royale flight and district markers')
    landings = [flag for flag in flags if flag.name.startswith('br_land_')]
    pads = [flag for flag in flags if flag.name.startswith('br_vehicle_')]
    if not landings or not pads or not scenario.sceneries.STEPTREE:
        raise ValueError('The map needs Royale clearings, vehicle pads and collision scenery')
    if len(landings) * 4 > 256:
        raise ValueError('Four player starts per clearing exceed the HEK limit of 256')
    scenario.type.set_to('multiplayer')
    add(scenario.skies).sky.filepath = sky
    for path in (*VEHICLES.values(), PELICAN):
        add(scenario.vehicles_palette).name.filepath = path
    # Keeping the unplaced Pelican in the palette survives script recompilation.
    # The game creates its one Echo 419 only when the host starts boarding.
    for field, paths in ((scenario.weapons_palette, WEAPONS), (scenario.equipments_palette, SUPPLIES)):
        for path in paths:
            add(field).name.filepath = path
    for marker in pads:
        kind, separator, number = marker.name[len('br_vehicle_'):].rpartition('_')
        if not separator or not number.isdecimal() or kind not in VEHICLES:
            raise ValueError(f'Unknown vehicle pad: {marker.name}')
        vehicle = add(scenario.vehicles)
        vehicle.type = tuple(VEHICLES).index(kind)
        vehicle.position[:] = marker.position[:]
        vehicle.rotation.y = marker.facing.y  # yaw, in radians
        vehicle.body_vitality = 1.0
        for mode in ('slayer', 'ctf', 'king', 'oddball'):
            setattr(vehicle.multiplayer_spawn_flags, mode + '_default', True)
            setattr(vehicle.multiplayer_spawn_flags, mode + '_allowed', True)
    del scenario.player_starting_locations.STEPTREE[:]
    for marker in landings:
        x, y, z = marker.position
        for cell in (5, 6, 9, 10):
            start = add(scenario.player_starting_locations)
            start.position[:] = [x + (cell % 4 - 1.5) * .75, y + (cell // 4 - 1.5) * .75, z + .1]
            start.team_index = cell % 2
            start.bsp_index = 0
            start.type_0.set_to('all_games')


def prepare_geometry(geometry, authored, sky):
    indexed = cache_index(geometry, 'sp')
    fixed = {STAGE, BSP, EMPTY_MODEL}
    expected = fixed | {name for name in indexed if name.startswith('orchard\\')}
    if not fixed <= indexed or len(expected) == len(fixed):
        raise ValueError('Expected an authored Royale StageKit cache')
    files = {name: tag_file(authored, name) for name in expected}
    subprocess.run(['invader-extract', '-G', '-t', str(authored), '-s', r'orchard\*',
                    '-s', STAGE, '-s', BSP, '-s', EMPTY_MODEL, str(geometry)], check=True)
    # Invader can return success after reporting individual extraction failures.
    missing = sorted(name for name, path in files.items() if not path.is_file())
    if missing:
        raise ValueError('Missing authored tags after extraction: ' + ', '.join(missing))

    tag = scnr_def.build(filepath=files[STAGE])
    configure_multiplayer(tag.data.tagdata, sky)
    destination = tag_file(authored, SCENARIO + '.scenario')
    destination.parent.mkdir(parents=True, exist_ok=True)
    tag.serialize(filepath=destination, temp=False, backup=False)

    bsp = sbsp_def.build(filepath=files[BSP])
    clusters = bsp.data.tagdata.clusters.STEPTREE
    if not clusters:
        raise ValueError('The island has no visibility clusters')
    for cluster in clusters:
        cluster.sky = 0  # the StageKit fixture has no sky; the playable scenario has one
    bsp.serialize(filepath=files[BSP], temp=False, backup=False)
    return (expected - {STAGE}) | {SCENARIO + '.scenario'}


def private_map(geometry, stock, sky, output):
    sky = sky.replace('/', '\\')
    required = stock_tags(sky)
    missing = sorted(name for name in required if not tag_file(stock, name).is_file())
    if missing:
        raise ValueError('Missing stock HEK tags: ' + ', '.join(missing))
    if output.resolve() == geometry.resolve():
        raise ValueError('The output would overwrite the authored source map')
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as folder:
        authored = Path(folder) / 'tags'
        authored.mkdir()
        expected = required | prepare_geometry(geometry, authored, sky)
        built = Path(folder) / 'halo_royale.map'
        subprocess.run(['invader-build', '-g', 'gbx-custom', '-r', 'none', '-t', str(authored),
                        '-t', str(stock), '-o', str(built), SCENARIO], check=True)
        absent = sorted(expected - cache_index(built, 'mp'))
        if absent:
            raise ValueError('Compiled map is missing tags: ' + ', '.join(absent))
        os.replace(built, output)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--geometry', type=Path, required=True, help='compiled royale_crossroads StageKit cache')
    parser.add_argument('--tags', type=Path, required=True, help='private HEK tags, including the campaign Pelican')
    parser.add_argument('--sky', required=True, help='stock daylight sky tag path without extension')
    parser.add_argument('--output', type=Path, required=True, help='destination halo_royale.map')
    args = parser.parse_args()
    try:
        private_map(args.geometry, args.tags, args.sky, args.output)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f'{parser.prog}: {error}\n')
