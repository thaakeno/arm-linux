#pragma once
#include <array>

namespace bounce {
struct Vec3 { float x{}, y{}, z{}; };
struct Aabb { Vec3 c; Vec3 h; int material; bool hazard=false; bool bounce=false; };

inline constexpr std::array<Aabb,14> kPlatforms{{
    {{ 0.0f,-0.42f,  2.0f},{5.8f,.42f,3.6f},0,false,false},
    {{ 0.7f, 0.10f, -3.0f},{4.0f,.40f,1.8f},0,false,false},
    {{-2.8f, 0.55f, -6.2f},{2.0f,.42f,1.6f},1,false,false},
    {{ 1.8f, 0.72f, -6.7f},{2.6f,.42f,1.7f},0,false,false},
    {{ 4.1f, 1.02f, -9.5f},{1.7f,.40f,1.5f},1,false,false},
    {{ 0.8f, 1.25f, -9.7f},{2.1f,.35f,1.1f},0,false,false},
    {{-2.0f, 1.55f,-11.7f},{1.6f,.34f,1.0f},0,false,false},
    {{ 1.4f, 1.85f,-13.5f},{1.7f,.34f,1.0f},0,false,false},
    {{ 4.1f, 2.10f,-15.5f},{1.6f,.34f,1.0f},1,false,false},
    {{ 1.7f, 2.35f,-17.2f},{1.8f,.34f,.90f},0,false,false},
    {{-1.0f, 2.62f,-19.0f},{1.7f,.34f,.90f},0,false,false},
    {{-3.2f, 2.90f,-21.0f},{1.6f,.34f,1.0f},1,false,false},
    {{-0.5f, 3.15f,-22.9f},{2.0f,.34f,1.0f},0,false,false},
    {{ 1.0f, 3.50f,-25.8f},{4.2f,.42f,2.1f},0,false,false}
}};

inline constexpr std::array<Vec3,10> kCoins{{
    {-2.4f,.70f,1.1f},{2.2f,.70f,.1f},{.7f,1.05f,-3.0f},{-2.8f,1.45f,-6.2f},{1.8f,1.60f,-6.7f},
    {4.1f,1.90f,-9.5f},{.8f,2.10f,-9.7f},{1.4f,2.80f,-13.5f},{1.7f,3.25f,-17.2f},{1.0f,4.35f,-25.8f}
}};

inline constexpr Vec3 kSpawn{-1.2f,.72f,3.0f};
inline constexpr Vec3 kBouncePad{2.15f,.08f,1.05f};
inline constexpr Vec3 kGoal{1.0f,4.85f,-27.0f};
}
