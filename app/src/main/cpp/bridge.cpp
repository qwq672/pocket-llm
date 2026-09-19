// bridge.cpp
#include "bridge.h"
#include "llama.h"
#include <mutex>
#include <fstream>
#include <string>

// 用 mutex 替代 std::atomic<bridge_sampler_t>，避免 16 字节原子操作在某些平台
// （如 32 位 ARM、部分 NDK 工具链）需要链接 __atomic_store_16 的可移植性问题。
static std::mutex g_sampler_mtx;
static bridge_sampler_t g_sampler{ 0.8f, 40, 0.95f, 1.1f };

void bridge_set_sampler(const bridge_sampler_t& s) {
    std::lock_guard<std::mutex> lk(g_sampler_mtx);
    g_sampler = s;
}

llama_sampler* bridge_build_sampler() {
    bridge_sampler_t s;
    {
        std::lock_guard<std::mutex> lk(g_sampler_mtx);
        s = g_sampler;
    }
    auto chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(chain, llama_sampler_init_penalties(64, 1, s.repeat_penalty, 0.0f));
    llama_sampler_chain_add(chain, llama_sampler_init_top_k(s.top_k));
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(s.top_p, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp (s.temperature));
    llama_sampler_chain_add(chain, llama_sampler_init_dist (0));
    return chain;
}

// 从 sysfs 读 CPU 温度，转 0-100
int bridge_read_thermal_percent() {
    static const char* zones[] = {
        "/sys/class/thermal/thermal_zone0/temp",
        "/sys/class/thermal/thermal_zone1/temp",
    };
    for (const char* z : zones) {
        std::ifstream f(z);
        if (f) {
            long milli = 0; f >> milli;
            double c = milli / 1000.0;
            return (int)((c - 35.0) * 100.0 / 25.0);
        }
    }
    return 0;
}
