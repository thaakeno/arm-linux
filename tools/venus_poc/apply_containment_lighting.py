#!/usr/bin/env python3
from pathlib import Path
import re
ROOT=Path(__file__).resolve().parents[2]
p=ROOT/'app/src/main/cpp/shaders/native_studio_v7_rt.frag'
s=p.read_text()
# The benchmark's movable light orb is no longer rendered in Blacksite. Prevent its
# hidden body state from contributing ghost light/haze to the facility.
s=s.replace('vec3 orbHaze(vec3 P){vec3 d=P-bodies[2].posRad.xyz;float r=max(bodies[2].posRad.w,.255),dist=length(d);float intensity=max(max(bodies[2].extra.y,bodies[2].extra.z),bodies[2].extra.w);vec3 col=bodies[2].extra.yzw/max(intensity,.25);float haze=clamp((r-.255)/.075,0.0,1.0);float g=exp(-dist*(1.8+1.6*(1.0-haze)))*haze*intensity;return col*g*.14;}', 'vec3 orbHaze(vec3 P){return vec3(0.0);}')
old=r'vec3 stablePathIndirect\(vec3 P,vec3 N,vec3 V,Material first,int source,vec2 uv\)\{.*?\}\nvec3 orbHaze'
new='''vec3 stablePathIndirect(vec3 P,vec3 N,vec3 V,Material first,int source,vec2 uv){
    // Deterministic two-bounce low-SPP integrator. Stable directions avoid the
    // shimmering that made the old "Path Quality" mode look worse than hybrid.
    vec3 rd=stableBounceDir(first,N,V,uv,source),hp,hn;int hm;vec2 huv;
    vec3 F0=mix(vec3(.04),first.base,first.metallic),F=fresnelSchlick(max(dot(N,V),0.0),F0);
    vec3 throughput=mix(first.base*(1.0-first.metallic),F,clamp(first.metallic+.18*(1.0-first.roughness),0.0,1.0));
    int hid=traceScene(P+N*rayEps(source),rd,source,hp,hn,hm,huv);
    if(hid<0)return min(throughput*envSample(rd,4.0)*.72,vec3(2.2));
    float w;Material hit=materialAt(hm,hp,huv,w);hn=normalize(hn);if(dot(hn,-rd)<0)hn=-hn;applyHeroWet(hit,hid,hm,hp,huv,hn);
    if(length(hit.emission)>0)return min(throughput*hit.emission*.28,vec3(2.2));
    vec3 firstBounce=ibl(hit,hn,-rd)*.44+fireLighting(hit,hn,-rd,hp)*.22;
    vec3 rd2=stableBounceDir(hit,hn,-rd,huv,hid),hp2,hn2;int hm2;vec2 uv2;
    int hid2=traceScene(hp+hn*rayEps(hid),rd2,hid,hp2,hn2,hm2,uv2);
    vec3 secondBounce;
    if(hid2<0)secondBounce=envSample(rd2,4.6)*.38;
    else{float w2;Material h2=materialAt(hm2,hp2,uv2,w2);hn2=normalize(hn2);if(dot(hn2,-rd2)<0)hn2=-hn2;secondBounce=h2.emission*.20+ibl(h2,hn2,-rd2)*.20+fireLighting(h2,hn2,-rd2,hp2)*.07;}
    vec3 hitF=fresnelSchlick(max(dot(hn,-rd),0.0),mix(vec3(.04),hit.base,hit.metallic));
    vec3 hitThrough=mix(hit.base*(1.0-hit.metallic),hitF,clamp(hit.metallic+.12*(1.0-hit.roughness),0.0,1.0));
    return min(throughput*(firstBounce+hitThrough*secondBounce),vec3(2.2));
}
vec3 orbHaze'''
s,n=re.subn(old,new,s,count=1,flags=re.S)
if n!=1: raise SystemExit('path integrator marker mismatch')
# Zero out legacy invisible body-light contribution in main; authored lab fixtures now
# provide all local direct lighting.
old='vec3 lightRaw=max(bodies[2].extra.yzw,vec3(.01));float lightIntensity=max(max(lightRaw.r,lightRaw.g),lightRaw.b);vec3 lightRgb=lightRaw/max(lightIntensity,.25);'
new='vec3 lightRaw=vec3(.01);float lightIntensity=0.0;vec3 lightRgb=vec3(1.0);'
if old not in s: raise SystemExit('legacy light marker missing')
s=s.replace(old,new,1)
# Raise path contribution now that it is a real two-bounce deterministic integral.
s=s.replace('vec3 color=direct+indirect*.22+gelTransmission(mat,m,N,V)*.28+haze;', 'vec3 color=direct+indirect*.34+gelTransmission(mat,m,N,V)*.20+haze;',1)
p.write_text(s)
print('Blacksite two-bounce path integrator installed')
