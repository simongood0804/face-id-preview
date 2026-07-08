// faceid_api.h — Public C API for FaceID pipeline on QCS6125
//
// Integration guide:
//   1. faceid_init()     — load models once at startup
//   2. faceid_detect()   — call per camera frame (UYVY input from IR camera)
//   3. faceid_compare()  — compare two embeddings (cosine similarity)
//   4. faceid_destroy()  — release resources at shutdown
//
// Thread safety: One handle = one thread. Not thread-safe for multi-threaded
//                access to the same handle. Create separate handles per thread.
//
// Example:
//   FaceIDHandle h = faceid_init("/data/faceid/models/manifest.json");
//   faceid_configure(h, FACEID_ALL);
//
//   FaceResult results[10];
//   int n = faceid_detect(h, uyvy_frame, 640, 480, 0, FACEID_FMT_UYVY, results, 10);
//   for (int i = 0; i < n; i++) {
//       if (results[i].liveness > 0.5f) {
//           float sim = faceid_compare(results[i].emb, registered_emb);
//           if (sim > 0.25f) { /* match */ }
//       }
//   }
//
//   faceid_destroy(h);

#ifndef FACEID_API_H
#define FACEID_API_H

#ifdef __cplusplus
extern "C" {
#endif

#include "faceid_types.h"

#if defined(_WIN32) || defined(__CYGWIN__)
#  ifdef FACEID_EXPORTS
#    define FACEID_API __declspec(dllexport)
#  else
#    define FACEID_API __declspec(dllimport)
#  endif
#else
#  define FACEID_API __attribute__((visibility("default")))
#endif

// ═══════════════════════════════════════════════════════════
// Opaque handle (internal state, do NOT access directly)
// ═══════════════════════════════════════════════════════════

typedef void* FaceIDHandle;

// API Functions

// ── Debug ──

// Enable/disable debug timing logs (default: off).
// When enabled, each model's preprocessing/inference/postprocessing
// time is printed to logcat (tag: FaceID, level: INFO).
// Useful for diagnosing pipeline bottlenecks at cost of log volume.
FACEID_API void faceid_set_debug_log(int enable);

// ── Lifecycle ──

// Initialize FaceID pipeline. Call ONCE at startup.
//
//   manifest_path: path to manifest.json
// Returns:      handle to use in subsequent calls, or NULL on failure
FACEID_API FaceIDHandle faceid_init(const char* manifest_path);

// Configure which sub-models to load and run.
// MUST be called once after faceid_init() and before faceid_detect().
//
//   handle:  from faceid_init()
//   flags:   bitwise OR of FaceIDFlag values (e.g., FACEID_FLAG_ALL)
//            FACEID_FLAG_DET is required for all other stages.
//            If only FACEID_FLAG_DET: only bbox + 5 keypoints returned.
//            Add FACEID_FLAG_LIVENESS/FLAG_LANDMARK/FLAG_RECOG as needed.
//
// Returns: 0 on success, -1 on failure
FACEID_API int faceid_configure(FaceIDHandle handle, uint32_t flags);

// Destroy handle and release all resources (memory, DSP, DLC models).
// Call ONCE at shutdown. Handle becomes invalid after this call.
FACEID_API void faceid_destroy(FaceIDHandle handle);

// ── Inference (per-frame) ──

// Run full pipeline on a camera frame.
//
// Input:
//   handle:    from faceid_init() + faceid_configure()
//   img_data:  raw image buffer (uint8_t*)
//   width:     image width in pixels (e.g., 640)
//   height:    image height in pixels (e.g., 480)
//   stride:    bytes per row (0 = auto-calculate from width & format)
//   format:    image format (FACEID_FMT_UYVY for IR camera, FACEID_FMT_RGB, etc.)
//
// Output:
//   results:   array of FaceResult, filled with detected faces (pre-allocated by caller)
//              results[0] to results[n-1] contain valid data
//   max_faces: maximum number of faces to return (capacity of results array)
//              suggested: 10 for typical use, 16 max
//   timing:    optional timing breakdown (pass NULL if not needed)
//
// Returns:     number of faces detected (0 = no face, -1 = pipeline error)
//
// Performance (typical, QCS6125 DSP, 640x480 UYVY):
//   det_500m:    ~44ms
//   liveness:    ~8ms
//   2d106det:    ~10ms (per face)
//   w600k_mbf:   ~15ms (per face)
//   Total (1 face, all models): ~75ms
FACEID_API int faceid_detect(FaceIDHandle handle,
                  const uint8_t* img_data,
                  int width, int height,
                  int stride,
                  FaceIDFormat format,
                  FaceResult* results,
                  int max_faces,
                  FaceIDTiming* timing);

// ── Embedding comparison (standalone, no handle needed) ──

// Compare two 512-D L2-normalized face embeddings.
// Both embeddings must come from faceid_detect() (already L2-normalized).
//
//   emb1, emb2:  512-D float arrays from FaceResult.emb
//
// Returns:       cosine similarity [0, 1]
//                1.0  = identical (same person)
//                0.0  = completely different people
//                High-quality match: > 0.30
//                Acceptable match:   > 0.25
//                Different person:   < 0.15
FACEID_API float faceid_compare(const float* emb1, const float* emb2);

// ── Version ──

// Get library version string (e.g., "1.0.0")
FACEID_API const char* faceid_version(void);

#ifdef __cplusplus
}
#endif

#endif // FACEID_API_H
