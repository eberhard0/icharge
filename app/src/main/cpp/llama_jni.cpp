// JNI bridge between LlamaNative.kt and llama.cpp. One model, one context, one
// sequence. The tokens already in the KV cache are remembered so a follow-up
// question only has to process what changed since the last turn (the chat grows
// at the end, so the trip sheet + earlier turns are never recomputed).
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <algorithm>
#include <thread>
#include "llama.h"

#define TAG "icharge_llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    int n_batch = 512;
    std::vector<llama_token> cached;   // what the KV cache currently holds, in order
    volatile bool stop = false;
};

std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return "";
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    env->ReleaseStringUTFChars(s, c);
    return out;
}

void log_cb(ggml_log_level level, const char * text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
}

// Decode tokens [from, to) of `toks` into the KV cache in n_batch chunks.
bool feed(Engine * e, const std::vector<llama_token> & toks, size_t from, size_t to) {
    for (size_t i = from; i < to; i += e->n_batch) {
        if (e->stop) return false;
        size_t n = std::min((size_t) e->n_batch, to - i);
        llama_batch b = llama_batch_get_one(const_cast<llama_token *>(toks.data() + i), (int32_t) n);
        int rc = llama_decode(e->ctx, b);
        if (rc != 0) { LOGE("llama_decode failed (%d) at %zu", rc, i); return false; }
    }
    return true;
}


// Length of the longest prefix of s that ends on a complete UTF-8 character.
size_t utf8_complete_prefix(const std::string & s) {
    if (s.empty()) return 0;
    size_t i = s.size();
    // find the last lead byte within the final 4 bytes
    size_t lead = s.size();
    for (size_t k = 0; k < 4 && k < s.size(); k++) {
        unsigned char b = s[s.size() - 1 - k];
        if ((b & 0xC0) != 0x80) { lead = s.size() - 1 - k; break; }
    }
    if (lead == s.size()) return i;                     // no lead found: pass it through
    unsigned char l = s[lead];
    size_t need = (l & 0x80) == 0 ? 1 : (l & 0xE0) == 0xC0 ? 2 : (l & 0xF0) == 0xE0 ? 3 : (l & 0xF8) == 0xF0 ? 4 : 1;
    return (s.size() - lead >= need) ? s.size() : lead;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_iameberhard_icharge_ai_LlamaNative_init(JNIEnv * env, jobject, jstring path, jint nCtx, jint nThreads) {
    llama_log_set(log_cb, nullptr);
    llama_backend_init();
    auto * e = new Engine();
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    std::string p = jstr(env, path);
    e->model = llama_model_load_from_file(p.c_str(), mp);
    if (!e->model) { LOGE("model load failed: %s", p.c_str()); delete e; return 0; }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) nCtx;
    cp.n_batch = (uint32_t) e->n_batch;
    cp.n_ubatch = (uint32_t) e->n_batch;
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreads;
    cp.no_perf = true;
    e->ctx = llama_init_from_model(e->model, cp);
    if (!e->ctx) { LOGE("context init failed"); llama_model_free(e->model); delete e; return 0; }
    e->vocab = llama_model_get_vocab(e->model);
    LOGI("loaded %s, n_ctx=%u threads=%d", p.c_str(), llama_n_ctx(e->ctx), nThreads);
    return (jlong) e;
}

JNIEXPORT void JNICALL
Java_com_iameberhard_icharge_ai_LlamaNative_free(JNIEnv *, jobject, jlong h) {
    auto * e = (Engine *) h;
    if (!e) return;
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}

JNIEXPORT void JNICALL
Java_com_iameberhard_icharge_ai_LlamaNative_stop(JNIEnv *, jobject, jlong h) {
    auto * e = (Engine *) h;
    if (e) e->stop = true;
}

JNIEXPORT jint JNICALL
Java_com_iameberhard_icharge_ai_LlamaNative_countTokens(JNIEnv * env, jobject, jlong h, jstring text) {
    auto * e = (Engine *) h;
    if (!e) return -1;
    std::string t = jstr(env, text);
    int n = -llama_tokenize(e->vocab, t.c_str(), (int32_t) t.size(), nullptr, 0, true, true);
    return n;
}

// Streams generated text through listener.onToken(ByteArray): Boolean (false = stop), as UTF-8 bytes.
// Returns the number of tokens generated, or a negative error code.
JNIEXPORT jint JNICALL
Java_com_iameberhard_icharge_ai_LlamaNative_generate(JNIEnv * env, jobject, jlong h, jstring prompt, jint maxTokens,
                                                     jfloat temperature, jobject listener) {
    auto * e = (Engine *) h;
    if (!e) return -1;
    e->stop = false;
    jclass cls = env->GetObjectClass(listener);
    jmethodID onToken = env->GetMethodID(cls, "onToken", "([B)Z");
    if (!onToken) return -2;

    std::string text = jstr(env, prompt);
    std::vector<llama_token> toks(text.size() + 16);
    int n = llama_tokenize(e->vocab, text.c_str(), (int32_t) text.size(), toks.data(), (int32_t) toks.size(), true, true);
    if (n < 0) { toks.resize(-n); n = llama_tokenize(e->vocab, text.c_str(), (int32_t) text.size(), toks.data(), (int32_t) toks.size(), true, true); }
    if (n <= 0) return -3;
    toks.resize(n);

    const uint32_t n_ctx = llama_n_ctx(e->ctx);
    if ((uint32_t) n + 16 > n_ctx) { LOGE("prompt too long: %d tokens for n_ctx %u", n, n_ctx); return -4; }

    // Reuse the cached prefix; always re-decode at least the last prompt token so the logits are fresh.
    size_t keep = 0;
    while (keep < e->cached.size() && keep < toks.size() && e->cached[keep] == toks[keep]) keep++;
    if (keep == toks.size()) keep = toks.size() - 1;
    llama_memory_t mem = llama_get_memory(e->ctx);
    if (keep == 0) llama_memory_clear(mem, true);
    else llama_memory_seq_rm(mem, 0, (llama_pos) keep, -1);
    e->cached.assign(toks.begin(), toks.begin() + keep);
    LOGI("prompt %d tokens, %zu cached, %zu to process", n, keep, toks.size() - keep);
    if (!feed(e, toks, keep, toks.size())) { e->cached.clear(); llama_memory_clear(mem, true); return e->stop ? 0 : -5; }
    e->cached = toks;

    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    llama_sampler * smpl = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    int produced = 0;
    std::string pending;            // bytes of a UTF-8 sequence not yet complete
    char buf[256];
    while (produced < maxTokens && !e->stop) {
        if (e->cached.size() + 1 >= n_ctx) break;
        llama_token t = llama_sampler_sample(smpl, e->ctx, -1);
        llama_sampler_accept(smpl, t);
        if (llama_vocab_is_eog(e->vocab, t)) break;
        int len = llama_token_to_piece(e->vocab, t, buf, sizeof(buf), 0, false);
        if (len > 0) {
            pending.append(buf, len);
            // Only hand complete UTF-8 to Java: NewStringUTF rejects partial sequences.
            size_t cut = utf8_complete_prefix(pending);
            if (cut > 0) {
                // Raw bytes: NewStringUTF speaks modified UTF-8 and mangles 4-byte characters (emoji).
                jbyteArray arr = env->NewByteArray((jsize) cut);
                env->SetByteArrayRegion(arr, 0, (jsize) cut, (const jbyte *) pending.data());
                jboolean go = env->CallBooleanMethod(listener, onToken, arr);
                env->DeleteLocalRef(arr);
                pending.erase(0, cut);
                if (!go || env->ExceptionCheck()) { env->ExceptionClear(); break; }
            }
        }
        llama_batch b = llama_batch_get_one(&t, 1);
        if (llama_decode(e->ctx, b) != 0) { LOGE("decode failed during generation"); break; }
        e->cached.push_back(t);
        produced++;
    }
    llama_sampler_free(smpl);
    return produced;
}

JNIEXPORT jstring JNICALL
Java_com_iameberhard_icharge_ai_LlamaNative_systemInfo(JNIEnv * env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

} // extern "C"
