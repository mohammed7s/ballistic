#include "engine.h"
#include <jni.h>

// Stable process lifetime: cancel never dereferences a freed context/engine. Other entry points
// serialize even if a future caller violates Kotlin's ownership lock.
static ballistic::Engine & engine() { static ballistic::Engine value; return value; }
static std::string bytes(JNIEnv * env, jbyteArray value) {
    if (!value) throw ballistic::Failure("Missing native argument");
    auto n = env->GetArrayLength(value);
    if(n > 2*1024*1024) throw ballistic::Failure("Native input too large");
    std::string s(n, '\0');
    env->GetByteArrayRegion(value, 0, n, reinterpret_cast<jbyte *>(s.data()));
    return s;
}
template<class F> static jbyteArray invoke(JNIEnv * env, F f) {
    std::string result;
    try {
        std::lock_guard<std::mutex> lock(engine().ownership);
        result = f().dump(-1, ' ', false, nlohmann::json::error_handler_t::replace);
    } catch (const ballistic::Failure & e) {
        result = nlohmann::json({{"error",e.what()}}).dump();
    } catch (const std::bad_alloc &) {
        result = "{\"error\":\"Insufficient memory for local model\"}";
    } catch (...) {
        result = "{\"error\":\"Invalid model, unsupported chat template or inference failure\"}";
    }
    auto output = env->NewByteArray(result.size());
    if (output) env->SetByteArrayRegion(output, 0, result.size(), reinterpret_cast<const jbyte *>(result.data()));
    return output;
}
extern "C" {
JNIEXPORT jbyteArray JNICALL Java_app_ballistic_assistant_models_LlamaNative_load(JNIEnv * e, jobject, jbyteArray path, jint context, jint threads) {
    return invoke(e,[&]{ return engine().load(bytes(e,path),context,threads); });
}
// schema: UTF-8 JSON Schema text constraining the output, or null for unconstrained greedy.
JNIEXPORT jbyteArray JNICALL Java_app_ballistic_assistant_models_LlamaNative_generate(JNIEnv * e, jobject, jbyteArray messages, jint cap, jbyteArray schema) {
    return invoke(e,[&]{
        return engine().generate(nlohmann::json::parse(bytes(e,messages)),cap, schema ? bytes(e,schema) : std::string());
    });
}
JNIEXPORT void JNICALL Java_app_ballistic_assistant_models_LlamaNative_unload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(engine().ownership); engine().unload();
}
JNIEXPORT void JNICALL Java_app_ballistic_assistant_models_LlamaNative_cancel(JNIEnv *, jobject) { engine().cancelled.store(true); }
JNIEXPORT void JNICALL Java_app_ballistic_assistant_models_LlamaNative_resetCancellation(JNIEnv *, jobject) { engine().cancelled.store(false); }
}
