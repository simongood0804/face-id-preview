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
//   FaceIDHandle h = faceid_init("/data/faceid/models", "dsp");
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

#include <stdint.h>

// ═══════════════════════════════════════════════════════════
// Opaque handle (internal state, do NOT access directly)
// ═══════════════════════════════════════════════════════════

typedef void* FaceIDHandle;

// ═══════════════════════════════════════════════════════════
// Input image format
// ═══════════════════════════════════════════════════════════

// Image format: tells faceid_detect() how to interpret the input buffer
typedef enum {
    FACEID_FMT_UYVY  = 0,   // UYVY 4:2:2, IR camera default
                             //   Bytes: [U0 Y0 V0 Y1] [U2 Y2 V2 Y3] ...
                             //   2 bytes per pixel, IR cameras: Y=grayscale, U/V≈0
    FACEID_FMT_RGB   = 1,   // RGB interleaved, 3 bytes/pixel [R G B R G B ...]
    FACEID_FMT_BGR   = 2,   // BGR interleaved, 3 bytes/pixel (OpenCV default)
    FACEID_FMT_GRAY8 = 3,   // 8-bit grayscale, 1 byte/pixel
} FaceIDFormat;

// ═══════════════════════════════════════════════════════════
// Pipeline configuration flags (bitmask)
// ═══════════════════════════════════════════════════════════

// Which sub-models to load and run. Use bitwise OR to combine.
// Models not enabled will be skipped (corresponding output fields = invalid).
// faceid_configure() MUST be called before faceid_detect().
typedef enum {
    FACEID_FLAG_DET      = 1 << 0,   // Detection (required for all other stages)
    FACEID_FLAG_LIVENESS = 1 << 1,   // Anti-spoofing liveness detection
    FACEID_FLAG_LANDMARK = 1 << 2,   // 2D 106-point facial landmarks
    FACEID_FLAG_RECOG    = 1 << 3,   // Face recognition (512-D embedding)
    FACEID_FLAG_ALL      = 0x0F,     // All models enabled
} FaceIDFlag;

// ═══════════════════════════════════════════════════════════
// Per-face detection result
// ═══════════════════════════════════════════════════════════

// Maximum number of faces that can be returned per frame
#define FACEID_MAX_FACES 16

// One result per detected face. All coordinates are in original image pixels.
// Check *_valid fields to determine which sub-models produced valid output.
typedef struct {
    // ── Detection (always valid if face detected) ──
    // Bounding box in original image coordinates [0, width] x [0, height]
    float x1, y1;             // Top-left corner
    float x2, y2;             // Bottom-right corner
    float score;              // Detection confidence [0, 1]

    // ── 5 facial keypoints (from detection model, always valid if detected) ──
    // In original image coordinates: 0=left_eye, 1=right_eye, 2=nose_tip,
    //                                 3=left_mouth, 4=right_mouth
    float kps[5][2];

    // ── Liveness (valid if FACEID_FLAG_LIVENESS enabled AND model loaded OK) ──
    // [0, 1]: 0 = spoof (fake), 1 = real person
    // -1.0 = not available (model not loaded, or inference failed)
    float liveness;

    // ── 106-point 2D landmarks (valid if FACEID_FLAG_LANDMARK enabled) ──
    // In original image coordinates, 106 points * 2 coordinates = 212 floats
    // landmarks_valid = 0 means not available
    float landmarks[106][2];
    int landmarks_valid;      // 0 = unavailable, 1 = valid

    // ── 512-D face embedding (valid if FACEID_FLAG_RECOG enabled) ──
    // L2-normalized, use faceid_compare() for cosine similarity comparison
    // emb_valid = 0 means not available
    float emb[512];
    int emb_valid;            // 0 = unavailable, 1 = valid

    // ── Reserved for future expansion ──
    float reserved[32];
} FaceResult;

// ═══════════════════════════════════════════════════════════
// Performance timing (optional, for debugging only)
// ═══════════════════════════════════════════════════════════

// If non-NULL, filled with per-stage timing in milliseconds.
// Each field is -1.0 if that stage was not run.
typedef struct {
    float det_pre_ms;         // Detection: preprocessing time
    float det_inf_ms;         // Detection: SNPE inference time
    float det_post_ms;        // Detection: post-processing + NMS time
    float liveness_ms;        // Liveness: total time
    float landmark_ms;        // Landmark: total time
    float recog_ms;           // Recognition: total time
} FaceIDTiming;

// ═══════════════════════════════════════════════════════════
// API Functions
// ═══════════════════════════════════════════════════════════

// ── Lifecycle ──

// Initialize FaceID pipeline. Call ONCE at startup.
//
//   model_dir:  directory containing DLC model files
//               (det_500m_int8.dlc, 2d106det_int8.dlc, w600k_mbf_int8.dlc,
//                face_antispoof_int8.dlc)
//   runtime:    "dsp" for QCS6125 DSP acceleration, "cpu" for CPU fallback
//
// Returns:      handle to use in subsequent calls, or NULL on failure
FaceIDHandle faceid_init(const char* model_dir, const char* runtime);

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
int faceid_configure(FaceIDHandle handle, uint32_t flags);

// Destroy handle and release all resources (memory, DSP, DLC models).
// Call ONCE at shutdown. Handle becomes invalid after this call.
void faceid_destroy(FaceIDHandle handle);

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
int faceid_detect(FaceIDHandle handle,
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
float faceid_compare(const float* emb1, const float* emb2);

// ── Version ──

// Get library version string (e.g., "1.0.0")
const char* faceid_version(void);

#ifdef __cplusplus
}
#endif

#endif // FACEID_API_H
