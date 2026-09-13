#pragma once
#include <array>

namespace bounce {
struct Vec3 { float x{}, y{}, z{}; };
struct Aabb { Vec3 c; Vec3 h; int material; bool hazard=false; bool bounce=false; };

inline constexpr std::array<Aabb, 14> kPlatforms{{
    {{ 0.0f,-0.55f,  2.0f},{4.9f,.55f,3.6f},0,false,false},
    {{ 0.0f,-0.20f, -3.2f},{3.4f,.42f,1.7f},0,false,false},
    {{-3.2f, 0.05f, -6.2f},{1.7f,.45f,1.7f},1,false,false},
    {{ 1.2f, 0.35f, -7.3f},{2.6f,.40f,1.3f},0,false,false},
    {{ 4.4f, 0.65f, -9.2f},{1.6f,.38f,1.3f},1,false,false},
    {{ 1.4f, 0.95f,-11.1f},{1.7f,.36f,1.0f},0,false,false},
    {{-1.4f, 1.35f,-12.9f},{1.5f,.34f,1.0f},0,false,false},
    {{-4.0f, 1.65f,-14.6f},{1.5f,.34f,1.0f},1,false,false},
    {{-1.0f, 2.00f,-16.4f},{2.2f,.34f,1.0f},0,false,false},
    {{ 2.7f, 2.35f,-18.2f},{1.8f,.34f,1.0f},0,false,false},
    {{ 5.2f, 2.65f,-20.4f},{1.8f,.34f,1.2f},1,false,false},
    {{ 2.1f, 2.95f,-22.6f},{2.5f,.34f,1.1f},0,false,false},
    {{-1.9f, 3.25f,-24.6f},{2.2f,.34f,1.0f},0,false,false},
    {{ 0.0f, 3.55f,-27.2f},{4.1f,.40f,1.8f},0,false,false}
}};

inline constexpr std::array<Vec3,10> kCoins{{
    {-2.3f,.65f,1.0f},{1.8f,.65f,-.1f},{-.3f,.95f,-3.4f},{-3.3f,1.15f,-6.0f},{1.2f,1.45f,-7.3f},
    {4.4f,1.75f,-9.1f},{1.4f,2.00f,-11.0f},{-3.9f,2.75f,-14.6f},{2.6f,3.45f,-18.2f},{0.0f,4.25f,-27.0f}
}};

inline constexpr Vec3 kSpawn{0.0f,.85f,3.5f};
inline constexpr Vec3 kBouncePad{2.15f,.18f,1.15f};
inline constexpr Vec3 kGoal{0.0f,4.55f,-28.7f};
}
