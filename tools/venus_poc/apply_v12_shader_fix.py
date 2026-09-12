#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
p = ROOT / "app/src/main/cpp/shaders/native_studio_v7_rt.frag"
text = p.read_text()

material = r'''Material materialAt(int m,vec3 P,vec2 uv,out float water){
    Material a;a.base=vec3(.5);a.metallic=0;a.roughness=.5;a.clearcoat=0;a.ao=1;a.emission=vec3(0);water=0;
    if(m==0){vec2 t=fract(uv*1.28);vec4 arm=texture(concreteArm,t);a.base=texture(concreteAlbedo,t).rgb;a.roughness=clamp(arm.g,.40,.92);a.ao=arm.r;}
    else if(m==1){vec2 t=fract(uv*.46);vec4 arm=texture(concreteArm,t);vec3 tex=texture(concreteAlbedo,t).rgb;float micro=.94+.06*noise2(t*32.0);a.base=mix(vec3(.34,.35,.36),tex*vec3(.62,.63,.64),.28)*micro;a.roughness=clamp(.72+arm.g*.16,.68,.92);a.clearcoat=0.0;a.ao=.96;water=0.0;}
    else if(m==2){float grain=hash21(floor(uv*260.0)),scratch=smoothstep(.975,.998,hash21(floor(uv*vec2(42.0,480.0))));a.base=mix(vec3(.095,.105,.115),vec3(.34,.36,.39),grain*.34);a.base=mix(a.base,vec3(.50,.44,.35),scratch*.42);a.metallic=1.0;a.roughness=clamp(.15+grain*.20,.13,.39);a.clearcoat=.035;}
    else if(m==3){float pores=hash21(floor(uv*230.0)),scuff=smoothstep(.86,.99,hash21(floor(uv*35.0+9.0)));a.base=mix(vec3(.17,.003,.003),vec3(.72,.012,.008),pores*.35);a.base=mix(a.base,vec3(.09,.014,.011),scuff*.24);a.roughness=clamp(.34+pores*.12-scuff*.07,.28,.52);a.clearcoat=.30;a.ao=.98;}
    else if(m==4){float raw=max(max(bodies[2].extra.y,bodies[2].extra.z),bodies[2].extra.w),intensity=max(raw,.25);vec3 col=bodies[2].extra.yzw/intensity;a.base=col;a.roughness=.09;a.clearcoat=.72;a.emission=col*(7.4*intensity);}
    else if(m==5){vec2 t=fract(P.xy*.16+P.zy*.13);vec4 arm=texture(concreteArm,t);a.base=texture(concreteAlbedo,t).rgb*vec3(.66,.65,.63);a.roughness=clamp(arm.g+.15,.62,.96);a.ao=.94;}
    else if(m==6){vec2 t=fract(uv*1.12);vec4 arm=texture(darkArm,t);vec3 tex=texture(darkAlbedo,t).rgb;int variant=int(mod(pc.heroMaterial,10.0)+.5);if(variant==1){a.base=tex*vec3(.17,.18,.21);a.roughness=clamp(.38+arm.g*.24,.34,.66);a.clearcoat=.08;}else if(variant==2){a.base=tex*vec3(.34,.33,.31);a.roughness=clamp(.58+arm.g*.22,.54,.82);a.clearcoat=.02;}else{a.base=mix(tex*vec3(.44,.42,.40),vec3(.38,.34,.31),.28);a.roughness=clamp(.48+arm.g*.24,.44,.76);a.clearcoat=.03;}a.ao=arm.r;}
    else if(m==7){float grain=hash21(floor(uv*220.0)),scuff=smoothstep(.88,.995,hash21(floor(uv*31.0+4.0)));a.base=mix(vec3(.012,.016,.019),vec3(.045,.060,.072),grain*.34);a.base*=1.0-scuff*.12;a.roughness=clamp(.62+grain*.14-scuff*.10,.52,.84);a.clearcoat=.025;a.ao=.95;}
    else if(m==10){float n=hash21(floor(P.xz*15.0+P.yy*7.0));a.base=mix(vec3(.035,.10,.04),vec3(.10,.25,.075),n);a.roughness=.76;a.ao=.84;}
    else if(m==12){float n=.94+.06*noise2(P.xz*.85+P.yy*.27);a.base=vec3(.39,.40,.42)*n;a.roughness=.83;a.metallic=0.0;a.ao=.98;}
    else if(m==14){float grain=.45+.35*noise2(vec2(P.x+P.z,P.y)*9.0);a.base=mix(vec3(.055,.020,.009),vec3(.19,.070,.026),grain);a.roughness=.66;}
    else if(m==15||m==22){a.base=vec3(.12,.055,.018);a.roughness=.75;a.emission=vec3(0);}
    else if(m==16){a.base=vec3(.18,.21,.23);a.roughness=.18;a.clearcoat=.55;a.ao=1.0;}
    else if(m==17){float weave=.5+.5*sin(uv.x*180.0)*sin(uv.y*155.0);a.base=mix(vec3(.05),vec3(.12),weave*.18);a.roughness=.92;a.ao=.90;}
    else if(m==18){a.base=vec3(.13,.11,.09);a.roughness=.96;a.ao=.88;}
    else if(m==20){a.base=vec3(.08,.085,.09);a.metallic=.86;a.roughness=.42;a.ao=.94;}
    return a;
}'''

pattern = r"Material materialAt\(int m,vec3 P,vec2 uv,out float water\)\{.*?\n\}"
text, n = re.subn(pattern, material, text, count=1, flags=re.S)
if n != 1:
    raise SystemExit(f"materialAt replacement failed: {n}")

# Make sure a previous partial regex replacement cannot leave a duplicated branch
# between materialAt and materialNormal.
start = text.index("Material materialAt")
normal = text.index("vec3 materialNormal", start)
chunk = text[start:normal]
if chunk.count("return a;") != 1:
    raise SystemExit("materialAt still malformed")

p.write_text(text)
print("v12 shader material chain repaired")
