#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
p = ROOT / "app/src/main/cpp/shaders/native_studio_v7_rt.frag"
text = p.read_text()

def rep(old: str, new: str, count: int = 1) -> None:
    global text
    if old not in text:
        raise SystemExit(f"RT marker missing: {old[:140]!r}")
    text = text.replace(old, new, count)

rep("// Vulkan Studio v10.1: deterministic hybrid lighting, sparse reconstructed path quality, real HDRI windows and texture-driven fire.",
    "// Vessel Playground v1.0: deterministic hybrid lighting for an open interactive arena.")
rep("m.base*=mix(1.0,.42,w);m.roughness=mix(m.roughness,.055,w*.94);m.clearcoat=max(m.clearcoat,w*.96);",
    "m.base*=mix(1.0,.68,w);m.roughness=mix(m.roughness,.18,w*.72);m.clearcoat=max(m.clearcoat,w*.46);")
rep("a.roughness=mix(dampR,.070,water);a.clearcoat=water*.62;",
    "a.roughness=mix(dampR,.16,water);a.clearcoat=water*.38;")
rep("a.base=texture(darkAlbedo,t).rgb*vec3(.20,.21,.24);a.roughness=clamp(arm.g*.82+.03,.26,.82);",
    "a.base=texture(darkAlbedo,t).rgb*vec3(.31,.32,.34);a.roughness=clamp(arm.g*.92+.08,.34,.90);")

new_box = '''void boxInfo(int id,out vec3 c,out vec3 s,out int mat){
    int b=id-2;mat=0;c=vec3(0);s=vec3(1);
    if(b==0){c=vec3(-7.5,-.72,-4.5);s=vec3(2.8,.29,2.7);}
    else if(b==1){c=vec3(7.3,-.58,-4.7);s=vec3(3.1,.43,2.5);mat=12;}
    else if(b==2){c=vec3(0,-.42,8.0);s=vec3(4.2,.59,2.0);}
    else if(b==3){c=vec3(-9.2,-.88,2.8);s=vec3(1.7,.12,1.25);}
    else if(b==4){c=vec3(-9.2,-.66,4.0);s=vec3(1.7,.21,1.25);}
    else if(b==5){c=vec3(-9.2,-.39,5.2);s=vec3(1.7,.27,1.25);}
    else if(b==6){c=vec3(-9.2,-.05,6.4);s=vec3(1.7,.34,1.25);}
    else if(b==7){c=vec3(-9.2,.36,7.6);s=vec3(1.7,.41,1.25);}
    else if(b==8){c=vec3(9.4,-.64,5.6);s=vec3(1.25,.37,1.25);mat=12;}
    else if(b==9){c=vec3(9.4,.10,5.6);s=vec3(.95,.37,.95);mat=12;}
    else if(b==10){c=vec3(5.4,-.72,2.2);s=vec3(1.2,.29,1.2);}
    else if(b==11){c=vec3(5.4,-.08,2.2);s=vec3(.85,.35,.85);mat=12;}
    else if(b==12){c=vec3(-4.2,-.78,-9.0);s=vec3(3.0,.22,1.2);}
    else if(b==13){c=vec3(-4.2,-.34,-9.0);s=vec3(2.2,.22,1.0);}
    else if(b==14){c=vec3(-4.2,.08,-9.0);s=vec3(1.35,.20,.75);mat=12;}
    else if(b==15){c=vec3(4.0,-.64,-10.5);s=vec3(4.0,.37,1.65);}
    else if(b==16){c=vec3(11.1,-.61,-1.8);s=vec3(2.2,.40,2.2);mat=12;}
    else{c=vec3(-11.2,-.59,-2.6);s=vec3(2.5,.42,2.0);}
}'''
start = text.find("void boxInfo(int id,out vec3 c,out vec3 s,out int mat)")
end = text.find("\nvec3 boxNormal", start)
if start < 0 or end < 0:
    raise SystemExit("boxInfo boundaries missing")
text = text[:start] + new_box + text[end:]

# Match RT floor hit UV scale to the 28 m visible floor.
text = text.replace("hm=1;huv=(hp.xz/14.1+.5)*3.20;", "hm=1;huv=(hp.xz/28.0+.5)*7.0;")

if "void main()" not in text:
    raise SystemExit("RT shader main entry point missing after patch")
p.write_text(text)
print("Playground RT shader repaired with main() preserved")
