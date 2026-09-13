#pragma once
#include <array>

namespace bounce {
struct Vec3 { float x{}, y{}, z{}; };
struct Aabb { Vec3 c; Vec3 h; int material; bool hazard=false; bool bounce=false; };

// Compact connected course matching the showcase framing: broad start deck,
// central climb, narrow bridge, hazard lane, elevated finish platform.
inline constexpr std::array<Aabb, 14> kPlatforms{{
    {{ 0.0f,-0.45f,  1.8f},{5.8f,.45f,3.5f},0,false,false},
    {{ 0.8f, 0.05f, -2.7f},{1.9f,.35f,1.7f},0,false,false},
    {{ 0.6f, 0.55f, -5.2f},{3.6f,.42f,1.6f},0,false,false},
    {{-3.5f, 0.78f, -6.8f},{1.7f,.40f,1.4f},1,false,false},
    {{ 3.5f, 0.92f, -7.2f},{1.8f,.40f,1.4f},1,false,false},
    {{ 0.0f, 1.18f, -8.9f},{3.0f,.34f,1.1f},0,false,false},
    {{-1.9f, 1.48f,-10.9f},{1.4f,.34f,1.0f},0,false,false},
    {{ 1.8f, 1.72f,-12.6f},{1.5f,.34f,1.0f},0,false,false},
    {{ 4.2f, 1.98f,-14.6f},{1.45f,.34f,1.0f},1,false,false},
    {{ 1.8f, 2.22f,-16.4f},{1.8f,.34f,.85f},0,false,false},
    {{-1.0f, 2.48f,-18.1f},{1.7f,.34f,.85f},0,false,false},
    {{-3.3f, 2.72f,-20.0f},{1.55f,.34f,.95f},1,false,false},
    {{-0.5f, 3.02f,-21.9f},{2.0f,.34f,1.0f},0,false,false},
    {{ 1.2f, 3.34f,-24.5f},{4.0f,.40f,1.8f},0,false,false}
}};

inline constexpr std::array<Vec3,10> kCoins{{
    {-2.4f,.62f,1.1f},{2.2f,.62f,.1f},{.8f,1.0f,-2.8f},{.6f,1.45f,-5.2f},{-3.4f,1.65f,-6.8f},
    {3.5f,1.78f,-7.2f},{0.0f,2.0f,-8.9f},{1.8f,2.58f,-12.6f},{1.8f,3.05f,-16.4f},{1.2f,4.02f,-24.4f}
}};

inline constexpr Vec3 kSpawn{-1.2f,.72f,3.0f};
inline constexpr Vec3 kBouncePad{2.15f,.08f,1.05f};
inline constexpr Vec3 kGoal{1.2f,4.45f,-25.5f};
}
