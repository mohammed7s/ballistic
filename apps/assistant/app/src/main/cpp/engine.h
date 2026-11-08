#pragma once
#include "llama.h"
#include "chat.h"
#include <nlohmann/json.hpp>
#include <atomic>
#include <memory>
#include <stdexcept>
#include <mutex>

namespace ballistic {
// Safe code-only errors; upstream exception messages may contain prompt/template/model data.
struct Failure : std::runtime_error { using std::runtime_error::runtime_error; };
struct Engine {
    std::mutex ownership;
    std::atomic<bool> cancelled{false};
    std::unique_ptr<llama_model, decltype(&llama_model_free)> model{nullptr, llama_model_free};
    std::unique_ptr<llama_context, decltype(&llama_free)> context{nullptr, llama_free};
    common_chat_templates_ptr templates;
    Engine();
    void unload();
    nlohmann::json load(const std::string & path, int context_size, int threads);
    /**
     * One completion. A non-empty [json_schema] constrains the output to that JSON Schema with
     * llama.cpp's grammar sampler (GBNF from common/json-schema-to-grammar); greedy otherwise.
     */
    nlohmann::json generate(const nlohmann::json & messages, int cap, const std::string & json_schema = "");
    void check_cancel() const;
    // Last schema compiled to GBNF; conversion is deterministic, so one cached pair suffices.
    std::string schema_source, schema_grammar;
};
/** JSON Schema text → GBNF (root rule "root"). Throws Failure for an unsupported schema. */
std::string schema_to_grammar(const std::string & json_schema);
}
