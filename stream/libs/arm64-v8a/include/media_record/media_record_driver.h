#ifndef MEDIA_RECORD_MEDIA_RECORD_DRIVER_H_
#define MEDIA_RECORD_MEDIA_RECORD_DRIVER_H_

// media_record exported C ABI (doc/integrated-lib-export-proposal.md §4.3).
//
// THE public interface for non-C++ consumers (Android NDK/JNI, Gradle, plain C).
// It is self-contained: no graph_runtime / absl / native_ui / video_codec
// headers, no C++ types. Link the packaged shared library
// (libmedia_record_core*.so/.dylib), which folds the three capability repos in.
//
// One-shot recording:
//
//   mr_session* s = NULL;
//   char err[512];
//   if (mr_session_open(config_json, NULL, -1, 0, &s, err, sizeof(err)) != MR_OK) { ... }
//   if (mr_session_start(s) != MR_OK) { ... }
//   const int rc = mr_session_wait(s, -1);   // -1 = block until the frame budget ends
//   mr_session_close(s);
//
// Resident (run until told to stop): open with resident = 1, then
// mr_session_stop(s) followed by mr_session_wait(s, timeout_ms).
//
// NOTE — mr_session_stop() is NOT how a recording is finished: it aborts the
// graph, the muxer is never finalized, and the output is discarded (0 bytes).
// End a recording with mr_session_push_frame_eof() (external-frame and surface
// pipelines) or by letting a one-shot source's frame budget run out. Use
// mr_session_stop() only to abandon a session.

#include <stddef.h>
#include <stdint.h>

#include "media_record/media_record_export.h"

#ifdef __cplusplus
extern "C" {
#endif

/// Opaque session handle: one assembled + runnable pipeline.
typedef struct mr_session mr_session;

/// Status codes returned by every mr_* entry point. 0 = success, negative = error.
typedef enum mr_status {
  MR_OK = 0,
  MR_WAIT_TIMEOUT = 1,             ///< mr_session_wait: still running at timeout.
  MR_ERROR_INVALID_ARGUMENT = -1,  ///< NULL/empty argument or out-of-range enum.
  MR_ERROR_CONFIG = -2,            ///< config parse/validate: bad JSON or unregistered node.
  MR_ERROR_RUNTIME = -3,           ///< graph assembly or execution failure.
  MR_ERROR_STATE = -4,             ///< call not valid in the current session state.
  MR_ERROR_OVERFLOW = -5,          ///< external frame queue full (P4-A; retry/drop).
} mr_status;

/// Library version (major.minor.patch). Output pointers may be NULL.
MEDIA_RECORD_API int mr_library_version(int* major, int* minor, int* patch);

/// Assemble a pipeline session from a graph_runtime config.
///
/// config_json  : the pipeline config, EITHER a filesystem path OR the JSON text
///                itself (text is detected by a leading '{' after whitespace).
/// options_json : optional per-node-type option overrides, written in the config's
///                own node schema — "nodes":[{"name":..,"type":..,"options":{..}}]
///                (name is mandatory for every node, as in a full config). The
///                overrides are applied per node TYPE, and using the same schema
///                keeps the JSON value types (bool/int/double/string) intact.
///                NULL or "" = no overrides.
/// surface_mode : -1 = keep the config's input_surface value; 0 = force the CPU
///                memory path; 1 = force the MediaCodec input-surface path. 0/1
///                patches BOTH DashcamRenderNode and VideoEncoderNode (they must
///                agree) — see LifecycleContext::input_surface.
/// resident     : 0 = one-shot (the source's frame budget ends the graph);
///                1 = run until mr_session_stop().
/// out          : receives the session on MR_OK; untouched otherwise.
/// error,error_size : optional caller buffer for a human-readable failure reason.
///
/// Returns MR_OK or a negative mr_status.
MEDIA_RECORD_API int mr_session_open(const char* config_json,
                                     const char* options_json,
                                     int surface_mode,
                                     int resident,
                                     mr_session** out,
                                     char* error,
                                     size_t error_size);

/// Begin asynchronous execution (returns immediately).
MEDIA_RECORD_API int mr_session_start(mr_session* session);

/// Block until the graph terminates, or until `timeout_ms` elapses
/// (< 0 = wait forever). MR_OK = clean completion; MR_WAIT_TIMEOUT = still
/// running; negative = failure (the partial output artifact is discarded).
MEDIA_RECORD_API int mr_session_wait(mr_session* session, int timeout_ms);

/// Abort the session: tears the graph down WITHOUT finalizing the muxer, so the
/// output artifact is discarded (no file / 0 bytes). "Graceful" here means
/// "orderly teardown", NOT "flush the MP4". To END a recording use
/// mr_session_push_frame_eof() instead; this call is for abandoning a run.
/// Idempotent; safe when the session is not running. Returns MR_OK either way —
/// it does not report that no recording was written.
MEDIA_RECORD_API int mr_session_stop(mr_session* session);

// ---- P4-A: external frame source -------------------------------------------
// Available only when the pipeline config contains an ExternalFrameSourceNode
// (otherwise MR_ERROR_STATE). Feed frames from any producer thread (e.g. a
// camera callback) after mr_session_start.
// To FINISH the recording call mr_session_push_frame_eof(): the source drains
// its queue and the MP4 is finalized. mr_session_stop() aborts instead — it
// returns MR_OK but no output file is produced.

/// Push one RGBA frame into the session (frame is copied). `rgba` holds
/// `stride * height` bytes; rows are compacted to width*4 internally.
/// NON-BLOCKING and thread-safe: returns MR_ERROR_OVERFLOW when the small
/// handoff queue is full (consumer slower than producer — retry or drop).
/// `timestamp_us <= 0` lets the source synthesize monotonic PTS.
MEDIA_RECORD_API int mr_session_push_frame(mr_session* session,
                                           const unsigned char* rgba,
                                           int stride, int width, int height,
                                           int64_t timestamp_us);

/// Signal "no more frames": the source ends the graph after draining queued
/// frames, so the MP4 finalizes normally (mr_session_wait then returns MR_OK).
/// This is the ONLY clean way to end an external-frame OR surface run — for a
/// surface pipeline there are no frames to push, but the finalize signal is the
/// same one. mr_session_stop() aborts instead and leaves no output.
/// Thread-safe with mr_session_push_frame.
MEDIA_RECORD_API int mr_session_push_frame_eof(mr_session* session);

// ---- P4-C: host-drawn surface path -----------------------------------------
// For encoders that accept ONLY surface input (common on older/vehicle-grade
// Qualcomm parts, where no I420/NV12 ByteBuffer format is offered): the caller
// draws frames with its own pipeline straight onto the encoder's input surface,
// pixel data never enters CPU memory.
//
//   mr_session_open(cfg, NULL, /*surface_mode=*/1, /*resident=*/1, ...);
//   mr_session_start(s);
//   mr_session_get_input_surface(s, &window, &w, &h);
//   mr_render* r = mr_render_create(window, w, h);   // library-owned EGL setup
//   // each frame, on your render thread:
//   //   A) draw with your own GL — the EGL context is already current:
//   mr_render_frame(r, my_draw, my_user, pts_ns);
//   //   B) or hand the library a camera buffer (zero-copy, synchronous):
//   mr_render_frame_buffer(r, ahwb, src_w, src_h, pts_ns);
//   mr_session_notify_frame(s, pts_us);              // pump the encoder
//   //   ... repeat ...
//   mr_render_destroy(r);
//   mr_session_push_frame_eof(s);   // REQUIRED: finalizes the MP4.
//   // (mr_session_stop(s) would abort instead: MR_OK, but 0-byte output.)
//   mr_session_wait(s, -1);

/// Return the encoder's input surface (an ANativeWindow*, delivered as void*)
/// plus the configured frame geometry, so the caller can render into it with
/// its own camera→GL pipeline. Valid from mr_session_start() until close, and
/// only for a session whose encoder runs in surface mode; otherwise
/// MR_ERROR_STATE. Either output pointer may be NULL.
MEDIA_RECORD_API int mr_session_get_input_surface(mr_session* session,
                                                  void** anativewindow,
                                                  int* width, int* height);

/// Announce that the caller just FINISHED drawing one frame onto that surface.
/// One call per frame; non-blocking and safe from the rendering thread; the
/// graph pumps the hardware encoder from this beat. Returns MR_ERROR_OVERFLOW
/// when the small handoff queue is full (encoder slower than the producer).
/// The MP4 frame timestamp comes from the buffer's presentation time — set it
/// through the mr_render_* helpers below (each takes pts_ns), or yourself with
/// eglPresentationTimeANDROID if you own the EGL setup. `timestamp_us` only
/// orders the in-graph notification.
MEDIA_RECORD_API int mr_session_notify_frame(mr_session* session,
                                             int64_t timestamp_us);

// ---- P4-C helper: encoder-input-surface rendering (no hand-written EGL) -----
// The surface path used to require the caller to build the whole EGL plumbing
// (eglGetDisplay / eglChooseConfig / eglCreateWindowSurface / eglCreateContext /
// eglMakeCurrent / eglPresentationTimeANDROID / eglSwapBuffers). These entry
// points do that part for you — the same plumbing the library's own surface
// renderer uses — so the caller only draws, and the presentation timestamp is
// always stamped (a missing one makes MediaCodec drop most input-surface frames).
//
//   mr_render* r = mr_render_create(window, w, h);   // window from mr_session_get_input_surface
//   ... each frame:
//   mr_render_frame(r, my_draw, user, pts_ns);       // GL context current inside my_draw
//   ... or, zero-copy, when the source is an AHardwareBuffer:
//   mr_render_frame_buffer(r, ahwb, src_w, src_h, pts_ns);
//   ...
//   mr_render_destroy(r);
//
// Frame sources may differ per camera (DMS 1600x1300, RVC 1280x760, ...): the
// library letterboxes whatever it is given into the encoder surface, so no
// source-size assumption is made and switching cameras needs no session rebuild.

/// Opaque render handle bound to one encoder input surface.
typedef struct mr_render mr_render;

/// Per-frame callback for mr_render_frame(): invoked with the EGL context
/// already made current, so plain GL calls (glViewport / glDraw* / ...) work.
typedef void (*mr_render_draw_fn)(void* user);

/// THREADING: the mr_render_* calls may run on DIFFERENT threads (create on a
/// setup thread, frames from a camera callback, destroy on a teardown path) as
/// long as no two of them are concurrent. No call leaves the EGL context bound
/// to the calling thread — it is released before returning — which is what makes
/// splitting "frame source thread" from "render thread" safe. (A context can be
/// current on only one thread at a time, so concurrent calls would fail with
/// EGL_BAD_ACCESS.)
///
/// Build the EGL display/config/context and bind `anativewindow` (obtained from
/// mr_session_get_input_surface) as the render target. width/height are the
/// encoder's configured geometry. Returns NULL on failure or on host builds.
MEDIA_RECORD_API mr_render* mr_render_create(void* anativewindow, int width,
                                             int height);

/// Release the handle (and its EGL objects). NULL is a no-op.
MEDIA_RECORD_API void mr_render_destroy(mr_render* render);

/// Draw one frame with the caller's own GL: makes the context current, calls
/// draw(user), then flushes, stamps the presentation time `pts_ns` and swaps.
/// Returns MR_OK or MR_ERROR_INVALID_ARGUMENT.
MEDIA_RECORD_API int mr_render_frame(mr_render* render, mr_render_draw_fn draw,
                                     void* user, int64_t pts_ns);

/// One frame of a solid colour (no callback) — smoke tests / placeholder frames.
/// `rgba` packs 0xRRGGBBAA.
MEDIA_RECORD_API int mr_render_frame_clear(mr_render* render, uint32_t rgba,
                                           int64_t pts_ns);

/// One frame straight from an AHardwareBuffer (Android only), SYNCHRONOUSLY: the
/// buffer is consumed inside this call and never retained, so it is safe to pass
/// one that dies when the caller's frame callback returns (EVS recycles it right
/// after). Do NOT acquire it for later use from another thread — doing so has
/// been observed to crash vendor gralloc.
///
/// `src_width`/`src_height` select the VALID image area from the buffer's
/// top-left: a gralloc allocation can be far taller than the picture it holds
/// (e.g. 1600x3900 allocated with only the top 1600x1300 painted), and the unused
/// part must not reach the video. 0 = the buffer's own dimensions. The valid area
/// is letterboxed into the encoder surface (aspect preserved, black bars).
///
/// NOTE — a YUV camera buffer (e.g. the EVS vendor format 0x120) is NOT usable on
/// this path: the buffer import has no YUV->RGB for such formats, and pushing it
/// through anyway makes the driver silently return wrong colours. MR_ERROR_RUNTIME
/// is the expected and correct answer for it. Zero-copy for those buffers comes
/// from mr_render_frame() instead: bind the buffer yourself (eglCreateImageKHR +
/// glEGLImageTargetTexture2DOES on GL_TEXTURE_EXTERNAL_OES) inside the draw
/// callback and convert YUV->RGB in your own shader — no CPU copy, full colour.
///
/// Returns MR_OK, or a negative mr_status.
MEDIA_RECORD_API int mr_render_frame_buffer(mr_render* render,
                                            void* ahardwarebuffer, int src_width,
                                            int src_height, int64_t pts_ns);

/// How the source is mapped into the encoder surface when the two aspect ratios
/// differ (mr_render_frame_buffer; mr_render_frame() draws whatever the caller
/// draws). Default MR_FILL_FIT.
typedef enum mr_fill_mode {
  MR_FILL_FIT = 0,   ///< aspect preserved, letterboxed (black bars at the sides)
  MR_FILL_CROP = 1,  ///< aspect preserved, centre-cropped to fill (no bars)
} mr_fill_mode;

/// Select MR_FILL_FIT (default) or MR_FILL_CROP. Returns MR_OK, or
/// MR_ERROR_INVALID_ARGUMENT for an unknown mode. CROP additionally avoids
/// touching the pixels the crop removes.
MEDIA_RECORD_API int mr_render_set_fill_mode(mr_render* render, int mode);

/// Copy the handle's last error message into `buffer` (always NUL-terminated).
MEDIA_RECORD_API int mr_render_last_error(mr_render* render, char* buffer,
                                          size_t buffer_size);

/// Surface-path diagnostics. Counters are updated by the graph nodes while the
/// session runs, so a device-side failure can be attributed WITHOUT logcat:
///   source_beats > 0 but encoder_notify == 0   -> notify never reached the encoder
///   encoder_notify > 0 but encoder_polls == 0  -> the encoder was never pumped
///   encoder_polls > 0 but encoder_emitted == 0 -> the codec produced no output
///   encoder_poll_failures > 0                  -> Poll() returned an error
/// (see encoder_last_poll_status for the raw codec status value)
///
/// Safe to call any time after mr_session_open; fields read 0 before the first
/// update. Write into a caller-owned struct — the layout is append-only.
typedef struct mr_stats {
  int64_t source_beats;              ///< host-drawn frames forwarded by the source
  int64_t encoder_notify;            ///< PacketNotify packets seen by the encoder
  int64_t encoder_polls;             ///< encoder Poll() calls
  int64_t encoder_poll_failures;     ///< Poll() calls that returned an error
  int64_t encoder_last_poll_status;  ///< raw status of the last failing Poll()
  int64_t encoder_emitted;           ///< encoded packets forwarded downstream
  int64_t first_keyframe_has_sps;    ///< 1 when the first keyframe carried NAL 7
  int64_t first_keyframe_has_pps;    ///< 1 when it carried NAL 8
  int64_t first_keyframe_size;       ///< bytes of that first keyframe packet
  int64_t muxer_tmpfile_ok;          ///< 1 when the muxer's std::tmpfile() worked
  int64_t muxer_tmpfile_errno;       ///< errno of that tmpfile() (0 = ok)
  int32_t surface_source;            ///< 1 when the source runs in surface mode
  int32_t reserved0;                 ///< alignment padding (append-only ABI)

  // ---- appended 2026-10-08 (append-only: the offsets above are unchanged, but
  // ---- rebuild against this header — sizeof(mr_stats) grew) ----------------
  //
  // Stream-push health. A push session that had silently stopped used to be
  // indistinguishable from a healthy one from outside: the frame counters keep
  // climbing (the encoder is fine) while nothing reaches the server.
  // Reading `push_present == 1 && push_active == 0`, or `push_state == 5`
  // (disconnected) while `push_frames_sent` stagnates, is the signal that the
  // session is down and should be rebuilt.
  int64_t push_present;              ///< 1 when the graph has a StreamPushNode
  int64_t push_active;               ///< 1 while the node still feeds the session
  int64_t push_state;                ///< StreamState: 3=streaming, 4=reconnecting, 5=disconnected, 7=ice-connected (v1.0.3)
  int64_t push_frames_sent;          ///< packets accepted by the push session
  int64_t push_frames_dropped;       ///< packets the session dropped
  int64_t push_bytes_sent;           ///< payload bytes accepted by the session
  int64_t push_rtt_ms;               ///< 0 = unknown (upstream does not fill it yet)
  int64_t push_packet_loss_pct_x100; ///< packet loss %, times 100; 0 = unknown
  int64_t push_uptime_s;             ///< seconds the current session has been up
  int64_t push_bitrate_kbps;         ///< current target bitrate, when ABR is active
} mr_stats;

MEDIA_RECORD_API int mr_session_get_stats(mr_session* session, mr_stats* out);

/// Write 1/0 into `running` (true while the graph is executing).
MEDIA_RECORD_API int mr_session_is_running(mr_session* session, int* running);

/// Copy the session's last error message into `buffer` (always NUL-terminated).
MEDIA_RECORD_API int mr_session_last_error(mr_session* session, char* buffer,
                                           size_t buffer_size);

/// Release the session (stopping it if still running). Idempotent; NULL is a no-op.
MEDIA_RECORD_API int mr_session_close(mr_session* session);

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // MEDIA_RECORD_MEDIA_RECORD_DRIVER_H_
