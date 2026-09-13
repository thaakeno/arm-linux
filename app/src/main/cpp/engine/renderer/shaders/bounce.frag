#version 460
layout(location=0) in vec3 vWorld;
layout(location=1) in vec3 vNormal;
layout(location=2) in vec2 vUv;
layout(location=3) flat in int vMat;
layout(location=4) in vec3 vView;
layout(location=5) flat in int vObject;
layout(location=0) out vec4 outColor;
layout(set=0,binding=0) uniform sampler2D texConcrete;
layout(set=0,binding=1) uniform sampler2D texNormal;
layout(set=0,binding=2) uniform sampler2D texArm;
layout(set=0,binding=3) uniform sampler2D texEnvironment;
layout(push_constant) uniform Push {vec4 ball;vec4 motion;vec4 misc;} pc;
const float PI=3.141592653589793;
float sat(float x){return clamp(x,0.0,1.0);} 
float hash21(vec2 p){p=fract(p*vec2(123.34,345.45));p+=dot(p,p+34.345);return fract(p.x*p.y);} 
vec3 fresnel(float c,vec3 f0){return f0+(1.0-f0)*pow(1.0-c,5.0);} 
float Dggx(float nh,float r){float a=max(.025,r*r),a2=a*a,d=nh*nh*(a2-1.0)+1.0;return a2/max(PI*d*d,1e-5);} 
float G1(float nv,float k){return nv/max(nv*(1.0-k)+k,1e-4);} 
vec3 film(vec3 x){x=max(x,vec3(0));return clamp((x*(2.51*x+.03))/(x*(2.43*x+.59)+.14),0.0,1.0);} 
vec2 envUv(vec3 d){d=normalize(d);return vec2(atan(d.z,d.x)/(2.0*PI)+.5,acos(clamp(d.y,-1.0,1.0))/PI);} 
vec3 env(vec3 d){return pow(texture(texEnvironment,envUv(d)).rgb,vec3(2.2));}
void main(){
 vec3 N=normalize(vNormal),V=normalize(vView);
 if(vMat==13){outColor=vec4(pow(film(env(normalize(-V))*1.10),vec3(1.0/2.2)),1.0);return;}
 vec3 base=vec3(.5);float rough=.48,metal=0.0,coat=0.0;vec3 emission=vec3(0);
 if(vMat==0||vMat==1){vec2 uv=fract(vUv*.46);vec3 al=pow(texture(texConcrete,uv).rgb,vec3(2.2));vec3 nn=texture(texNormal,uv).xyz*2.0-1.0;vec3 T=normalize(abs(N.y)>.72?vec3(1,0,0):vec3(0,1,0)),B=normalize(cross(N,T));T=normalize(cross(B,N));N=normalize(T*nn.x*.82+B*nn.y*.82+N*max(.30,nn.z));rough=clamp(texture(texArm,uv).g*.72+.16,.24,.86);base=al*(vMat==1?vec3(.82,.12,.085):vec3(.82,.86,.91));if(vMat==1){rough=.38;coat=.10;}}
 else if(vMat==2){base=vec3(.11,.12,.13);rough=.28;metal=.52;coat=.08;}
 else if(vMat==3){float stripe=step(.5,fract((vUv.x+vUv.y)*5.0));base=mix(vec3(.035,.04,.045),vec3(.96,.48,.025),stripe);rough=.31;metal=.72;}
 else if(vMat==4){base=vec3(.055,.06,.068);rough=.22;metal=.62;coat=.14;}
 else if(vMat==5){base=vec3(.26,.25,.23);rough=.72;}
 else if(vMat==6){base=vec3(.79,.018,.012);rough=.18;metal=.42;coat=.12;}
 else if(vMat==7){base=vec3(1.0,.55,.018);rough=.08;coat=.80;emission=vec3(8.0,3.7,.16)*(1.0+.10*sin(pc.motion.y*4.0));}
 else if(vMat==8){base=vec3(.52,.018,.008);rough=.12;metal=.35;coat=.50;emission=vec3(7.0,.32,.025)*(1.0+.12*sin(pc.motion.y*5.0));}
 else if(vMat==9){base=vec3(.035,.16,.82);rough=.065;coat=.92;emission=vec3(.09,2.1,10.0)*(1.0+.15*sin(pc.motion.y*3.7));}
 else if(vMat==10){float pores=.90+.10*hash21(floor(vUv*520.0));base=vec3(.62,.0048,.0032)*pores;rough=.155;coat=.88;}
 else if(vMat==11){float dirt=.78+.22*hash21(floor(vUv*29.0));base=vec3(.34,.35,.34)*dirt;rough=.68;metal=.05;}
 else if(vMat==12){float grain=.72+.28*hash21(floor(vUv*37.0));base=vec3(.20,.18,.145)*grain;rough=.80;}
 else if(vMat==14){float leaf=.78+.22*hash21(floor(vUv*41.0));base=vec3(.025,.25,.045)*leaf;rough=.58;coat=.08;}
 vec3 L=normalize(vec3(-.48,.78,.39)),H=normalize(L+V);float nl=max(dot(N,L),0.0),nv=max(dot(N,V),.001),nh=max(dot(N,H),0.0),vh=max(dot(V,H),0.0);float k=(rough+1.0)*(rough+1.0)/8.0;vec3 f0=mix(vec3(.038),base,metal),F=fresnel(vh,f0);vec3 spec=Dggx(nh,rough)*G1(nv,k)*G1(nl,k)*F/max(4.0*nl*nv,.001),diff=(1.0-F)*(1.0-metal)*base/PI;vec3 R=reflect(-V,N);vec3 color=(diff+spec)*vec3(1.0,.91,.76)*5.8*nl+env(N)*base*(1.0-metal)*.23+env(R)*fresnel(nv,f0)*mix(.68,.18,rough)+emission;
 if((vMat==0||vMat==1)&&N.y>.25){float radial=length(vWorld.xz-pc.ball.xz),vertical=max(0.0,pc.ball.y-pc.ball.w-vWorld.y),contact=(1.0-smoothstep(pc.ball.w*.20,pc.ball.w*1.65+vertical*.7,radial))*exp(-vertical*3.0);color*=1.0-.47*contact;}
 if(vMat==10){float rim=pow(1.0-nv,2.1),back=max(dot(-N,L),0.0);color+=vec3(.30,.0025,.0015)*(.38+.62*max(N.y,0.0))+rim*vec3(1.60,.062,.035)+vec3(.88,.018,.006)*pow(back,1.8)*.48+env(R)*fresnel(nv,vec3(.035))*1.15;}
 if(coat>0.0)color+=env(R)*fresnel(nv,vec3(.04))*coat*.42;
 float d=length(vWorld-pc.ball.xyz),fog=sat((d-28.0)/58.0);color=mix(color,env(normalize(-V)),fog*.32);outColor=vec4(pow(film(color*1.05),vec3(1.0/2.2)),1.0);
}
