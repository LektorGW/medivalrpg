# Copyright (c) 2026 Maciej Kaczmarczyk — lektoro.pl
"""Offline painter projection of actual cube faces, including element rotations."""
import itertools, json, math, struct, zlib
from pathlib import Path
from generate_models import MATERIALS, ROOT

W,H=1440,900
pixels=bytearray(bytes((229,224,211))*W*H)

def polygon(points,color):
    for y in range(max(0,math.ceil(min(v[1] for v in points))), min(H,math.ceil(max(v[1] for v in points)))):
        xs=[]
        for a,b in zip(points,points[1:]+points[:1]):
            if min(a[1],b[1])<=y+.5<max(a[1],b[1]): xs.append(a[0]+(y+.5-a[1])*(b[0]-a[0])/(b[1]-a[1]))
        xs.sort()
        for left,right in zip(xs[::2],xs[1::2]):
            lo=max(0,math.ceil(left)); hi=min(W,math.ceil(right))
            if hi>lo: pixels[(y*W+lo)*3:(y*W+hi)*3]=bytes(color)*(hi-lo)

def rotate(p,e):
    p=[p[i]-e['origin'][i] for i in range(3)]
    for axis,degree in enumerate(e['rotation']):
        if not degree: continue
        a,b=((1,2),(2,0),(0,1))[axis]; t=math.radians(degree); c,s=math.cos(t),math.sin(t)
        p[a],p[b]=p[a]*c-p[b]*s,p[a]*s+p[b]*c
    return [p[i]+e['origin'][i] for i in range(3)]

order=['parowoz','tender','pasazerski','salonowy','kryty','weglarka','platforma','cysterna','hamulcowy']
for index,name in enumerate(order):
    d=json.loads((ROOT/('kolej_'+name+'.bbmodel')).read_text(encoding='utf-8')); faces=[]
    for e in d['elements']:
        vertices=[rotate([e['to'][i] if bit[i] else e['from'][i] for i in range(3)],e) for bit in itertools.product((0,1),repeat=3)]
        uv=e['faces']['north']['uv']; k=int(uv[1]//32)*4+int(uv[0]//32); base=MATERIALS[k][1]
        for ids,shade in [([0,1,3,2],.65),([4,6,7,5],.85),([0,4,5,1],.55),([2,3,7,6],1.16),([0,2,6,4],.75),([1,5,7,3],.94)]:
            ps=[vertices[i] for i in ids]; depth=sum(.8*p[0]-.49*p[2]+.38*p[1] for p in ps)/4
            points=[(240+(index%3)*480+2.9*(.55*x+.9*z),245+(index//3)*300+2.9*(.35*x-.12*z-.9*y)) for x,y,z in ps]
            faces.append((depth,points,[min(255,int(c*shade)) for c in base]))
    for _,points,col in sorted(faces,key=lambda f:f[0]): polygon(points,col)

def chunk(tag,data): return struct.pack('>I',len(data))+tag+data+struct.pack('>I',zlib.crc32(tag+data))
raw=b''.join(b'\0'+pixels[y*W*3:(y+1)*W*3] for y in range(H))
out=Path(__file__).parent/'models_preview.png'
out.write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',W,H,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(raw))+chunk(b'IEND',b''))
print(out)
