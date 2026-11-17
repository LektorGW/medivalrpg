# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Deterministic Blockbench 5 cube models. Standard library; no server access."""
from pathlib import Path
import base64, json, math, random, uuid, struct, zlib

ROOT = Path(__file__).resolve().parents[1] / 'src/main/resources/models'
TOOLS = Path(__file__).resolve().parent
MATERIALS = [('iron',(43,48,51)),('steel',(118,127,130)),('brass',(183,137,57)),('oak',(115,72,38)),('plank',(153,101,53)),('green',(32,76,57)),('wine',(104,33,43)),('velvet',(148,44,51)),('coal',(25,26,28)),('cream',(223,204,155)),('lamp',(255,219,111)),('blue',(46,85,116)),('roof',(58,62,60)),('leather',(76,47,31)),('white',(200,209,210)),('red',(163,47,35))]

def uid(s): return str(uuid.uuid5(uuid.NAMESPACE_URL,'medivalrpg/kolej/'+s))

def atlas():
    px=bytearray(128*128*4); rng=random.Random(27102026)
    for k,(name,col) in enumerate(MATERIALS):
        ox,oy=(k%4)*32,(k//4)*32
        for y in range(32):
            for x in range(32):
                n=rng.randint(-7,7)
                if name in ('oak','plank'): n += -20 if y%8==0 else (5 if (x+y//8*11)%23==0 else 0)
                if name in ('iron','steel','green','wine','blue') and (x,y) in ((3,3),(28,3),(3,28),(28,28)): n=35
                off=((oy+y)*128+ox+x)*4
                px[off:off+4]=bytes([max(0,min(255,c+n)) for c in col]+[255])
    def chunk(tag,data): return struct.pack('>I',len(data))+tag+data+struct.pack('>I',zlib.crc32(tag+data))
    raw=b''.join(b'\0'+px[y*512:(y+1)*512] for y in range(128))
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',128,128,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(raw))+chunk(b'IEND',b'')

def num(v):
    v=round(float(v),5)
    return str(int(v)) if v==int(v) else repr(v)

def frames(owner,bone,channel,points,interp='linear'):
    """points: [(time,(x,y,z))]. UUIDs derive from model/animation/bone/channel/index."""
    return [{'uuid':uid(owner+'/'+bone+'/'+channel+'/'+str(i)),'channel':channel,'time':t,'interpolation':interp,
             'data_points':[{'x':num(x),'y':num(y),'z':num(z)}]} for i,(t,(x,y,z)) in enumerate(points)]

def bone_anim(group,keyframes):
    return {group['uuid']:{'name':group['name'],'type':'bone','rotation_global':False,'keyframes':keyframes}}

def wheel_spin(model,anim,degrees,length,steps):
    # Steps of at most 90 degrees: no shortest-path ambiguity between keyframes.
    result={}
    for g,r in model.wheels:
        result.update(bone_anim(g,frames(model.name+'/'+anim,g['name'],'rotation',[(round(length*i/steps,5),(degrees*i/steps,0,0)) for i in range(steps+1)])))
    return result

class Model:
    def __init__(self,name):
        self.name=name; self.elements=[]; self.groups=[]; self.out=[]; self.wheels=[]; self.seats=[]; self.doors=[]
        self.body=self.group('body',[0,0,0])
    def group(self,name,pivot,parent=None):
        g={'name':name,'origin':pivot,'rotation':[0,0,0],'uuid':uid(self.name+'/'+name),'export':True,'visibility':True,'isOpen':True,'children':[]}
        self.groups.append({k:v for k,v in g.items() if k!='children'})
        (parent['children'] if parent else self.out).append(g); return g
    def box(self,name,a,b,mat='iron',group=None,rot=None,pivot=None):
        i=len(self.elements); k=[x[0] for x in MATERIALS].index(mat); x,y=(k%4)*32,(k//4)*32
        e={'name':name,'type':'cube','uuid':uid(self.name+'/cube/'+str(i)),'from':a,'to':b,'origin':pivot or [0,0,0],'rotation':rot or [0,0,0],'box_uv':False,'autouv':0,'rescale':False,'export':True,'visibility':True,'faces':{f:{'uv':[x+1,y+1,x+31,y+31],'texture':0} for f in ('north','south','east','west','up','down')}}
        for key in ('from','to','origin','rotation'): e[key]=list(e[key])
        self.elements.append(e); (group or self.body)['children'].append(e['uuid'])
    def cylinder(self,name,center,r,length,axis='z',mat='iron',group=None):
        # Eight overlapping rotated rectangular prisms form a faceted cylinder.
        x,y,z=center
        for i in range(4):
            if axis=='z': a=[x-r,y-r*.4142,z-length/2]; b=[x+r,y+r*.4142,z+length/2]; rot=[0,0,i*45]
            else: a=[x-length/2,y-r,z-r*.4142]; b=[x+length/2,y+r,z+r*.4142]; rot=[i*45,0,0]
            self.box(name+str(i),a,b,mat,group,rot,center)
    def chassis(self,length=96,axles=(-30,30),radius=7):
        self.length=length
        self.box('oak_deck',[-19,14,-length/2],[19,17,length/2],'plank')
        for x in (-15,13): self.box('frame',[x,9,-length/2],[x+2,14,length/2],'iron')
        for z in (-length/2,length/2):
            self.box('buffer_beam',[-20,10,z-1],[20,15,z+1],'red')
            for x in (-13,13): self.box('buffer',[x-3,10,z-4],[x+3,14,z+4],'iron')
            self.box('coupler',[-2,9,z-7],[2,12,z+7],'steel')
        for n,z in enumerate(axles):
            self.box('axle',[-19,radius-.8,z-1],[19,radius+.8,z+1],'steel')
            for side,x in [('l',-19),('r',19)]:
                g=self.group(f'wheel_{n}_{side}',[x,radius,z]); self.wheels.append((g,radius))
                self.cylinder('rim',[x,radius,z],radius,2,'x','steel',g)
                self.cylinder('tyre_inner',[x,radius,z],radius-1,2.3,'x','iron',g)
                for angle in (0,45,90,135):
                    self.box('spoke',[x-1.3,radius-.55,z-radius+1],[x+1.3,radius+.55,z+radius-1],'brass',g,[angle,0,0],[x,radius,z])
                self.box('hub',[x-1.6,radius-1.6,z-1.6],[x+1.6,radius+1.6,z+1.6],'brass',g)
            for x in (-17,15):
                for j in range(3): self.box('leaf_spring',[x,10+j,z-6+j],[x+2,10.6+j,z+6-j],'iron')
    def roof(self,lo=-42,hi=42,y=49):
        self.box('roof_crown',[-13,y,lo],[13,y+2,hi],'roof')
        for s in (-1,1): self.box('roof_eave',[s*16-4,y-1,lo],[s*16+4,y+1,hi],'roof',rot=[0,0,-s*22.5],pivot=[s*16,y,0])
    def cabin(self,mat='green',lo=-40,hi=40):
        for s in (-1,1):
            x=s*18
            self.box('side_panel',[x-1,17,lo],[x+1,29,hi],mat)
            self.box('window_rail',[x-1,43,lo],[x+1,48,hi],mat)
            self.box('gold_lining',[x-1.15,27,lo],[x+1.15,27.6,hi],'brass')
            for z in range(int(lo),int(hi)+1,16): self.box('window_pillar',[x-1.2,29,z-1],[x+1.2,44,z+1],'oak')
        # Open windows and central doorways: no opaque panes obscuring the interior.
        for z in (lo,hi):
            for x in (-18,7): self.box('end_wall',[x,17,z-1],[x+11,47,z+1],mat)
            self.box('door_lintel',[-7,44,z-1],[7,48,z+1],'oak')
            self.box('step',[-9,10,z-5],[9,12,z+5],'steel')
        self.roof(lo-2,hi+2)
    def seat(self,x,z,mat='velvet',facing=1):
        n=len(self.seats); p=[x,23,z]; self.seats.append({'name':f'seat_{n:02}','position':p,'yaw':0 if facing==1 else 180})
        self.group(f'seat_{n:02}',p,self.body)
        self.box('seat_cushion',[x-5,21,z-4],[x+5,23,z+4],mat)
        zz=z-4*facing
        self.box('seat_back',[x-5,23,min(zz,zz+facing)],[x+5,34,max(zz,zz+facing)],mat)
        for dx in (-4,3): self.box('seat_leg',[x+dx,17,z-2],[x+dx+1,21,z+2],'oak')
    def animations(self,factor):
        """Extra animations after the unchanged run. override=True: bones without keyframes keep run."""
        out=[]; n=self.name
        # Reverse for every vehicle (depot manoeuvres push the tender backwards): same multiplier as run.
        out.append(('run_reverse','loop',1,wheel_spin(self,'run_reverse',-360,1,4),False))
        if n=='kolej_parowoz':
            body=next(g for g in self.groups if g['name']=='body'); valve=next(g for g in self.groups if g['name']=='whistle_valve')
            out.append(('idle','loop',2,bone_anim(body,
                frames(n+'/idle','body','position',[(0,(0,0,0)),(.5,(0,.3,0)),(1,(0,0,0)),(1.5,(0,.2,0)),(2,(0,0,0))])+
                frames(n+'/idle','body','rotation',[(0,(0,0,0)),(.5,(0,0,.2)),(1,(0,0,0)),(1.5,(0,0,-.2)),(2,(0,0,0))])),True))
            start=wheel_spin(self,'start',540,1.6,8)
            start.update(bone_anim(body,frames(n+'/start','body','position',[(0,(0,0,0)),(.15,(0,.4,-.8)),(.4,(0,.2,.6)),(.7,(0,.3,-.3)),(1.1,(0,.1,.2)),(1.6,(0,0,0))])))
            out.append(('start','once',1.6,start,True))
            # BetterModel negates keyframe X and renders the model with Blockbench +Z (our front) at its -Z:
            # positive X here dips the front (verified server-side in verify-rail-anim-20261029).
            out.append(('brake','loop',.4,bone_anim(body,
                frames(n+'/brake','body','rotation',[(0,(.5,0,0)),(.2,(.7,0,.1)),(.4,(.5,0,0))])+
                frames(n+'/brake','body','position',[(0,(0,-.2,0)),(.1,(0,-.1,0)),(.2,(0,-.25,0)),(.3,(0,-.1,0)),(.4,(0,-.2,0))])),True))
            out.append(('whistle','once',1.5,bone_anim(valve,frames(n+'/whistle','whistle_valve','rotation',[(0,(0,0,0)),(.15,(-30,0,0)),(1.2,(-30,0,0)),(1.5,(0,0,0))])),True))
        if self.doors:
            animators={}
            for g,vec in self.doors:
                v=(vec[0],vec[1],vec[2]*factor)
                animators.update(bone_anim(g,frames(n+'/doors',g['name'],'position',[(0,(0,0,0)),(.8,v),(4.2,v),(5,(0,0,0))])))
            out.append(('doors','once',5,animators,True))
        return [{'uuid':uid(n+'/'+a),'name':a,'loop':l,'length':t,'snapping':20,'override':o,'animators':an} for a,l,t,an,o in out]
    def save(self,im):
        png=im
        # Unit contract: 16 model units per metre; body lengths exclude buffers.
        target=112
        factor=target/self.length
        wheel_centers={c:g['origin'][2] for g,r in self.wheels for c in g['children']}
        for e in self.elements:
            for key in ('from','to','origin'):
                if e['uuid'] in wheel_centers: e[key][2]+=wheel_centers[e['uuid']]*(factor-1)
                else: e[key][2]*=factor
        for g in self.groups: g['origin'][2]*=factor
        self.length=target
        animators={}
        for g,r in self.wheels:
            animators[g['uuid']]={'name':g['name'],'type':'bone','rotation_global':False,'keyframes':[{'uuid':uid(self.name+g['name']+str(i)),'channel':'rotation','time':i/4,'interpolation':'linear','data_points':[{'x':str(i*90),'y':'0','z':'0'}]} for i in range(5)]}
        d={'meta':{'format_version':'5.0','model_format':'free','box_uv':False},'name':self.name,'model_identifier':self.name,'resolution':{'width':128,'height':128},'elements':self.elements,'groups':self.groups,'outliner':self.out,'textures':[{'name':self.name+'.png','id':'0','uuid':uid(self.name+'/texture'),'width':128,'height':128,'uv_width':128,'uv_height':128,'source':'data:image/png;base64,'+base64.b64encode(png).decode(),'internal':True,'mode':'bitmap','visible':True,'render_mode':'default'}],'animations':[{'uuid':uid(self.name+'/run'),'name':'run','loop':'loop','length':1,'snapping':20,'override':False,'animators':animators}]+self.animations(factor)}
        (ROOT/(self.name+'.bbmodel')).write_text(json.dumps(d,ensure_ascii=False,indent=2),encoding='utf-8')
        (ROOT/'textures'/ (self.name+'.png')).write_bytes(png)
        return {'name':self.name,'length':self.length,'seats':self.seats,'wheels':[{'name':g['name'],'pivot':g['origin'],'radius':r} for g,r in self.wheels],'cubes':len(self.elements),
                'animations':[{'name':a['name'],'loop':a['loop'],'length':a['length'],'bones':sorted(v['name'] for v in a['animators'].values())} for a in d['animations']]}

def build():
    ROOT.mkdir(parents=True,exist_ok=True); (ROOT/'textures').mkdir(exist_ok=True); im=atlas(); result=[]
    for kind in ('parowoz','tender','pasazerski','salonowy','kryty','weglarka','platforma','cysterna','hamulcowy'):
        m=Model('kolej_'+kind)
        m.chassis(112 if kind=='parowoz' else 96,(-34,-10,14,36) if kind=='parowoz' else (-30,30),9 if kind=='parowoz' else 7)
        if kind=='parowoz':
            m.cylinder('boiler',[0,32,10],13,62,mat='iron')
            for z in (-18,0,24,39): m.cylinder('boiler_band',[0,32,z],13.4,1.3,mat='brass')
            m.box('smokestack',[-5,41,29],[5,59,39],'iron'); m.box('chimney_lip',[-7,57,27],[7,60,41],'steel')
            m.box('steam_dome',[-5,43,-3],[5,51,7],'brass')
            m.cabin('green',-50,-23)
            m.box('firebox',[-10,17,-26],[10,36,-18],'iron'); m.box('fire_door',[-5,23,-27],[5,31,-26],'brass')
            for x in (-15,14): m.box('handrail',[x,39,-18],[x+1,40,40],'brass')
            for x in (-20,18): m.box('running_board',[x,19,-18],[x+2,21,45],'steel')
            m.box('headlamp',[-4,32,43],[4,40,47],'brass'); m.box('lamp_lens',[-3,33,47],[3,39,47.5],'lamp')
            m.seat(-9,-39,'leather'); m.seat(9,-39,'leather')
            # Steam whistle in front of the cab roof; the lever is a root bone so run keeps the wheels.
            m.box('whistle_pipe',[2.5,45,-21],[3.5,49,-20],'brass'); m.box('whistle_bell',[2,49,-21.5],[4,53,-19.5],'brass')
            valve=m.group('whistle_valve',[3,53,-20.5]); m.box('whistle_lever',[2.7,52.6,-20.5],[3.3,53.2,-15],'steel',valve)
        elif kind in ('pasazerski','salonowy','hamulcowy'):
            m.cabin('wine' if kind=='salonowy' else 'green')
            for z in ((-24,-8,8,24) if kind=='pasazerski' else (-22,18)):
                for x in (-11,11): m.seat(x,z,'velvet' if kind!='hamulcowy' else 'leather',1 if z<0 else -1)
            if kind=='pasazerski':
                # Sliding end doors just inside each doorway; they open into the end-wall pockets (X=-14..-7, 7..14).
                for end,z in (('front',39.0),('rear',-39.0)):
                    zz=(z-.7,z-.1) if z>0 else (z+.1,z+.7)
                    for side,x0,x1,dx in (('l',-7,0,-7),('r',0,7,7)):
                        door=m.group(f'door_{end}_{side}',[(x0+x1)/2,30.5,z]); m.doors.append((door,(dx,0,0)))
                        m.box('door_leaf',[x0,17,zz[0]],[x1,44,zz[1]],'oak',door)
            if kind=='salonowy':
                m.box('carpet',[-7,17,-31],[7,17.25,30],'wine')
                m.box('table',[-7,28,-4],[7,30,6],'oak'); m.box('table_pedestal',[-2,17,-1],[2,28,3],'brass')
                for z in (-29,29): m.box('ceiling_lamp',[-3,44,z-3],[3,47,z+3],'lamp')
            if kind=='hamulcowy':
                m.box('cupola',[-11,50,-9],[11,60,9],'oak'); m.roof(-11,11,61)
                m.cylinder('brake_handwheel',[0,29,42],5,1,mat='brass')
                m.box('brake_column',[-1,17,41],[1,29,43],'steel')
        elif kind in ('tender','weglarka','kryty'):
            height=46 if kind=='kryty' else 34
            for x in (-18,17): m.box('side',[x,17,-42],[x+1,height,42],'oak')
            for z in (-42,41): m.box('end',[-18,17,z],[18,height,z+1],'oak')
            for z in range(-38,40,12):
                for x in (-19,18): m.box('iron_strap',[x,18,z],[x+1,height,z+1],'iron')
            if kind=='kryty':
                m.roof(-44,44,47)
                for x,side in ((-19.5,'l'),(18.5,'r')):
                    # Same cube order as before; door and handle move into a root bone sliding along the rail.
                    door=m.group('door_'+side,[x+.5,31,0]); m.doors.append((door,(0,0,15)))
                    m.box('sliding_door',[x,18,-11],[x+1,44,11],'plank',door); m.box('door_rail',[x-.3,44,-26],[x+1.3,45,26],'steel')
                    m.box('door_handle',[x-.5,29,5],[x+1.5,34,6],'brass',door)
            else:
                rng=random.Random(7)
                for x in range(-14,15,7):
                    for z in range(-36,38,8):
                        h=rng.randint(25,34); m.box('coal_lump',[x-3,20,z-3],[x+3,h,z+3],'coal',rot=[0,rng.choice((-22.5,0,22.5)),0],pivot=[x,20,z])
                if kind=='tender': m.box('water_hatch',[-6,34,-38],[6,36,-26],'steel')
        elif kind=='platforma':
            for z in (-40,-20,0,20,40):
                for x in (-19,18): m.box('stake',[x,17,z-1],[x+1,31,z+1],'oak')
            for z in range(-42,44,7): m.box('deck_seam',[-18,17,z],[18,17.2,z+.4],'oak')
        elif kind=='cysterna':
            m.cylinder('water_tank',[0,32,0],15,74,mat='blue')
            for z in (-37,-23,23,37): m.cylinder('tank_hoop',[0,32,z],15.4,1.5,mat='steel')
            m.box('filler_neck',[-4,46,-4],[4,51,4],'steel'); m.box('filler_lid',[-5,51,-5],[5,52,5],'brass')
            for z in (-25,25): m.box('saddle',[-14,17,z-3],[14,22,z+3],'oak')
            for y in range(20,48,5): m.box('ladder_rung',[19,y,-5],[21,y+1,5],'steel')
            for z in (-5,5): m.box('ladder_rail',[19,17,z],[20,49,z+1],'steel')
            m.box('water_outlet',[-2,15,38],[2,24,42],'brass')
        result.append(m.save(im))
    (TOOLS/'model_manifest.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print('Generated',len(result),'models;',sum(r['cubes'] for r in result),'cubes')

if __name__=='__main__': build()
