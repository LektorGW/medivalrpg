# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Blender (background) preview of the Armia models with their real cubes, UVs, embedded texture and a sampled
animation pose. The ship scene also shows the invisible shulker deck tiles (red) under the visible deck.

    blender -b -P render_blender.py -- <out dir> [scene ...]
"""
import base64, itertools, json, math, os, sys, tempfile
from pathlib import Path

import bpy
from mathutils import Matrix, Vector, Euler

HERE = Path(__file__).resolve().parent
MODELS = HERE.parent / 'src/main/resources/models'
COUNTRY = (0.13, 0.33, 0.80)  # dyed parts in a sample country colour


def euler_m(r):
    # Blockbench: R = Rz * Ry * Rx (degrees)
    return (Matrix.Rotation(math.radians(r[2]), 4, 'Z') @ Matrix.Rotation(math.radians(r[1]), 4, 'Y')
            @ Matrix.Rotation(math.radians(r[0]), 4, 'X'))


def sample(keys, t):
    keys = sorted(keys)
    if t <= keys[0][0]:
        return keys[0][1]
    for (t0, v0), (t1, v1) in zip(keys, keys[1:]):
        if t0 <= t <= t1:
            u = 0 if t1 == t0 else (t - t0) / (t1 - t0)
            return tuple(a + (b - a) * u for a, b in zip(v0, v1))
    return keys[-1][1]


def pose(d, anims):
    tracks = {}
    for name, at in anims:
        a = next(x for x in d['animations'] if x['name'] == name)
        local = at % a['length'] if a['loop'] == 'loop' else min(at, a['length'])
        for uid, an in a['animators'].items():
            ch = tracks.setdefault(uid, {})
            for c in ('rotation', 'position', 'scale'):
                keys = [(k['time'], tuple(float(k['data_points'][0][q]) for q in 'xyz')) for k in an['keyframes'] if k['channel'] == c]
                if keys:
                    ch[c] = sample(keys, local)
    groups = {g['uuid']: g for g in d['groups']}
    out, names = {}, {}

    def walk(nodes, parent, bone):
        for n in nodes:
            if isinstance(n, str):
                out[n] = parent
                names[n] = bone
                continue
            g = groups[n['uuid']]
            tr = tracks.get(n['uuid'], {})
            o = Vector(g['origin'])
            rot = [g.get('rotation', [0, 0, 0])[i] + (tr.get('rotation') or (0, 0, 0))[i] for i in range(3)]
            sc = tr.get('scale') or (1, 1, 1)
            pos = Vector(tr.get('position') or (0, 0, 0))
            m = Matrix.Translation(o + pos) @ euler_m(rot) @ Matrix.Diagonal((sc[0], sc[1], sc[2], 1)) @ Matrix.Translation(-o)
            walk(n['children'], parent @ m, g['name'])
    walk(d['outliner'], Matrix.Identity(4), None)
    return out, names


def material(png_path, tint=None):
    key = 'armia_tint' if tint else 'armia'
    if key in bpy.data.materials:
        return bpy.data.materials[key]
    mat = bpy.data.materials.new(key)
    mat.use_nodes = True
    nodes = mat.node_tree.nodes
    bsdf = next(n for n in nodes if n.type == 'BSDF_PRINCIPLED')
    img = bpy.data.images.load(png_path, check_existing=True)
    tex = nodes.new('ShaderNodeTexImage')
    tex.image = img
    tex.interpolation = 'Closest'
    if tint:
        mix = nodes.new('ShaderNodeMix')
        mix.data_type = 'RGBA'
        mix.blend_type = 'MULTIPLY'
        mix.inputs['Factor'].default_value = 1.0
        mat.node_tree.links.new(tex.outputs['Color'], mix.inputs['A'])
        mix.inputs['B'].default_value = (*tint, 1)
        mat.node_tree.links.new(mix.outputs['Result'], bsdf.inputs['Base Color'])
    else:
        mat.node_tree.links.new(tex.outputs['Color'], bsdf.inputs['Base Color'])
    bsdf.inputs['Roughness'].default_value = 0.85
    return mat


FACE_CORNERS = {  # Blockbench face -> corner bit order (x,y,z) for u-left/v-top
    'north': [(1, 1, 0), (0, 1, 0), (0, 0, 0), (1, 0, 0)],
    'south': [(0, 1, 1), (1, 1, 1), (1, 0, 1), (0, 0, 1)],
    'east': [(1, 1, 1), (1, 1, 0), (1, 0, 0), (1, 0, 1)],
    'west': [(0, 1, 0), (0, 1, 1), (0, 0, 1), (0, 0, 0)],
    'up': [(0, 1, 0), (1, 1, 0), (1, 1, 1), (0, 1, 1)],
    'down': [(0, 0, 1), (1, 0, 1), (1, 0, 0), (0, 0, 0)],
}


def build(name, anims, offset=(0, 0, 0), tinted=('tarcze', 'proporzec', 'bandera', 'herb')):
    d = json.loads((MODELS / (name + '.bbmodel')).read_text(encoding='utf-8'))
    png = base64.b64decode(d['textures'][0]['source'].split(',')[1])
    path = os.path.join(tempfile.gettempdir(), 'armia_atlas_preview.png')
    Path(path).write_bytes(png)
    worlds, bones = pose(d, anims)
    verts, faces, uvs, mats = [], [], [], []
    base = Matrix.Translation(Vector(offset))
    # Blockbench Y-up, Z towards the viewer -> Blender Z-up: (x, y, z) -> (x, -z, y)
    swap = Matrix(((1, 0, 0, 0), (0, 0, -1, 0), (0, 1, 0, 0), (0, 0, 0, 1)))
    for e in d['elements']:
        w = worlds[e['uuid']]
        a, b = e['from'], e['to']
        rot = e['rotation']
        el = Matrix.Identity(4)
        if any(rot):
            o = Vector(e['origin'])
            el = Matrix.Translation(o) @ euler_m(rot) @ Matrix.Translation(-o)
        full = base @ swap @ Matrix.Scale(1 / 16, 4) @ w @ el
        tint = any(bones.get(e['uuid'], '') and bones[e['uuid']].startswith(p) for p in tinted)
        for face, corners in FACE_CORNERS.items():
            uv = e['faces'][face]['uv']
            idx = []
            for c in corners:
                p = Vector([b[i] if c[i] else a[i] for i in range(3)])
                verts.append(full @ p)
                idx.append(len(verts) - 1)
            faces.append(idx)
            u1, v1, u2, v2 = [x / 128 for x in uv]
            uvs.append([(u1, 1 - v1), (u2, 1 - v1), (u2, 1 - v2), (u1, 1 - v2)])
            mats.append(1 if tint else 0)
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata([tuple(v) for v in verts], [], faces)
    mesh.update()
    layer = mesh.uv_layers.new()
    for poly, coords in zip(mesh.polygons, uvs):
        for li, co in zip(poly.loop_indices, coords):
            layer.data[li].uv = co
        poly.material_index = mats[poly.index]
    mesh.materials.append(material(path))
    mesh.materials.append(material(path, COUNTRY))
    obj = bpy.data.objects.new(name, mesh)
    bpy.context.scene.collection.objects.link(obj)
    return obj


def tiles(kind, offset):
    """DeckRules.tiles: the invisible shulker colliders (translucent red) under the visible deck."""
    hw, hl = {'koga': (1.5, 4.5), 'karawela': (2.0, 5.5)}[kind]
    scale = hw
    end = hl - .7 - scale / 2
    n = math.ceil(2 * end / (scale * .9))
    deck = 1 + hw
    mat = bpy.data.materials.get('kolizja') or bpy.data.materials.new('kolizja')
    mat.use_nodes = True
    bsdf = next(x for x in mat.node_tree.nodes if x.type == 'BSDF_PRINCIPLED')
    bsdf.inputs['Base Color'].default_value = (0.9, 0.08, 0.05, 1)
    bsdf.inputs['Alpha'].default_value = 0.35
    try:
        mat.surface_render_method = 'BLENDED'
    except (AttributeError, TypeError):
        pass
    for row in range(n + 1):
        z = -end + 2 * end * row / n if n else 0
        for x in (-scale * .45, scale * .45):
            bpy.ops.mesh.primitive_cube_add(size=scale, location=(x + offset[0], -z + offset[1], deck - scale / 2 + offset[2]))
            cube = bpy.context.active_object
            cube.data.materials.append(mat)


def scene(kind):
    for o in list(bpy.data.objects):
        bpy.data.objects.remove(o, do_unlink=True)
    sc = bpy.context.scene
    try:
        sc.render.engine = 'BLENDER_EEVEE_NEXT'
    except TypeError:
        try:
            sc.render.engine = 'BLENDER_EEVEE'
        except TypeError:
            sc.render.engine = 'CYCLES'
    sc.render.resolution_x, sc.render.resolution_y = 1600, 1000
    sc.render.film_transparent = False
    world = bpy.data.worlds.get('World') or bpy.data.worlds.new('World')
    sc.world = world
    world.use_nodes = True
    bg = next(n for n in world.node_tree.nodes if n.type == 'BACKGROUND')
    bg.inputs['Color'].default_value = (0.62, 0.74, 0.86, 1)
    bg.inputs['Strength'].default_value = 0.9
    bpy.ops.object.light_add(type='SUN', location=(10, -10, 20))
    sun = bpy.context.active_object
    sun.data.energy = 3.2
    sun.rotation_euler = Euler((math.radians(50), math.radians(10), math.radians(35)))
    return sc


def water(size, z, color=(0.12, 0.32, 0.45)):
    bpy.ops.mesh.primitive_plane_add(size=size, location=(0, 0, z))
    plane = bpy.context.active_object
    mat = bpy.data.materials.new('woda')
    mat.use_nodes = True
    bsdf = next(x for x in mat.node_tree.nodes if x.type == 'BSDF_PRINCIPLED')
    bsdf.inputs['Base Color'].default_value = (*color, 1)
    bsdf.inputs['Roughness'].default_value = 0.2
    bsdf.inputs['Alpha'].default_value = 0.78
    try:
        mat.surface_render_method = 'BLENDED'
    except (AttributeError, TypeError):
        pass
    plane.data.materials.append(mat)


def camera(location, target, lens=50):
    bpy.ops.object.camera_add(location=location)
    cam = bpy.context.active_object
    direction = Vector(target) - Vector(location)
    cam.rotation_euler = direction.to_track_quat('-Z', 'Y').to_euler()
    cam.data.lens = lens
    bpy.context.scene.camera = cam


def render(path):
    bpy.context.scene.render.filepath = str(path)
    bpy.ops.render.render(write_still=True)
    print('RENDERED', path)


SCENES = {
    'statki': lambda: (
        build('armia_koga', [('fala', 1.0), ('zagle_rozwin', 9), ('lopot', .25), ('flagi', .3)], (-4.5, 0, 0)),
        build('armia_karawela', [('fala', 2.2), ('zagle_rozwin', 9), ('lopot', .6), ('flagi', .7)], (5.0, 5, 0)),
        water(80, 0.05), camera((-14, -17, 9), (1, 2, 3.2), 38)),
    'poklad_kolizja': lambda: (
        build('armia_koga', [('zagle_zwiniete', 1), ('kotwica_opuszczona', 1), ('flagi', .2)]),
        tiles('koga', (0, 0, 0.002)), water(60, 0.05), camera((7.5, -9.5, 9.5), (0, 0, 2.2), 34)),
    'machiny': lambda: (
        build('armia_trebusz', [('strzal', 0.1)], (-9, 0, 0)),
        build('armia_katapulta', [('strzal', 0.11)], (-3, 0, 0)),
        build('armia_taran', [('uderzenie', 0.12)], (2.6, 0, 0)),
        build('armia_armata', [('strzal', 0.3)], (8, 0, 0)),
        water(80, -0.01, (0.32, 0.42, 0.22)), camera((-6, -21, 10), (-0.5, 3, 2.2), 36)),
    'machiny_spoczynek': lambda: (
        build('armia_trebusz', [], (-9, 0, 0)),
        build('armia_katapulta', [], (-3, 0, 0)),
        build('armia_taran', [], (2.6, 0, 0)),
        build('armia_armata', [], (8, 0, 0)),
        water(80, -0.01, (0.32, 0.42, 0.22)), camera((6, -21, 9), (-0.5, 3, 1.8), 36)),
}


def main():
    argv = sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else []
    out = Path(argv[0] if argv else '.').resolve()
    out.mkdir(parents=True, exist_ok=True)
    for name in argv[1:] or list(SCENES):
        scene(name)
        SCENES[name]()
        render(out / (name + '.png'))


if __name__ == '__main__':
    main()
