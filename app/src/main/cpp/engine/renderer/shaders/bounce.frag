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

void applyConcrete(inout vec3 N,vec2 uv,out vec3 al,out float rough){
    vec2 t=fract(uv*.42);
    al=pow(texture(texConcrete,t).rgb,vec3(2.2));
    vec3 nn=texture(texNormal,t).xyz*2.0-1.0;
    vec3 T=normalize(abs(N.y)>.72?vec3(1,0,0):vec3(0,1,0)),B=normalize(cross(N,T));T=normalize(cross(B,N));
    N=normalize(T*nn.x*.78+B*nn.y*.78+N*max(.34,nn.z));
    rough=clamp(texture(texArm,t).g*.70+.18,.25,.88);
}

void main(){
    vec3 N=normalize(vNormal),V=normalize(vView);
    if(vMat==13){outColor=vec4(pow(film(env(normalize(-V))*1.12),vec3(1.0/2.2)),1.0);return;}

    vec3 base=vec3(.5),emission=vec3(0);float rough=.48,metal=0.0,coat=0.0;
    vec3 texAl;float texR;

    if(vMat==0||vMat==1||vMat==2||vMat==11){
        applyConcrete(N,vUv,texAl,texR);rough=texR;
        if(vMat==0){base=texAl*vec3(.88,.91,.94);}
        else if(vMat==1){base=texAl*vec3(.72,.085,.055);rough*=.82;coat=.11;}
        else if(vMat==2){base=texAl*vec3(.28,.31,.34);rough*=.72;metal=.10;}
        else {base=texAl*vec3(.58,.60,.58);rough=min(.88,texR+.10);}
    }
    else if(vMat==3){float stripe=step(.5,fract((vUv.x+vUv.y)*5.0));base=mix(vec3(.028,.033,.038),vec3(.93,.48,.025),stripe);rough=.30;metal=.66;}
    else if(vMat==4){base=vec3(.045,.052,.060);rough=.21;metal=.58;coat=.12;}
    else if(vMat==5){float g=.76+.24*hash21(floor(vUv*21.0));base=vec3(.29,.28,.25)*g;rough=.79;}
    else if(vMat==6){base=vec3(.82,.016,.010);rough=.17;metal=.48;coat=.18;}
    else if(vMat==7){base=vec3(1.0,.54,.012);rough=.07;metal=.05;coat=.86;emission=vec3(8.5,4.2,.18)*(1.0+.10*sin(pc.motion.y*4.0));}
    else if(vMat==8){base=vec3(.47,.012,.006);rough=.10;metal=.30;coat=.58;emission=vec3(8.0,.28,.018)*(1.0+.13*sin(pc.motion.y*5.0));}
    else if(vMat==9){base=vec3(.028,.12,.75);rough=.05;coat=.95;emission=vec3(.08,2.2,11.0)*(1.0+.17*sin(pc.motion.y*3.7));}
    else if(vMat==10){
        float pores=.91+.09*hash21(floor(vUv*620.0));
        base=vec3(.58,.0045,.0030)*pores;rough=.145;metal=.0;coat=.98;
    }
    else if(vMat==12){float grain=.68+.32*hash21(floor(vUv*43.0));base=vec3(.19,.17,.14)*grain;rough=.84;}
    else if(vMat==14){float leaf=.72+.28*hash21(floor(vUv*63.0));base=vec3(.018,.24,.040)*leaf;rough=.56;coat=.06;}
    else if(vMat==15){
        float grain=.72+.18*sin(vUv.y*52.0+sin(vUv.x*7.0)*2.0)+.10*hash21(floor(vUv*37.0));
        base=vec3(.22,.095,.036)*grain;rough=.56;metal=.04;
    }
    else if(vMat==16){base=vec3(.63,.025,.018);rough=.48;coat=.04;}
    else if(vMat==17){base=vec3(.025,.15,.46);rough=.46;coat=.06;}
    else if(vMat==18){
        float seams=step(.055,abs(fract(vUv.y*7.0)-.5));float grain=.72+.20*sin(vUv.x*42.0)+.08*hash21(floor(vUv*40.0));
        base=mix(vec3(.055,.025,.011),vec3(.30,.13,.052)*grain,seams);rough=.60;
    }
    else if(vMat==19){base=vec3(1.0,.47,.015);rough=.31;coat=.10;}

    vec3 L=normalize(vec3(-.48,.78,.39)),H=normalize(L+V);
    float nl=max(dot(N,L),0.0),nv=max(dot(N,V),.001),nh=max(dot(N,H),0.0),vh=max(dot(V,H),0.0);
    float k=(rough+1.0)*(rough+1.0)/8.0;vec3 f0=mix(vec3(.038),base,metal),F=fresnel(vh,f0);
    vec3 spec=Dggx(nh,rough)*G1(nv,k)*G1(nl,k)*F/max(4.0*nl*nv,.001);
    vec3 diff=(1.0-F)*(1.0-metal)*base/PI;
    vec3 R=reflect(-V,N);
    vec3 color=(diff+spec)*vec3(1.0,.93,.82)*5.9*nl;
    color+=env(N)*base*(1.0-metal)*.24+env(R)*fresnel(nv,f0)*mix(.72,.16,rough)+emission;

    // Contact darkening around the hero ball grounds it without a fake circular shadow decal.
    if((vMat==0||vMat==1||vMat==2)&&N.y>.25){
        float radial=length(vWorld.xz-pc.ball.xz),vertical=max(0.0,pc.ball.y-pc.ball.w-vWorld.y);
        float contact=(1.0-smoothstep(pc.ball.w*.18,pc.ball.w*1.70+vertical*.75,radial))*exp(-vertical*3.3);
        color*=1.0-.50*contact;
    }

    // v13-inspired gummy response: deep red body, strong clear coat, rim transmission/backscatter.
    if(vMat==10){
        float rim=pow(1.0-nv,2.0),back=max(dot(-N,L),0.0);
        vec3 through=env(-R)*vec3(.46,.004,.002);
        color+=vec3(.24,.0023,.0015)*(.34+.66*max(N.y,0.0));
        color+=rim*vec3(1.75,.075,.040);
        color+=vec3(.92,.020,.007)*pow(back,1.65)*.55;
        color+=through*(.18+.42*rim);
        color+=env(R)*fresnel(nv,vec3(.032))*1.22;
    }
    if(coat>0.0)color+=env(R)*fresnel(nv,vec3(.04))*coat*.44;

    float d=length(vWorld-pc.ball.xyz),fog=sat((d-31.0)/62.0);
    color=mix(color,env(normalize(-V)),fog*.24);
    outColor=vec4(pow(film(color*1.04),vec3(1.0/2.2)),1.0);
}
