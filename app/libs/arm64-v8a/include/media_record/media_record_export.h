#ifndef MEDIA_RECORD_MEDIA_RECORD_EXPORT_H_
#define MEDIA_RECORD_MEDIA_RECORD_EXPORT_H_

// Symbol visibility control for media_record public API.
// Mirrors graph_runtime / native_ui / video_codec export macros.
// Public symbols are decorated with MEDIA_RECORD_API; the shared-library build
// defines MEDIA_RECORD_SHARED_LIBRARY so the attribute expands.

#if defined(_WIN32)
  #if defined(MEDIA_RECORD_SHARED_LIBRARY)
    #define MEDIA_RECORD_API __declspec(dllexport)
  #else
    #define MEDIA_RECORD_API __declspec(dllimport)
  #endif
#else
  #if defined(MEDIA_RECORD_SHARED_LIBRARY)
    #define MEDIA_RECORD_API __attribute__((visibility("default")))
  #else
    #define MEDIA_RECORD_API
  #endif
#endif

#endif  // MEDIA_RECORD_MEDIA_RECORD_EXPORT_H_
