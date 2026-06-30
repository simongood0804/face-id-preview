# EGL 渲染与 OpenGL ES 使用说明书

> 版本：1.0.0  
> 最后更新：2026-06-30  
> 适用范围：Face ID 预览应用（QCS6125 + Android 10 + EVS Camera）

---

## 一、概述

Face ID 应用的视频渲染层由以下组件构成：

| 组件 | 角色 | 源码 |
|---|---|---|
| `GLSurfaceView` | Android 窗口 + EGL 上下文管理 | Android Framework（SDK） |
| `EvsGL20CameraRenderer` | EVS 相机帧 OpenGL ES 2.0 渲染器 | EvsSDK AAR（闭源） |
| `FaceIDCameraController` | EVS 帧缓冲供给（`EvsBufferProvider`） | 项目源码 |
| `FaceOverlayView` | Canvas 2D 画框覆盖层 | 项目源码 |

**核心约束：** 应用不自建 EGL 上下文，全部委托给 `GLSurfaceView` 的内部 EGL 管理机制。

---

## 二、GLSurfaceView EGL 生命周期

### 2.1 初始化序列

```
Application.onCreate()
  └── GLSurfaceView.setEGLContextClientVersion(2)
      └── 请求 OpenGL ES 2.0 上下文

  └── GLSurfaceView.setRenderer(renderer)
      └── GLSurfaceView 内部创建 GLThread
          └── GLThread 启动后：
              1. eglGetDisplay(EGL_DEFAULT_DISPLAY)
              2. eglInitialize(display, &major, &minor)
              3. eglChooseConfig()  ─── 选择 RGBA8888 + Depth16 配置
              4. eglCreateWindowSurface(display, config, surface, NULL)
                 └── surface = SurfaceView 的 Surface（由 Android WindowManager 创建）
              5. eglCreateContext(display, config, EGL_NO_CONTEXT, ctxAttribs)
                 └── ctxAttribs: { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE }
              6. eglMakeCurrent(display, surface, surface, context)
                 └── 绑定上下文到当前线程（GLThread）

  └── GLSurfaceView.renderMode = RENDERMODE_CONTINUOUSLY
      └── 每帧（~16ms/60fps）主动回调 onDrawFrame()
```

### 2.2 每帧渲染循环

```
GLSurfaceView.GLThread 主循环：
  ┌─────────────────────────────────────────────────────┐
  │ 1. eglMakeCurrent()  ─── 确保当前线程持有 EGL 上下文 │
  │ 2. renderer.onDrawFrame()  ─── 执行绘制              │
  │ 3. eglSwapBuffers()    ─── 提交帧到显示系统           │
  │ 4. 根据 renderMode 决定是否等待下一个 vsync           │
  └─────────────────────────────────────────────────────┘
```

### 2.3 销毁序列

```
Activity.onPause()
  └── GLSurfaceView.onPause()
      └── GLThread 暂停渲染循环

Activity.onDestroy()
  └── GLSurfaceView 销毁
      └── eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)
      └── eglDestroyContext(display, context)
      └── eglDestroySurface(display, surface)
      └── eglTerminate(display)
```

### 2.4 EGL 配置参数

由 `GLSurfaceView` 内部 `DefaultContextFactory` 设置：

```c
// GLSurfaceView 内部等效代码
EGLint attribList[] = {
    EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
    EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
    EGL_RED_SIZE, 8,
    EGL_GREEN_SIZE, 8,
    EGL_BLUE_SIZE, 8,
    EGL_ALPHA_SIZE, 8,
    EGL_DEPTH_SIZE, 16,
    EGL_STENCIL_SIZE, 0,
    EGL_NONE
};

EGLint ctxAttribs[] = {
    EGL_CONTEXT_CLIENT_VERSION, 2,
    EGL_NONE
};
```

---

## 三、EvsGL20CameraRenderer 渲染流程

> `EvsGL20CameraRenderer` 由 EvsSDK（`com.android.car:evs:1.0.7`）提供，闭源。  
> 以下为基于标准 EVS 渲染模式的功能描述。

### 3.1 接口实现

```
EvsGL20CameraRenderer implements GLSurfaceView.Renderer
                          implements EvsFrameBufferCallback（推测）

方法：
  onSurfaceCreated(gl, config)
  onSurfaceChanged(gl, width, height)
  onDrawFrame(gl)
```

### 3.2 onSurfaceCreated

```
调用时机：EGL 上下文首次创建或重建后

执行操作（推测）：
1. 编译相机渲染着色器程序
   ├── 顶点着色器：传递纹理坐标 + 顶点位置
   └── 片段着色器：YUV → RGB 色彩空间转换

2. 生成并绑定纹理对象
   ├── glGenTextures(1, &cameraTexId)
   └── glBindTexture(GL_TEXTURE_EXTERNAL_OES, cameraTexId)

3. 创建顶点缓冲对象（VBO）
   ├── 全屏四边形顶点：[-1, -1, 1, -1, -1, 1, 1, 1]
   └── 纹理坐标：     [0, 0, 1, 0, 0, 1, 1, 1]
```

### 3.3 onSurfaceChanged

```
调用时机：Surface 尺寸变化

操作：
1. glViewport(0, 0, width, height)  ─── 设置视口
2. 更新投影矩阵（如需要）
```

### 3.4 onDrawFrame

```
调用时机：每帧（60fps）

伪代码流程：
void onDrawFrame(GL10 gl) {
    // 1. 获取新帧
    EvsBufferDesc desc = provider.getNewFrame();  ← 本项目 FaceIDCameraController

    if (desc != null) {
        // 2. 获取 HardwareBuffer
        HardwareBuffer hwb = desc.getHardwareBuffer();

        // 3. 更新外部纹理
        //    └── 内部实现：EGLImageKHR + glEGLImageTargetTexture2DOES
        updateExternalTexture(hwb);

        // 4. 渲染全屏四边形
        glClear(GL_COLOR_BUFFER_BIT);
        glUseProgram(shaderProgram);

        // 5. 绑定纹理
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, cameraTexId);

        // 6. 设置 Uniform
        glUniformMatrix4fv(uMVPMatrixLoc, 1, false, mvpMatrix);
        glUniform1i(uTextureLoc, 0);

        // 7. 绘制
        glBindBuffer(GL_ARRAY_BUFFER, vboId);
        glEnableVertexAttribArray(aPositionLoc);
        glEnableVertexAttribArray(aTexCoordLoc);
        glVertexAttribPointer(aPositionLoc, 2, GL_FLOAT, false, 0, 0);
        glVertexAttribPointer(aTexCoordLoc, 2, GL_FLOAT, false, 0, 8 * 4);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        // 8. 释放帧（关键！避免阻塞 EVS 管线）
        //    注意：此处释放时机由 getNewFrame() 返回后的调用方决定
        //    具体见 FaceIDCameraController.returnBuffer()
    }
}
```

### 3.5 着色器程序

EVS 相机渲染使用 `GL_TEXTURE_EXTERNAL_OES` 扩展纹理，因为相机输出通常为 YUV 格式，需在 GPU 内完成 YUV→RGB 转换。

#### 顶点着色器

```glsl
#version 100
attribute vec4 aPosition;
attribute vec2 aTexCoord;
uniform mat4 uMVPMatrix;
varying vec2 vTexCoord;
void main() {
    vTexCoord = aTexCoord;
    gl_Position = uMVPMatrix * aPosition;
}
```

#### 片段着色器（YUV → RGB 转换）

```glsl
#extension GL_OES_EGL_image_external : require
#version 100
precision mediump float;
varying vec2 vTexCoord;
uniform samplerExternalOES uTexture;
void main() {
    gl_FragColor = texture2D(uTexture, vTexCoord);
}
```

> **说明：** `GL_TEXTURE_EXTERNAL_OES` 是 Android 相机特有的纹理目标，与标准 `GL_TEXTURE_2D` 的区别：
> - 数据来自外部源（相机 HAL 的 Gralloc Buffer）
> - 不能使用 `glTexImage2D` 上传数据
> - 通过 `EGLImageKHR` + `glEGLImageTargetTexture2DOES` 绑定 GPU 可访问的缓冲区
> - 硬件自动处理 YUV→RGBA 转换

---

## 四、HardwareBuffer → OpenGL 纹理绑定

### 4.1 EGLImageKHR 机制

EVS 相机帧通过 `AHardwareBuffer` 在 CPU 和 GPU 之间共享。GPU 侧通过 `EGLImageKHR` 扩展将 HardwareBuffer 暴露为 OpenGL 纹理。

```
┌──────────────────────────────────────────────────────────┐
│                     Android Graphics Stack                │
│                                                          │
│  EVS HAL (Camera)                                        │
│    └── allocates AHardwareBuffer (GPU/CPU accessible)    │
│          │                                                │
│          ▼                                                │
│  App Process                                              │
│    ├── FaceIDCameraController receives HardwareBuffer     │
│    ├── getNewFrame() returns EvsBufferDesc                │
│    │                                                      │
│    ▼                                                      │
│  EvsGL20CameraRenderer (内部实现，概念流程)                │
│                                                          │
│  1. eglGetDisplay(EGL_DEFAULT_DISPLAY)                   │
│  2. eglInitialize(display, NULL, NULL)                    │
│  3. eglGetConfigAttrib()  ─── 选择匹配的 EGLConfig       │
│                                                          │
│  4. // 创建 EGLImageKHR 绑定 HardwareBuffer               │
│     EGLint attrs[] = {                                   │
│         EGL_IMAGE_PRESERVED_KHR, EGL_TRUE,               │
│         EGL_NONE                                         │
│     };                                                    │
│     EGLImageKHR img = eglCreateImageKHR(                 │
│         display,                                         │
│         EGL_NO_CONTEXT,                                  │
│         EGL_NATIVE_BUFFER_ANDROID,                       │
│         (EGLClientBuffer) hardwareBuffer,                │
│         attrs                                            │
│     );                                                    │
│                                                          │
│  5. // 绑定到外部纹理                                      │
│     glBindTexture(GL_TEXTURE_EXTERNAL_OES, texId)         │
│     glEGLImageTargetTexture2DOES(                        │
│         GL_TEXTURE_EXTERNAL_OES,                         │
│         img                                              │
│     );                                                    │
│                                                          │
│  6. // 渲染完成后销毁 EGLImageKHR                         │
│     eglDestroyImageKHR(display, img)                     │
│                                                          │
│  7. eglSwapBuffers(display, surface)  ─── 提交到屏幕      │
└──────────────────────────────────────────────────────────┘
```

### 4.2 AHardwareBuffer → EGLImage 的 EGL 属性

```c
// 标准用法
EGLint attrs[] = {
    EGL_IMAGE_PRESERVED_KHR,   EGL_TRUE,   // 保留缓冲区内容
    EGL_NONE
};

// 注意：
// EGL_NATIVE_BUFFER_ANDROID 要求 gralloc 分配的 buffer
// 必须包含 EGLNativeBufferANDROID 格式信息
// EVS HAL 创建的 HardwareBuffer 默认满足此要求
```

### 4.3 GL_TEXTURE_EXTERNAL_OES 限制

| 限制 | 说明 |
|---|---|
| 绑定方式 | 只能通过 `glEGLImageTargetTexture2DOES` 绑定，不可 `glTexImage2D` |
| Mipmap | 不支持 |
| 采样 | 只能使用 `texture2D()` 采样，不支持 `textureCube()` 等 |
| 精度 | Android 10+ 支持 `EXT_YUV_target` 扩展直接输出 YUV |
| 兼容性 | 仅 `samplerExternalOES` 可采样，需在片段着色器中声明 `#extension` |

---

## 五、帧缓冲供给协议

### 5.1 EvsBufferProvider 接口

```java
public interface EvsBufferProvider {
    EvsBufferDesc getNewFrame();
}
```

**调用方：** `EvsGL20CameraRenderer.onDrawFrame()`

**实现方：** `FaceIDCameraController`

**时序约束：**
```
               GLThread                        EVS Callback Thread
                  │                                    │
                  │  onDrawFrame()                      │
                  │    │                                │
                  │    ▼                                │
                  │  getNewFrame()                      │
                  │    ├── dequeue()  ── 取已就绪帧      │
                  │    ├── onFrameData() ── 算法处理      │
                  │    ├── recycle(old)                  │
                  │    └── return desc                   │
                  │    │                                │
                  │    ▼                                │
                  │  EGLImageKHR + 渲染                   │
                  │    │                                │
                  │    ▼                                │
                  │  eglSwapBuffers()                    │
                  │                                    │
                  │                          onFrameEvent()
                  │                              ├── returnBuffers()
                  │                              │    └── close()+doneWithFrame()
                  │                              └── queue(new)
```

### 5.2 关键约束

- `getNewFrame()` 返回的 `EvsBufferDesc` 在下一帧 `getNewFrame()` 调用前必须通过 `recycle()` 释放
- `HardwareBuffer.close()` 在 `returnBuffers()` 中调用，**不能在 GL 渲染中访问已 close 的 buffer**
- `doneWithFrame(id)` 通知 EVS HAL 该 buffer 可重用

---

## 六、Face Overlay 与 EGL 的关系

`FaceOverlayView` 是 Android `View` 的子类，**不参与 EGL/OpenGL 渲染管线**：

```
                    EGL Layer                      View Layer
    ┌────────────────────────────┐    ┌──────────────────────────┐
    │   GLSurfaceView            │    │  FaceOverlayView          │
    │                            │    │                           │
    │   ┌──────────────────┐     │    │   ┌──────────────────┐    │
    │   │ EVS Camera Frame │     │    │   │ Canvas 2D 画框   │    │
    │   │ (GL_TEXTURE_     │     │    │   │                  │    │
    │   │  EXTERNAL_OES)   │     │    │   │ green/red rect   │    │
    │   │                  │     │    │   │ label text       │    │
    │   │ 全屏四边形渲染    │     │    │   │ confidence %     │    │
    │   └──────────────────┘     │    │   └──────────────────┘    │
    └────────────────────────────┘    └──────────────────────────┘
            硬件加速 (GPU)                     CPU Canvas 绘制
```

两者通过 `FrameLayout`/`ConstraintLayout` 重叠布局，`FaceOverlayView` 使用 `android:visibility="gone"` 默认隐藏，检测到人脸时设置 `VISIBLE`。

---

## 七、EGL 调试与诊断

### 7.1 常用调试命令

```bash
# 检查 EGL 配置
adb shell dumpsys gfxinfo com.skyworth.faceid

# 检查 GL 扩展
adb shell dumpsys SurfaceFlinger

# GPU 频率监控
adb shell cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq

# GPU 负载
adb shell cat /sys/class/kgsl/kgsl-3d0/gpubusy

# 检查 Window Surface
adb shell dumpsys window | grep -A5 "faceid"
```

### 7.2 常见 EGL 错误码

| 错误码 | 值 | 含义 | 排查 |
|---|---|---|---|
| `EGL_SUCCESS` | 0x3000 | 成功 | — |
| `EGL_NOT_INITIALIZED` | 0x3001 | EGL 未初始化 | 检查 `eglGetDisplay` |
| `EGL_BAD_ACCESS` | 0x3002 | 访问违规 | 多线程同时操作 EGL |
| `EGL_BAD_ALLOC` | 0x3003 | 资源分配失败 | 内存不足 |
| `EGL_BAD_ATTRIBUTE` | 0x3004 | 属性参数错误 | EGL config 属性 |
| `EGL_BAD_CONTEXT` | 0x3005 | 上下文无效 | 上下文已销毁 |
| `EGL_BAD_CONFIG` | 0x3006 | Config 无效 | `eglChooseConfig` |
| `EGL_BAD_CURRENT_SURFACE` | 0x3007 | 当前 Surface 无效 | Surface 已销毁 |
| `EGL_BAD_DISPLAY` | 0x3008 | Display 无效 | 连接失败 |
| `EGL_BAD_SURFACE` | 0x3009 | Surface 无效 | ANativeWindow 已释放 |
| `EGL_BAD_MATCH` | 0x3010 | 参数不匹配 | Context/Surface Config 不一致 |
| `EGL_BAD_PARAMETER` | 0x3011 | 参数错误 | — |
| `EGL_BAD_NATIVE_PIXMAP` | 0x3012 | Native pixmap 无效 | — |
| `EGL_BAD_NATIVE_WINDOW` | 0x3013 | Native window 无效 | Surface 已释放 |
| `EGL_CONTEXT_LOST` | 0x300E | 上下文丢失 | GPU 崩溃/重置 |

### 7.3 EGL 错误检查

```c
// 标准调试模式：每次 EGL 调用后检查错误
EGLint err = eglGetError();
if (err != EGL_SUCCESS) {
    LOGE("EGL error 0x%x at %s:%d", err, __FILE__, __LINE__);
}
```

---

## 八、相关文件索引

| 文件 | 用途 | EGL/GLES 相关代码行 |
|---|---|---|
| `PreviewActivity.kt:127-129` | GLSurfaceView 配置：ES 2.0 + Renderer + CONTIUOUSLY | 3 行 |
| `PreviewActivity.kt:122-124` | 创建 EvsGL20CameraRenderer + setProvider | 3 行 |
| `PreviewActivity.kt:204-232` | 视口尺寸计算（不影响 EGL） | 0 行 |
| `FaceIDCameraController.kt:212-243` | getNewFrame() 帧供给协议 | 0 行 |
| `FaceIDCameraController.kt:312-330` | returnBuffer() 帧释放（含 HardwareBuffer.close()） | 0 行 |

> **注意：** 本项目无直接 EGL/GLES 源码。上述所有 EGL 操作由 `GLSurfaceView` 框架和 `EvsGL20CameraRenderer` 闭源代码内部完成。如需修改渲染行为（如自定义着色器），需替换或继承 `EvsGL20CameraRenderer`。
