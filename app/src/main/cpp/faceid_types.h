#pragma once

#include <stdint.h>

// Image format: tells detect() how to interpret the input buffer.
typedef enum {
    FACEID_FMT_UYVY  = 0,
    FACEID_FMT_RGB   = 1,
    FACEID_FMT_BGR   = 2,
    FACEID_FMT_GRAY8 = 3,
} FaceIDFormat;

// Which sub-models to load and run. Use bitwise OR to combine.
typedef enum {
    FACEID_FLAG_DET      = 1 << 0,
    FACEID_FLAG_LIVENESS = 1 << 1,
    FACEID_FLAG_LANDMARK = 1 << 2,
    FACEID_FLAG_RECOG    = 1 << 3,
    FACEID_FLAG_ALL      = 0x0F,
} FaceIDFlag;

// Maximum number of faces that can be returned per frame.
#define FACEID_MAX_FACES 16

typedef struct {
    float x1, y1;
    float x2, y2;
    float score;

    float kps[5][2];

    float liveness;

    float landmarks[106][2];
    int landmarks_valid;

    float emb[512];
    int emb_valid;

    float reserved[32];
} FaceResult;

// Optional timing breakdown per stage, in milliseconds.
typedef struct {
    float det_pre_ms;
    float det_inf_ms;
    float det_post_ms;
    float liveness_ms;
    float landmark_ms;
    float recog_ms;
} FaceIDTiming;
