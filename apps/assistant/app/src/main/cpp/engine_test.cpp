#include "engine.h"
#include <cassert>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <sstream>
// Usage: ballistic_native_test [model.gguf]. BALLISTIC_ACTIONS_SCHEMA=core/schema/actions.json adds
// the constrained-output checks (grammar conversion; with a model, a schema-valid JSON reply).
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
    failed = false;
    try { ballistic::schema_to_grammar("{not json"); } catch (const ballistic::Failure &) { failed = true; }
    assert(failed);
    std::string schema;
    if (const char * path = std::getenv("BALLISTIC_ACTIONS_SCHEMA")) {
        std::ifstream in(path);
        std::stringstream text; text << in.rdbuf();
        schema = text.str();
        assert(!schema.empty());
        const auto grammar = ballistic::schema_to_grammar(schema);
        assert(grammar.find("root ::=") != std::string::npos);
        std::cout << "actions schema -> GBNF: " << grammar.size() << " bytes\n";
    }
    if(argc == 2) {
        engine.load(argv[1],1024,2);
        auto result = engine.generate({{{"role","system"},{"content","Be brief."}},{{"role","user"},{"content","Say hello."}}},32);
        std::cout << result["metrics"].dump() << '\n'; // Never log completion content.
        if (!schema.empty()) {
            auto constrained = engine.generate({
                {{"role","system"},{"content","You control Android. Return exactly one JSON action."}},
                {{"role","user"},{"content","Open the Clock app."}}}, 128, schema);
            const auto reply = nlohmann::json::parse(constrained["text"].get<std::string>());
            assert(reply.is_object() && reply.contains("action") && reply["action"].is_string());
            // Test-only output: an action name from the fixed schema, not owner content.
            std::cout << "constrained action=" << reply["action"].get<std::string>() << ' ' << constrained["metrics"].dump() << '\n';
        }
        engine.unload();
    }
    std::cout << "native lifecycle/error smoke passed\n";
}
