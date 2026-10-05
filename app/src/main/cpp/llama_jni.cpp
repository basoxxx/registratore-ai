// Ponte JNI verso llama.cpp per il riassunto delle lezioni con un modello linguistico locale.
#include <jni.h>
#include <atomic>
#include <cstring>
#include <string>
#include <vector>
#include "llama.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LLOGE(...) __android_log_print(ANDROID_LOG_ERROR, "LlamaJNI", __VA_ARGS__)
#else
#include <cstdio>
#define LLOGE(...) (fprintf(stderr, "[LlamaJNI] "), fprintf(stderr, __VA_ARGS__), fprintf(stderr, "\n"))
#endif

static std::atomic<bool> g_llm_abort{false};
static bool llm_abort_cb(void *) { return g_llm_abort.load(); }

struct LlmHandle {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::string tmpl;
};

static std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

static std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text, bool special) {
    int n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, special, true);
    std::vector<llama_token> toks(n);
    if (llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), toks.data(), n, special, true) < 0) toks.clear();
    return toks;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_it_registratoreai_summary_LlamaLib_load(JNIEnv *env, jobject, jstring path, jint nCtx, jint threads) {
    static bool initialized = false;
    if (!initialized) {
        llama_backend_init();
        initialized = true;
    }
    llama_model_params mp = llama_model_default_params();
#if defined(__APPLE__) && !defined(__ANDROID__)
    mp.n_gpu_layers = 99;   // tutto sulla GPU Metal
#else
    mp.n_gpu_layers = 0;
#endif
    std::string p = jstr(env, path);
    llama_model *model = llama_model_load_from_file(p.c_str(), mp);
    if (!model) {
        LLOGE("Impossibile caricare il modello %s", p.c_str());
        return 0;
    }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) nCtx;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    cp.abort_callback = llm_abort_cb;
    cp.abort_callback_data = nullptr;
    cp.no_perf = true;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        LLOGE("Impossibile creare il contesto");
        llama_model_free(model);
        return 0;
    }
    auto *h = new LlmHandle();
    h->model = model;
    h->ctx = ctx;
    h->vocab = llama_model_get_vocab(model);
    const char *t = llama_model_chat_template(model, nullptr);
    h->tmpl = t ? t : "chatml";
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL
Java_it_registratoreai_summary_LlamaLib_free(JNIEnv *, jobject, jlong ptr) {
    auto *h = reinterpret_cast<LlmHandle *>(ptr);
    if (!h) return;
    llama_free(h->ctx);
    llama_model_free(h->model);
    delete h;
}

JNIEXPORT void JNICALL
Java_it_registratoreai_summary_LlamaLib_requestAbort(JNIEnv *, jobject, jboolean v) {
    g_llm_abort.store(v == JNI_TRUE);
}

JNIEXPORT jint JNICALL
Java_it_registratoreai_summary_LlamaLib_countTokens(JNIEnv *env, jobject, jlong ptr, jstring text) {
    auto *h = reinterpret_cast<LlmHandle *>(ptr);
    if (!h) return -1;
    return (jint) tokenize(h->vocab, jstr(env, text), false).size();
}

JNIEXPORT jint JNICALL
Java_it_registratoreai_summary_LlamaLib_contextSize(JNIEnv *, jobject, jlong ptr) {
    auto *h = reinterpret_cast<LlmHandle *>(ptr);
    return h ? (jint) llama_n_ctx(h->ctx) : 0;
}

/**
 * Risponde a un messaggio (system + user) usando il formato di chat del modello.
 * Restituisce il testo UTF-8 generato, oppure null in caso di errore o interruzione.
 */
JNIEXPORT jbyteArray JNICALL
Java_it_registratoreai_summary_LlamaLib_chat(JNIEnv *env, jobject, jlong ptr, jstring system, jstring user,
                                             jint maxTokens, jfloat temperature) {
    auto *h = reinterpret_cast<LlmHandle *>(ptr);
    if (!h) return nullptr;
    g_llm_abort.store(false);

    std::string sys = jstr(env, system), usr = jstr(env, user);
    std::vector<llama_chat_message> msgs;
    if (!sys.empty()) msgs.push_back({"system", sys.c_str()});
    msgs.push_back({"user", usr.c_str()});
    std::vector<char> buf((sys.size() + usr.size()) * 2 + 1024);
    int n = llama_chat_apply_template(h->tmpl.c_str(), msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
    if (n < 0) {
        // Formato del modello non riconosciuto: si usa chatml
        n = llama_chat_apply_template("chatml", msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
    }
    if (n > (int) buf.size()) {
        buf.resize(n + 1);
        n = llama_chat_apply_template(h->tmpl.c_str(), msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
    }
    if (n < 0) return nullptr;
    std::string prompt(buf.data(), n);

    std::vector<llama_token> toks = tokenize(h->vocab, prompt, true);
    const int nCtx = (int) llama_n_ctx(h->ctx);
    if (toks.empty() || (int) toks.size() + maxTokens > nCtx) {
        LLOGE("Prompt troppo lungo: %zu token (contesto %d)", toks.size(), nCtx);
        return nullptr;
    }

    llama_memory_clear(llama_get_memory(h->ctx), true);
    const int nBatch = (int) llama_n_batch(h->ctx);
    for (size_t i = 0; i < toks.size(); i += nBatch) {
        int len = (int) std::min((size_t) nBatch, toks.size() - i);
        if (llama_decode(h->ctx, llama_batch_get_one(toks.data() + i, len)) != 0) return nullptr;
    }

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(64, 1.1f, 0.0f, 0.0f));
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(42));
    }

    std::string out;
    char piece[256];
    bool ok = true;
    for (int i = 0; i < maxTokens; ++i) {
        if (g_llm_abort.load()) { ok = false; break; }
        llama_token tok = llama_sampler_sample(smpl, h->ctx, -1);
        if (llama_vocab_is_eog(h->vocab, tok)) break;
        int len = llama_token_to_piece(h->vocab, tok, piece, sizeof(piece), 0, false);
        if (len > 0) out.append(piece, len);
        if (llama_decode(h->ctx, llama_batch_get_one(&tok, 1)) != 0) { ok = false; break; }
    }
    llama_sampler_free(smpl);
    if (!ok) return nullptr;

    jbyteArray arr = env->NewByteArray((jsize) out.size());
    if (!out.empty()) env->SetByteArrayRegion(arr, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return arr;
}

} // extern "C"
