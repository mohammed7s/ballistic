#include "engine.h"
#include <cassert>
#include <fstream>
#include <iostream>
int main(int argc, char ** argv) {
    ballistic::Engine engine;
    engine.unload(); engine.unload();
    bool failed = false;
    try { engine.generate(nlohmann::json::array(),32); } catch (const ballistic::Failure &) { failed = true; }
    assert(failed);
    engine.cancelled.store(true);
    failed = false;
    try { engine.load("missing.gguf",1024,2); } catch (const ballistic::Failure &) { failed = true; }
    assert(failed && !engine.model && !engine.context);
    engine.cancelled.store(false);
    failed = false;
    try { engine.load("missing.gguf",1024,2); } catch (const ballistic::Failure &) { failed = true; }
    assert(failed && !engine.model && !engine.context);
    std::ofstream("corrupt.gguf",std::ios::binary) << "GGUF\3\0\0\0invalid";
    failed = false;
    try { engine.load("corrupt.gguf",1024,2); } catch (const ballistic::Failure &) { failed = true; }
    std::remove("corrupt.gguf");
    assert(failed && !engine.model && !engine.context);
    if(argc == 2) {
        engine.load(argv[1],1024,2);
        auto result = engine.generate({{{"role","system"},{"content","Be brief."}},{{"role","user"},{"content","Say hello."}}},32);
        std::cout << result["metrics"].dump() << '\n'; // Never log completion content.
        engine.unload();
    }
    std::cout << "native lifecycle/error smoke passed\n";
}
