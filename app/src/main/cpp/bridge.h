// bridge.h —— 抽象出与 llama.cpp 版本相关的细节
#pragma once
#include <string>

struct bridge_sampler_t {
    float temperature;
    int   top_k;
    float top_p;
    float repeat_penalty;
};

void                bridge_set_sampler(const bridge_sampler_t& s);
struct llama_sampler* bridge_build_sampler();
int                 bridge_read_thermal_percent();
