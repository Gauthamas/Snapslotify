// LLM timing spike (Build 2 step 0): a thin JNI wrapper over the llama.cpp C API. Debug builds only.
#include <jni.h>
#include <android/log.h>

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <string>
#include <vector>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "SnapslotifyLlm"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

// Set by abort(): llama_decode stops between graph nodes, and generation stops between tokens.
std::atomic<bool> g_abort{false};

bool abort_requested(void *) { return g_abort.load(); }

double now_ms() {
    using namespace std::chrono;
    return duration<double, std::milli>(steady_clock::now().time_since_epoch()).count();
}

std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

std::string json_escape(const std::string &s) {
    std::string o;
    for (unsigned char c : s) {
        switch (c) {
            case '"': o += "\\\""; break;
            case '\\': o += "\\\\"; break;
            case '\n': o += "\\n"; break;
            case '\r': o += "\\r"; break;
            case '\t': o += "\\t"; break;
            default:
                if (c < 0x20) { char b[8]; snprintf(b, sizeof b, "\\u%04x", c); o += b; } else o += (char) c;
        }
    }
    return o;
}

// The model's own chat template if llama.cpp knows it; otherwise ChatML (recorded as "chatml-fallback").
std::string apply_template(const llama_model *model, const std::string &system, const std::string &user, std::string &used) {
    llama_chat_message msgs[2] = {{"system", system.c_str()}, {"user", user.c_str()}};
    const char *tmpl = llama_model_chat_template(model, nullptr);
    std::vector<char> buf(system.size() + user.size() + 4096);
    int n = tmpl ? llama_chat_apply_template(tmpl, msgs, 2, true, buf.data(), (int) buf.size()) : -1;
    if (n > (int) buf.size()) {
        buf.resize(n + 1);
        n = llama_chat_apply_template(tmpl, msgs, 2, true, buf.data(), (int) buf.size());
    }
    if (n >= 0) {
        used = "model";
        return std::string(buf.data(), n);
    }
    used = "chatml-fallback";
    return "<|im_start|>system\n" + system + "<|im_end|>\n<|im_start|>user\n" + user + "<|im_end|>\n<|im_start|>assistant\n";
}

// A decoded prompt prefix for generateUser: its tokens and the saved state of sequence 0 after decoding them.
struct Prefix {
    std::vector<llama_token> toks;
    std::vector<uint8_t> state;
};
std::vector<Prefix> g_prefixes(8);

// One user message through the template, as the training rows and the laptop's llama-server render it.
std::string render_user(const llama_model *model, const std::string &user) {
    llama_chat_message msg{"user", user.c_str()};
    const char *tmpl = llama_model_chat_template(model, nullptr);
    std::vector<char> buf(user.size() + 256);
    int n = tmpl ? llama_chat_apply_template(tmpl, &msg, 1, true, buf.data(), (int) buf.size()) : -1;
    if (n > (int) buf.size()) {
        buf.resize(n + 1);
        n = llama_chat_apply_template(tmpl, &msg, 1, true, buf.data(), (int) buf.size());
    }
    return n >= 0 ? std::string(buf.data(), n) : std::string();
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text) {
    int n = -llama_tokenize(vocab, text.c_str(), (int) text.size(), nullptr, 0, true, true);
    std::vector<llama_token> toks(n);
    llama_tokenize(vocab, text.c_str(), (int) text.size(), toks.data(), n, true, true);
    return toks;
}

int decode_range(llama_context *ctx, std::vector<llama_token> &toks, int from, int to) {
    const int n_batch = (int) llama_n_batch(ctx);
    int rc = 0;
    for (int i = from; i < to && rc == 0; i += n_batch) {
        rc = llama_decode(ctx, llama_batch_get_one(toks.data() + i, std::min(n_batch, to - i)));
    }
    return rc;
}

long major_faults() {
    rusage r{};
    getrusage(RUSAGE_SELF, &r);
    return r.ru_majflt;
}

long blocks_read() {
    rusage r{};
    getrusage(RUSAGE_SELF, &r);
    return r.ru_inblock;
}

double resident_percent(const std::string &path) {
    int fd = open(path.c_str(), O_RDONLY);
    if (fd < 0) return -1;
    struct stat st{};
    fstat(fd, &st);
    void *m = mmap(nullptr, st.st_size, PROT_READ, MAP_SHARED, fd, 0);
    double out = -1;
    if (m != MAP_FAILED) {
        const long page = sysconf(_SC_PAGESIZE);
        std::vector<unsigned char> v((st.st_size + page - 1) / page);
        if (mincore(m, st.st_size, v.data()) == 0) {
            size_t in = 0;
            for (unsigned char c : v) in += c & 1;
            out = 100.0 * in / v.size();
        }
        munmap(m, st.st_size);
    }
    close(fd);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL Java_in_gauthama_snapslotify_llmspike_LlamaNative_abort(JNIEnv *, jobject) {
    g_abort = true;
}

JNIEXPORT void JNICALL Java_in_gauthama_snapslotify_llmspike_LlamaNative_clearAbort(JNIEnv *, jobject) {
    g_abort = false;
}

JNIEXPORT void JNICALL Java_in_gauthama_snapslotify_llmspike_LlamaNative_clearPrefixes(JNIEnv *, jobject) {
    for (auto &p : g_prefixes) p = Prefix{};
}

JNIEXPORT jlong JNICALL Java_in_gauthama_snapslotify_llmspike_LlamaNative_majorFaults(JNIEnv *, jobject) {
    return major_faults();
}

JNIEXPORT jlong JNICALL Java_in_gauthama_snapslotify_llmspike_LlamaNative_blocksRead(JNIEnv *, jobject) {
    return blocks_read();
}

JNIEXPORT jdouble JNICALL Java_in_gauthama_snapslotify_llmspike_LlamaNative_residentPercent(JNIEnv *env, jobject, jstring path) {
    return resident_percent(jstr(env, path));
}

JNIEXPORT jdouble JNICALL Java_in_gauthama_snapslotify_llmspike_LlamaNative_evictFromCache(JNIEnv *env, jobject, jstring jpath) {
    const std::string path = jstr(env, jpath);
    int fd = open(path.c_str(), O_RDONLY);
    if (fd < 0) return -1;
    fsync(fd);
    posix_fadvise(fd, 0, 0, POSIX_FADV_DONTNEED);
    close(fd);
    return resident_percent(path);
}

JNIEXPORT jlong JNICALL Java_in_gauthama_snapslotify_llmspike_LlamaNative_readThrough(JNIEnv *env, jobject, jstring jpath) {
    int fd = open(jstr(env, jpath).c_str(), O_RDONLY);
    if (fd < 0) return -1;
    std::vector<char> buf(1 << 20);
    double t0 = now_ms();
    while (read(fd, buf.data(), buf.size()) > 0) {}
    close(fd);
    return (jlong) (now_ms() - t0);
}

JNIEXPORT jstring JNICALL Java_in_gauthama_snapslotify_llmspike_LlamaNative_buildPrefix(
        JNIEnv *env, jobject, jlong jctx, jlong jmodel, jint slot, jstring jprefix) {
    auto *ctx = reinterpret_cast<llama_context *>(jctx);
    auto *model = reinterpret_cast<llama_model *>(jmodel);
    const std::string prefix_text = jstr(env, jprefix);
    if (slot < 0 || slot >= (int) g_prefixes.size()) return env->NewStringUTF("{\"error\":\"bad slot\"}");
    const std::string rendered = render_user(model, prefix_text + "x");
    const size_t at = rendered.find(prefix_text);
    if (rendered.empty() || at == std::string::npos) return env->NewStringUTF("{\"error\":\"prefix not rendered\"}");
    std::vector<llama_token> toks = tokenize(llama_model_get_vocab(model), rendered.substr(0, at + prefix_text.size()));
    // The last token can merge with what follows in a real prompt, so it is not kept.
    if (toks.size() < 2) return env->NewStringUTF("{\"error\":\"prefix too short\"}");
    toks.pop_back();
    llama_memory_clear(llama_get_memory(ctx), true);
    const long f0 = major_faults(), b0 = blocks_read();
    double t0 = now_ms();
    if (decode_range(ctx, toks, 0, (int) toks.size()) != 0) return env->NewStringUTF("{\"error\":\"prefix decode failed\"}");
    double prefix_ms = now_ms() - t0;
    Prefix &p = g_prefixes[slot];
    p.toks = toks;
    p.state.resize(llama_state_seq_get_size(ctx, 0));
    size_t got = llama_state_seq_get_data(ctx, p.state.data(), p.state.size(), 0);
    if (got == 0) {
        p = Prefix{};
        return env->NewStringUTF("{\"error\":\"prefix state save failed\"}");
    }
    p.state.resize(got);
    std::string out = "{\"n_prefix\":" + std::to_string(toks.size()) + ",\"prefix_ms\":" + std::to_string(prefix_ms) +
                      ",\"state_bytes\":" + std::to_string(got) + ",\"majflt\":" + std::to_string(major_faults() - f0) +
                      ",\"inblock\":" + std::to_string(blocks_read() - b0) + "}";
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL Java_in_gauthama_snapslotify_llmspike_LlamaNative_generateUser(
        JNIEnv *env, jobject, jlong jctx, jlong jmodel, jint slot, jstring jprefix, jstring juser, jint max_tokens) {
    auto *ctx = reinterpret_cast<llama_context *>(jctx);
    auto *model = reinterpret_cast<llama_model *>(jmodel);
    const llama_vocab *vocab = llama_model_get_vocab(model);
    const std::string user = jstr(env, juser), prefix_text = jstr(env, jprefix);

    double t0 = now_ms();
    const std::string prompt = render_user(model, user);
    if (prompt.empty()) return env->NewStringUTF("{\"error\":\"template failed\"}");
    std::vector<llama_token> toks = tokenize(vocab, prompt);
    const int n = (int) toks.size();

    int n_prefix = 0;
    bool hit = false;
    double prefix_ms = 0, restore_ms = 0;
    const long f0 = major_faults(), b0 = blocks_read();
    size_t state_bytes = 0;
    llama_memory_clear(llama_get_memory(ctx), true);
    if (slot >= 0) {
        if (slot >= (int) g_prefixes.size()) return env->NewStringUTF("{\"error\":\"bad slot\"}");
        Prefix &p = g_prefixes[slot];
        if (p.toks.empty()) {
            const size_t at = prompt.find(prefix_text);
            if (at == std::string::npos) return env->NewStringUTF("{\"error\":\"prefix not in prompt\"}");
            std::vector<llama_token> pre = tokenize(vocab, prompt.substr(0, at + prefix_text.size()));
            int common = 0;
            while (common < (int) pre.size() && common < n - 1 && pre[common] == toks[common]) common++;
            double tb = now_ms();
            if (common == 0 || decode_range(ctx, toks, 0, common) != 0) return env->NewStringUTF("{\"error\":\"prefix decode failed\"}");
            prefix_ms = now_ms() - tb;
            p.toks.assign(toks.begin(), toks.begin() + common);
            p.state.resize(llama_state_seq_get_size(ctx, 0));
            size_t got = llama_state_seq_get_data(ctx, p.state.data(), p.state.size(), 0);
            if (got == 0) {
                p = Prefix{};
                return env->NewStringUTF("{\"error\":\"prefix state save failed\"}");
            }
            p.state.resize(got);
            state_bytes = got;
            n_prefix = common;
        } else {
            const int m = (int) p.toks.size();
            if (m >= n || !std::equal(p.toks.begin(), p.toks.end(), toks.begin())) return env->NewStringUTF("{\"error\":\"prompt does not start with the cached prefix\"}");
            double tb = now_ms();
            if (llama_state_seq_set_data(ctx, p.state.data(), p.state.size(), 0) == 0) return env->NewStringUTF("{\"error\":\"prefix restore failed\"}");
            restore_ms = now_ms() - tb;
            n_prefix = m;
            hit = true;
        }
    }

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    const long f1 = major_faults(), b1 = blocks_read();
    double tp = now_ms();
    if (decode_range(ctx, toks, n_prefix, n) != 0) {
        llama_sampler_free(smpl);
        return env->NewStringUTF("{\"error\":\"prefill decode failed\"}");
    }
    double prefill_ms = now_ms() - tp;
    const long f2 = major_faults(), b2 = blocks_read();

    std::string text, stop = "max_tokens", tok_ms;
    int n_out = 0;
    double ttft_ms = 0, t_first = 0;
    for (; n_out < max_tokens; ) {
        if (g_abort.load()) { stop = "decode_error"; break; }
        llama_token tok = llama_sampler_sample(smpl, ctx, -1);
        if (n_out == 0) { t_first = now_ms(); ttft_ms = t_first - t0; }
        if (llama_vocab_is_eog(vocab, tok)) { stop = "eog"; break; }
        char piece[256];
        int np = llama_token_to_piece(vocab, tok, piece, sizeof piece, 0, false);
        if (np > 0) text.append(piece, np);
        n_out++;
        double td = now_ms();
        if (llama_decode(ctx, llama_batch_get_one(&tok, 1)) != 0) { stop = "decode_error"; break; }
        tok_ms += (tok_ms.empty() ? "" : ",") + std::to_string((long) (now_ms() - td));
    }
    double t_end = now_ms();
    llama_sampler_free(smpl);

    std::string out = "{\"majflt_prefix\":" + std::to_string(f1 - f0) + ",\"inblock_prefix\":" + std::to_string(b1 - b0) +
                      ",\"majflt_tail\":" + std::to_string(f2 - f1) + ",\"inblock_tail\":" + std::to_string(b2 - b1) +
                      ",\"majflt_decode\":" + std::to_string(major_faults() - f2) + ",\"inblock_decode\":" + std::to_string(blocks_read() - b2) +
                      ",\"state_bytes\":" + std::to_string(state_bytes) + ",\"tok_ms\":[" + tok_ms + "]" +
                      ",\"n_prompt\":" + std::to_string(n) + ",\"n_prefix\":" + std::to_string(n_prefix) +
                      ",\"n_tail\":" + std::to_string(n - n_prefix) + ",\"cache_hit\":" + (hit ? "true" : "false") +
                      ",\"prefix_ms\":" + std::to_string(prefix_ms) + ",\"restore_ms\":" + std::to_string(restore_ms) +
                      ",\"prefill_ms\":" + std::to_string(prefill_ms) + ",\"ttft_ms\":" + std::to_string(ttft_ms) +
                      ",\"n_out\":" + std::to_string(n_out) + ",\"decode_ms\":" + std::to_string(n_out > 0 ? t_end - t_first : 0) +
                      ",\"total_ms\":" + std::to_string(t_end - t0) + ",\"stop\":\"" + stop + "\",\"text\":\"" +
                      json_escape(text) + "\"}";
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL Java_in_gauthama_snapslotify_llmspike_LlamaNative_backendInit(JNIEnv *, jobject) {
    llama_backend_init();
}

JNIEXPORT jstring JNICALL Java_in_gauthama_snapslotify_llmspike_LlamaNative_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT jlong JNICALL Java_in_gauthama_snapslotify_llmspike_LlamaNative_loadModel(JNIEnv *env, jobject, jstring path) {
    llama_model_params p = llama_model_default_params();
    p.load_mode = LLAMA_LOAD_MODE_MMAP;   // memory-mapped, as llama.cpp apps (PocketPal) do by default
    llama_model *m = llama_model_load_from_file(jstr(env, path).c_str(), p);
    if (!m) LOGW("model load failed");
    return reinterpret_cast<jlong>(m);
}

JNIEXPORT jlong JNICALL Java_in_gauthama_snapslotify_llmspike_LlamaNative_newContext(
        JNIEnv *, jobject, jlong model, jint n_ctx, jint n_batch, jint n_threads) {
    llama_context_params p = llama_context_default_params();
    p.n_ctx = n_ctx;
    p.n_batch = n_batch;
    p.n_ubatch = n_batch;
    p.n_threads = n_threads;
    p.n_threads_batch = n_threads;
    llama_context *c = llama_init_from_model(reinterpret_cast<llama_model *>(model), p);
    if (!c) LOGW("context init failed");
    else llama_set_abort_callback(c, abort_requested, nullptr);
    return reinterpret_cast<jlong>(c);
}

JNIEXPORT jstring JNICALL Java_in_gauthama_snapslotify_llmspike_LlamaNative_generate(
        JNIEnv *env, jobject, jlong jctx, jlong jmodel, jstring jsystem, jstring juser, jstring jgrammar, jint max_tokens) {
    auto *ctx = reinterpret_cast<llama_context *>(jctx);
    auto *model = reinterpret_cast<llama_model *>(jmodel);
    const llama_vocab *vocab = llama_model_get_vocab(model);
    const std::string grammar = jstr(env, jgrammar);

    double t0 = now_ms();
    llama_memory_clear(llama_get_memory(ctx), true);

    std::string tmpl_used;
    const std::string prompt = apply_template(model, jstr(env, jsystem), jstr(env, juser), tmpl_used);
    int n = -llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> toks(n);
    llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(), toks.data(), n, true, true);

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!grammar.empty()) {
        llama_sampler *g = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
        if (!g) {
            llama_sampler_free(smpl);
            return env->NewStringUTF("{\"error\":\"grammar parse failed\"}");
        }
        llama_sampler_chain_add(smpl, g);
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    // Prefill in n_batch chunks
    const int n_batch = (int) llama_n_batch(ctx);
    double tp = now_ms();
    int rc = 0;
    for (int i = 0; i < n && rc == 0; i += n_batch) {
        int len = std::min(n_batch, n - i);
        rc = llama_decode(ctx, llama_batch_get_one(toks.data() + i, len));
    }
    double prefill_ms = now_ms() - tp;
    if (rc != 0) {
        llama_sampler_free(smpl);
        return env->NewStringUTF(("{\"error\":\"prefill decode failed\",\"n_prompt\":" + std::to_string(n) + "}").c_str());
    }

    std::string text, stop = "max_tokens";
    int n_out = 0;
    double ttft_ms = 0, t_first = 0;
    for (; n_out < max_tokens; ) {
        llama_token tok = llama_sampler_sample(smpl, ctx, -1);
        if (n_out == 0) { t_first = now_ms(); ttft_ms = t_first - tp; }
        if (llama_vocab_is_eog(vocab, tok)) { stop = "eog"; break; }
        char piece[256];
        int np = llama_token_to_piece(vocab, tok, piece, sizeof piece, 0, false);
        if (np > 0) text.append(piece, np);
        n_out++;
        if (llama_decode(ctx, llama_batch_get_one(&tok, 1)) != 0) { stop = "decode_error"; break; }
    }
    double t_end = now_ms();
    llama_sampler_free(smpl);

    // decode = everything after the first sampled token (tokens 2..n_out); tokens/s over that span
    double decode_ms = n_out > 0 ? t_end - t_first : 0;
    std::string out = "{\"template\":\"" + tmpl_used + "\",\"n_prompt\":" + std::to_string(n) +
                      ",\"prefill_ms\":" + std::to_string(prefill_ms) + ",\"ttft_ms\":" + std::to_string(ttft_ms) +
                      ",\"n_out\":" + std::to_string(n_out) + ",\"decode_ms\":" + std::to_string(decode_ms) +
                      ",\"total_ms\":" + std::to_string(t_end - t0) + ",\"stop\":\"" + stop + "\",\"text\":\"" +
                      json_escape(text) + "\"}";
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL Java_in_gauthama_snapslotify_llmspike_LlamaNative_freeContext(JNIEnv *, jobject, jlong ctx) {
    llama_free(reinterpret_cast<llama_context *>(ctx));
}

JNIEXPORT void JNICALL Java_in_gauthama_snapslotify_llmspike_LlamaNative_freeModel(JNIEnv *, jobject, jlong model) {
    llama_model_free(reinterpret_cast<llama_model *>(model));
}

// ---- Try harder: a vision-language model (LFM2.5-VL) through mtmd. One handle holds the text model, its context and
// the image encoder. The image arrives already shrunk, as packed RGB bytes.

namespace {
struct Vision {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    mtmd_context *mtmd = nullptr;
};

void free_vision(Vision *v) {
    if (!v) return;
    if (v->mtmd) mtmd_free(v->mtmd);
    if (v->ctx) llama_free(v->ctx);
    if (v->model) llama_model_free(v->model);
    delete v;
}
}  // namespace

JNIEXPORT jlong JNICALL Java_in_gauthama_snapslotify_llmspike_LlamaNative_vlLoad(
        JNIEnv *env, jobject, jstring jmodel, jstring jmmproj, jint n_ctx, jint n_threads) {
    auto *v = new Vision();
    llama_model_params mp = llama_model_default_params();
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    v->model = llama_model_load_from_file(jstr(env, jmodel).c_str(), mp);
    if (!v->model) { LOGW("vision text model load failed"); free_vision(v); return 0; }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    v->ctx = llama_init_from_model(v->model, cp);
    if (!v->ctx) { LOGW("vision context init failed"); free_vision(v); return 0; }
    llama_set_abort_callback(v->ctx, abort_requested, nullptr);
    mtmd_context_params tp = mtmd_context_params_default();
    tp.use_gpu = false;
    tp.n_threads = n_threads;
    tp.print_timings = false;
    tp.warmup = false;  // the first real image is the warm-up; a dummy encode would only add to the wait
    v->mtmd = mtmd_init_from_file(jstr(env, jmmproj).c_str(), v->model, tp);
    if (!v->mtmd || !mtmd_support_vision(v->mtmd)) { LOGW("image encoder load failed"); free_vision(v); return 0; }
    return reinterpret_cast<jlong>(v);
}

/**
 * One image and one prompt, as llama-server sends them: the user turn is the media marker, then the prompt, through the
 * model's chat template. Greedy. Returns JSON: n_prompt, eval_ms (image encode + prefill), n_out, decode_ms, total_ms,
 * stop, text; or {"error": ...}.
 */
JNIEXPORT jstring JNICALL Java_in_gauthama_snapslotify_llmspike_LlamaNative_vlRead(
        JNIEnv *env, jobject, jlong handle, jbyteArray jrgb, jint w, jint h, jstring jprompt, jint max_tokens) {
    auto *v = reinterpret_cast<Vision *>(handle);
    if (!v) return env->NewStringUTF("{\"error\":\"no model\"}");
    if (env->GetArrayLength(jrgb) != (jsize) w * h * 3) return env->NewStringUTF("{\"error\":\"bad image size\"}");
    const llama_vocab *vocab = llama_model_get_vocab(v->model);
    double t0 = now_ms();
    llama_memory_clear(llama_get_memory(v->ctx), true);

    const std::string prompt = render_user(v->model, std::string(mtmd_default_marker()) + jstr(env, jprompt));
    if (prompt.empty()) return env->NewStringUTF("{\"error\":\"template failed\"}");

    std::vector<unsigned char> rgb((size_t) w * h * 3);
    env->GetByteArrayRegion(jrgb, 0, (jsize) rgb.size(), reinterpret_cast<jbyte *>(rgb.data()));
    mtmd_bitmap *bmp = mtmd_bitmap_init((uint32_t) w, (uint32_t) h, rgb.data());
    mtmd_input_chunks *chunks = mtmd_input_chunks_init();
    mtmd_input_text text{prompt.c_str(), prompt.size(), true, true};
    const mtmd_bitmap *bmps[1] = {bmp};
    int32_t rc = mtmd_tokenize(v->mtmd, chunks, &text, bmps, 1);
    mtmd_bitmap_free(bmp);
    if (rc != 0) {
        mtmd_input_chunks_free(chunks);
        return env->NewStringUTF("{\"error\":\"tokenize failed\"}");
    }
    const size_t n_prompt = mtmd_helper_get_n_tokens(chunks);
    double te = now_ms();
    llama_pos n_past = 0;
    rc = g_abort.load() ? -1 : mtmd_helper_eval_chunks(v->mtmd, v->ctx, chunks, 0, 0, (int32_t) llama_n_batch(v->ctx), true, &n_past);
    mtmd_input_chunks_free(chunks);
    double eval_ms = now_ms() - te;
    if (rc != 0) return env->NewStringUTF(g_abort.load() ? "{\"error\":\"aborted\"}" : "{\"error\":\"eval failed\"}");

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    std::string out_text, stop = "max_tokens";
    int n_out = 0;
    double t_first = now_ms();
    for (; n_out < max_tokens; ) {
        if (g_abort.load()) { stop = "aborted"; break; }
        llama_token tok = llama_sampler_sample(smpl, v->ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) { stop = "eog"; break; }
        char piece[256];
        int np = llama_token_to_piece(vocab, tok, piece, sizeof piece, 0, false);
        if (np > 0) out_text.append(piece, np);
        n_out++;
        if (llama_decode(v->ctx, llama_batch_get_one(&tok, 1)) != 0) { stop = g_abort.load() ? "aborted" : "decode_error"; break; }
    }
    double t_end = now_ms();
    llama_sampler_free(smpl);
    std::string out = "{\"n_prompt\":" + std::to_string(n_prompt) + ",\"eval_ms\":" + std::to_string(eval_ms) +
                      ",\"n_out\":" + std::to_string(n_out) + ",\"decode_ms\":" + std::to_string(t_end - t_first) +
                      ",\"total_ms\":" + std::to_string(t_end - t0) + ",\"stop\":\"" + stop + "\",\"text\":\"" +
                      json_escape(out_text) + "\"}";
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL Java_in_gauthama_snapslotify_llmspike_LlamaNative_vlFree(JNIEnv *, jobject, jlong handle) {
    free_vision(reinterpret_cast<Vision *>(handle));
}

}  // extern "C"
