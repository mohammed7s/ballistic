#include "engine.h"
#include "log.h"
#include "json-schema-to-grammar.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <limits>
#include <vector>

namespace ballistic {
std::string schema_to_grammar(const std::string & json_schema) {
    try {
        // force_gbnf: this build has no llguidance; always produce a GBNF grammar.
        return json_schema_to_grammar(common_json::parse(json_schema), true);
    } catch (...) {
        throw Failure("Output schema is not supported by llama.cpp's grammar converter");
    }
}
using Clock = std::chrono::steady_clock;
static double ms(Clock::time_point a, Clock::time_point b) {
    return std::chrono::duration<double, std::milli>(b-a).count();
}
static bool abort_eval(void * p) { return static_cast<Engine *>(p)->cancelled.load(); }
static bool progress(float, void * p) { return !abort_eval(p); }
Engine::Engine() {
    static std::once_flag once;
    std::call_once(once, [] {
        // Upstream can print template examples and model paths. Never forward those to logcat.
        common_log_set_verbosity_thold(-1);
        common_log_set_stdo(common_log_main(), false);
        llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr);
        llama_backend_init();
    });
}
void Engine::check_cancel() const { if(cancelled.load()) throw Failure("Local operation stopped"); }
void Engine::unload() {
    templates.reset();
    context.reset();
    model.reset();
}
nlohmann::json Engine::load(const std::string & path, int context_size, int threads) {
    unload();
    if (context_size < 512 || context_size > 8192 || threads < 1 || threads > 8) throw Failure("Invalid local settings");
    check_cancel();
    auto mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    mp.progress_callback = progress;
    mp.progress_callback_user_data = this;
    try {
        model.reset(llama_model_load_from_file(path.c_str(), mp));
        check_cancel();
        if (!model) throw Failure("Cannot load GGUF: invalid, unsupported or insufficient memory");
        if (llama_model_has_encoder(model.get()) || !llama_model_has_decoder(model.get())) throw Failure("Only text decoder GGUF models are supported");
        const auto * tmpl = llama_model_chat_template(model.get(), nullptr);
        if (!tmpl || !*tmpl) throw Failure("GGUF has no chat template; import an instruct/chat model");
        templates = common_chat_templates_init(model.get(), "");
        auto cp = llama_context_default_params();
        cp.n_ctx = std::min(context_size, llama_model_n_ctx_train(model.get()));
        if (cp.n_ctx < 512) throw Failure("Model context is too small");
        cp.n_batch = 128;
        cp.n_ubatch = 128;
        cp.n_threads = threads;
        cp.n_threads_batch = threads;
        cp.n_seq_max = 1;
        cp.abort_callback = abort_eval;
        cp.abort_callback_data = this;
        cp.no_perf = false;
        context.reset(llama_init_from_model(model.get(), cp));
        check_cancel();
        if (!context) throw Failure("Cannot allocate model context; use a smaller model or context");
        char description[512] = {};
        llama_model_desc(model.get(), description, sizeof(description));
        char quant[64] = {};
        auto quant_len = llama_model_meta_val_str(model.get(), "general.file_type", quant, sizeof(quant));
        nlohmann::json result = {{"description",description},{"trainingContext",llama_model_n_ctx_train(model.get())},{"context",llama_n_ctx(context.get())}};
        if (quant_len > 0 && quant_len < int(sizeof(quant))) result["quantization"] = std::string("GGUF file type ") + quant;
        return result;
    } catch (...) { unload(); throw; }
}

nlohmann::json Engine::generate(const nlohmann::json & messages, int cap, const std::string & json_schema) {
    if (!model || !context || !templates) throw Failure("Local model is not loaded");
    if (cap <= 0 || cap > 1024 || !messages.is_array() || messages.empty() || messages.size() > 128) throw Failure("Invalid local completion request");
    if (json_schema.size() > 64*1024) throw Failure("Output schema too large");
    check_cancel();
    if (!json_schema.empty() && json_schema != schema_source) {
        schema_grammar = schema_to_grammar(json_schema);
        schema_source = json_schema;
    }
    // Fresh context on every role/call. Never retain another role's content in the KV cache.
    llama_memory_clear(llama_get_memory(context.get()), true);
    struct Clear { llama_context * ctx; ~Clear(){ llama_memory_clear(llama_get_memory(ctx), true); } } clear{context.get()};
    const auto start = Clock::now();
    common_chat_templates_inputs input;
    input.enable_thinking = false;
    input.tool_choice = COMMON_CHAT_TOOL_CHOICE_NONE;
    input.use_jinja = true;
    size_t bytes = 0;
    for (const auto & m : messages) {
        common_chat_msg msg;
        msg.role = m.at("role").get<std::string>();
        msg.content = m.at("content").get<std::string>();
        if (msg.role != "system" && msg.role != "user" && msg.role != "assistant") throw Failure("Unsupported message role");
        bytes += msg.content.size();
        if (bytes > 1024*1024) throw Failure("Prompt exceeds local input limit");
        input.messages.push_back(std::move(msg));
    }
    const auto * vocab = llama_model_get_vocab(model.get());
    input.add_bos = llama_vocab_get_add_bos(vocab);
    input.add_eos = llama_vocab_get_add_eos(vocab);
    common_chat_session chat(templates.get(), vocab, input);
    check_cancel();
    std::vector<llama_token> tokens;
    if (input.add_bos) tokens.push_back(llama_vocab_bos(vocab));
    for (const auto & part : chat.prompt().parts) {
        // Literal message content must not turn into model control tokens.
        auto chunk = common_tokenize(vocab, part.text, false, !part.is_input);
        tokens.insert(tokens.end(), chunk.begin(), chunk.end());
        if (tokens.size() + cap > llama_n_ctx(context.get())) throw Failure("Prompt and generation budget exceed local context; reduce input or change context");
    }
    if (input.add_eos) tokens.push_back(llama_vocab_eos(vocab));
    if (tokens.size() + cap > llama_n_ctx(context.get())) throw Failure("Prompt and generation budget exceed local context");
    if (tokens.empty()) throw Failure("Chat template produced no prompt tokens");
    std::unique_ptr<llama_batch_ext, decltype(&llama_batch_ext_free)> batch(llama_batch_ext_init(context.get()), llama_batch_ext_free);
    if (!batch) throw Failure("Cannot allocate inference batch");
    auto decode = [&](const llama_token * ids, int count, int position) {
        check_cancel();
        llama_batch_ext_clear(batch.get());
        for(int i=0; i<count; ++i) {
            auto index = llama_batch_ext_add_token(batch.get(), 0, ids[i]);
            llama_pos pos = position + i;
            if (index < 0 || !llama_batch_ext_set_pos(batch.get(), index, &pos)) throw Failure("Invalid inference batch");
        }
        llama_batch_ext_set_output_logits(batch.get(), count-1, true);
        int code = llama_process(context.get(), LLAMA_PROCESS_TYPE_DECODE, batch.get());
        check_cancel();
        if (code != 0) throw Failure("CPU inference failed; unload and retry with smaller settings");
    };
    const auto prompt_start = Clock::now();
    for (size_t offset=0; offset<tokens.size(); offset+=128) {
        const int count = std::min<size_t>(128, tokens.size()-offset);
        decode(tokens.data()+offset, count, offset);
    }
    const auto prompt_end = Clock::now();
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(), llama_sampler_free);
    if (!sampler) throw Failure("Cannot allocate sampler");
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> grammar(nullptr, llama_sampler_free);
    if (!json_schema.empty()) {
        grammar.reset(llama_sampler_init_grammar(vocab, schema_grammar.c_str(), "root"));
        if (!grammar) throw Failure("Output grammar could not be compiled");
    }
    std::vector<llama_token_data> candidates;
    // Greedy pick; under a grammar, the pick is checked first and only a rejected pick pays for
    // masking the whole vocabulary (the same rejection scheme as common_sampler_sample).
    auto next_token = [&]() -> llama_token {
        llama_token token = llama_sampler_sample(sampler.get(), context.get(), -1);
        if (!grammar) return token;
        llama_token_data single = {token, 1.0f, 0.0f};
        llama_token_data_array one = {&single, 1, -1, false};
        llama_sampler_apply(grammar.get(), &one);
        if (single.logit == -INFINITY) {
            const float * logits = llama_get_logits_ith(context.get(), -1);
            if (!logits) throw Failure("CPU inference failed; unload and retry with smaller settings");
            const int n_vocab = llama_vocab_n_tokens(vocab);
            candidates.resize(n_vocab);
            for (llama_token id = 0; id < n_vocab; ++id) candidates[id] = {id, logits[id], 0.0f};
            llama_token_data_array all = {candidates.data(), candidates.size(), -1, false};
            llama_sampler_apply(grammar.get(), &all);
            token = LLAMA_TOKEN_NULL;
            float best = -INFINITY;
            for (size_t k = 0; k < all.size; ++k) {
                if (all.data[k].logit > best) { best = all.data[k].logit; token = all.data[k].id; }
            }
            if (token == LLAMA_TOKEN_NULL) throw Failure("No output token fits the output schema");
        }
        llama_sampler_accept(grammar.get(), token);
        return token;
    };
    common_chat_input output;
    int generated = 0;
    bool capped = true;
    Clock::time_point first_token{};
    for (int i=0; i<cap; ++i) {
        check_cancel();
        const auto token = next_token();
        if (llama_vocab_is_eog(vocab, token)) { capped = false; break; }
        if (generated == 0) first_token = Clock::now();
        ++generated;
        output.append(common_token_to_piece(vocab, token, true), token);
        bool stopped = false;
        for(const auto & stop: chat.additional_stops()) {
            if (stop.empty()) continue;
            const auto pos = output.text.find(stop);
            if (pos != std::string::npos) { output.truncate(pos); stopped = true; break; }
        }
        if (stopped) { capped = false; break; }
        if (i+1 < cap) decode(&token, 1, tokens.size()+i);
    }
    const auto end = Clock::now();
    check_cancel();
    const auto & result = chat.finish(output);
    if (!result.tool_calls.empty() || !result.content_parts.empty()) throw Failure("Local model returned a non-text completion");
    nlohmann::json metrics = {
        {"promptTokens",tokens.size()}, {"generatedTokens",generated},
        {"promptMs",ms(prompt_start,prompt_end)},
        {"generationMs",generated > 0 ? ms(first_token,end) : 0},
        {"durationMs",ms(start,end)}, {"capped",capped}
    };
    if(generated > 0) metrics["firstTokenMs"] = ms(start,first_token);
    return {{"text",result.content},{"metrics",metrics}};
}
}
