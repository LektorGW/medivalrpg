# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Strict offline JSON, cube, hierarchy, animation and PNG checks; stdlib only."""
import base64, copy, itertools, json, math, struct, zlib
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]/'src/main/resources/models'
EXPECTED={'kolej_'+n for n in ('parowoz','tender','pasazerski','salonowy','kryty','weglarka','platforma','cysterna','hamulcowy')}
# Animation names used by pl.medivalrpg.kolej.visual.TrainModels / LocomotiveAnimator.
ANIMATIONS={'kolej_parowoz':{'run','run_reverse','idle','start','brake','whistle'},'kolej_pasazerski':{'run','run_reverse','doors'},'kolej_kryty':{'run','run_reverse','doors'}}
LOOPS={'loop','once','hold'}

def check_animations(d,gs):
    names=[a['name'] for a in d['animations']]; assert len(names)==len(set(names)),'duplicate animation'
    assert set(names)==ANIMATIONS.get(d['name'],{'run','run_reverse'}),(d['name'],names)
    roots={n['uuid'] for n in d['outliner'] if isinstance(n,dict)}
    uuids=set()
    for a in d['animations']:
        assert a['loop'] in LOOPS and 0<a['length']<=10 and a['animators'],a['name']
        assert isinstance(a['override'],bool) and (a['name']=='run' or a['name']=='run_reverse' or a['override']),'non-run animations must not reset run bones'
        for k,an in a['animators'].items():
            assert k in gs and an['name']==gs[k]['name'] and an['type']=='bone'
            assert k in roots,'animated bone must be a root bone'
            for ch in {f['channel'] for f in an['keyframes']}:
                times=[f['time'] for f in an['keyframes'] if f['channel']==ch]; assert times==sorted(times) and times[0]==0 and abs(times[-1]-a['length'])<1e-9,(a['name'],ch)
            last={}
            for f in an['keyframes']:
                assert f['channel'] in ('rotation','position') and f['interpolation']=='linear' and f['uuid'] not in uuids; uuids.add(f['uuid'])
                v=[float(f['data_points'][0][c]) for c in 'xyz']; assert all(math.isfinite(x) for x in v)
                if f['channel']=='rotation' and 'rotation' in last: assert max(abs(a-b) for a,b in zip(v,last['rotation']))<=90,'rotation step over 90'
                if f['channel']=='position': assert max(map(abs,v))<=20,'position offset too large'
                last[f['channel']]=v
            for ch in ('rotation','position'):
                end=[f for f in an['keyframes'] if f['channel']==ch]
                if end and a['loop']!='hold' and not an['name'].startswith('wheel_'):
                    first=[float(end[0]['data_points'][0][c]) for c in 'xyz'];final=[float(end[-1]['data_points'][0][c]) for c in 'xyz']
                    assert first==final,'non-wheel animation must return to rest/loop seamlessly'
    if True:
        rev=next(a for a in d['animations'] if a['name']=='run_reverse')
        assert set(rev['animators'])=={k for k,g in gs.items() if g['name'].startswith('wheel_')}
        for an in rev['animators'].values(): assert [float(f['data_points'][0]['x']) for f in an['keyframes']]==[0,-90,-180,-270,-360]
    return len(d['animations'])

def png_check(blob):
    assert blob[:8]==b'\x89PNG\r\n\x1a\n'
    pos=8; data=b''; tags=[]
    while pos<len(blob):
        size=struct.unpack('>I',blob[pos:pos+4])[0]; tag=blob[pos+4:pos+8]; b=blob[pos+8:pos+8+size]
        assert len(b)==size and zlib.crc32(tag+b)==struct.unpack('>I',blob[pos+8+size:pos+12+size])[0]
        if tag==b'IHDR': assert struct.unpack('>IIBBBBB',b)==(128,128,8,6,0,0,0)
        if tag==b'IDAT': data+=b
        tags.append(tag); pos+=size+12
    assert tags[0]==b'IHDR' and tags[-1]==b'IEND' and pos==len(blob)
    raw=zlib.decompress(data); assert len(raw)==128*513
    colors=set()
    for y in range(128):
        row=raw[y*513:(y+1)*513]; assert row[0]==0
        for x in range(128):
            rgba=row[1+x*4:5+x*4]; assert rgba[3]==255; colors.add(rgba)
    assert len(colors)>100

def validate(path,document=None):
    d=document if document is not None else json.loads(path.read_text(encoding='utf-8')); assert d['name']==path.stem
    assert d['meta']=={'format_version':'5.0','model_format':'free','box_uv':False}
    es={e['uuid']:e for e in d['elements']}; gs={g['uuid']:g for g in d['groups']}
    assert len(es)==len(d['elements']) and len(gs)==len(d['groups']) and not es.keys()&gs.keys()
    refs=[]; bones=[]
    def visit(nodes):
        for n in nodes:
            if isinstance(n,str): assert n in es; refs.append(n)
            else:
                assert n['uuid'] in gs; bones.append(n['uuid']); assert n['origin']==gs[n['uuid']]['origin']; visit(n['children'])
    visit(d['outliner']); assert set(refs)==set(es) and len(refs)==len(es); assert set(bones)==set(gs) and len(bones)==len(gs)
    for e in es.values():
        assert e['type']=='cube'
        for key in ('from','to','origin','rotation'): assert len(e[key])==3 and all(math.isfinite(x) for x in e[key])
        assert all(a<b for a,b in zip(e['from'],e['to'])),e['name']
        assert sum(x!=0 for x in e['rotation'])<=1
        for bits in itertools.product((0,1),repeat=3):
            point=[(e['to'][i] if bits[i] else e['from'][i])-e['origin'][i] for i in range(3)]
            for axis,degree in enumerate(e['rotation']):
                if not degree: continue
                i,j=((1,2),(2,0),(0,1))[axis]; t=math.radians(degree); c,s=math.cos(t),math.sin(t)
                point[i],point[j]=point[i]*c-point[j]*s,point[i]*s+point[j]*c
            assert point[1]+e['origin'][1]>=-.02, 'geometry below rail'
        assert len(e['faces'])==6
        for face in e['faces'].values(): assert face['texture']==0 and len(face['uv'])==4 and all(0<=v<=128 for v in face['uv'])
    tex=base64.b64decode(d['textures'][0]['source'].split(',')[1],validate=True)
    png_check(tex); assert tex==(ROOT/'textures'/(path.stem+'.png')).read_bytes()
    run=next(a for a in d['animations'] if a['name']=='run'); assert run['loop']=='loop' and run['length']==1
    wheels={k for k,g in gs.items() if g['name'].startswith('wheel_')}; assert set(run['animators'])==wheels and len(wheels)>=4
    for k,a in run['animators'].items():
        assert a['name']==gs[k]['name']; frames=a['keyframes']; assert [f['time'] for f in frames]==[0,.25,.5,.75,1]
        assert [float(f['data_points'][0]['x']) for f in frames]==[0,90,180,270,360]
        assert all(f['channel']=='rotation' and f['interpolation']=='linear' for f in frames)
    def check_wheel_nodes(nodes):
        for n in nodes:
            if not isinstance(n,dict): continue
            if n['uuid'] in wheels:
                for cube in n['children']:
                    e=es[cube]
                    if any(e['rotation']): assert e['origin']==n['origin'], 'wheel rotation center differs from axle'
            check_wheel_nodes(n['children'])
    check_wheel_nodes(d['outliner'])
    check_animations(d,gs)
    return len(es),len(wheels)

def main():
    paths=sorted(ROOT.glob('*.bbmodel')); assert {p.stem for p in paths}==EXPECTED
    for p in paths:
        cubes,wheels=validate(p); print(f'PASS {p.stem}: {cubes} cubes, {wheels} wheel bones, PNG CRC + pixels OK')
    manifest=json.loads((Path(__file__).parent/'model_manifest.json').read_text(encoding='utf-8'))
    for m in manifest:
        assert m['length']==112
        d=json.loads((ROOT/(m['name']+'.bbmodel')).read_text(encoding='utf-8')); groups={g['name']:g for g in d['groups']}
        for s in m['seats']: assert groups[s['name']]['origin']==s['position']
        for w in m['wheels']: assert groups[w['name']]['origin']==w['pivot'] and w['pivot'][1]==w['radius']
    print('PASS manifest: lengths, seat pivots, rail level; 9/9 models')
    p=ROOT/'kolej_parowoz.bbmodel'; original=json.loads(p.read_text(encoding='utf-8'))
    for mutation in ('uv','geometry','hierarchy','animation','texture','pivot','missing_animation','orphan_animator','reverse_direction','override'):
        d=copy.deepcopy(original)
        if mutation=='uv': d['elements'][0]['faces']['north']['uv'][0]=-1
        if mutation=='geometry': d['elements'][0]['to'][0]=d['elements'][0]['from'][0]
        if mutation=='hierarchy': d['outliner'][0]['children'].append(d['elements'][0]['uuid'])
        if mutation=='animation': next(iter(d['animations'][0]['animators'].values()))['keyframes'][-1]['data_points'][0]['x']='0'
        if mutation=='texture': d['textures'][0]['source']='data:image/png;base64,YWJj'
        if mutation=='missing_animation': d['animations']=[a for a in d['animations'] if a['name']!='whistle']
        if mutation=='orphan_animator':
            a=next(a for a in d['animations'] if a['name']=='idle'); a['animators']['00000000-0000-0000-0000-000000000000']=next(iter(a['animators'].values()))
        if mutation=='reverse_direction':
            a=next(a for a in d['animations'] if a['name']=='run_reverse'); next(iter(a['animators'].values()))['keyframes'][4]['data_points'][0]['x']='360'
        if mutation=='override': next(a for a in d['animations'] if a['name']=='brake')['override']=False
        if mutation=='pivot':
            e=next(e for e in d['elements'] if any(e['rotation']) and e['name'].startswith('rim')); e['origin'][2]+=10
        try: validate(p,d)
        except (AssertionError,ValueError,struct.error): print('PASS rejected corrupted '+mutation)
        else: raise AssertionError('Accepted corrupted '+mutation)

if __name__=='__main__': main()
