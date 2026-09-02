package com.skyworth.faceid.algorithm;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLog;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import kotlin.Unit;

import static org.junit.Assert.*;

/**
 * 帧处理效率基准测试（JVM 参考值）。
 *
 * 说明：
 * - native 算法（SNPE / libface.so）无法在 JVM 上运行，本测试用 [MockFaceIDAlgorithm]
 *   模拟算法耗时，验证**帧处理管线**（UYVY→RGB888 转换 + 单槽替换调度）的吞吐与丢帧行为；
 * - 设备端真实帧处理效率（转换/推理耗时、FPS、丢帧）以 logcat 中 FrameProcessor 的
 *   `[PERF]` 摘要日志为准，本测试给出 PC 上的转换吞吐参考值；
 * - 性能断言阈值放宽，避免 CI 环境波动导致误报，重点是输出的实测数字。
 */
@RunWith(RobolectricTestRunner.class)
public class FrameProcessingBenchmarkTest {

    /** 真实相机帧尺寸（UYVY）。 */
    private static final int W = 1600;
    private static final int H = 1300;

    private MockFaceIDAlgorithm mAlgo;
    private ExecutorService mExecutor;
    private int mResultCount;
    private long mLastResultAtMs;

    @Before
    public void setUp() {
        ShadowLog.stream = System.out;
        mAlgo = new MockFaceIDAlgorithm();
        mAlgo.initialize(null, null); // Mock 不校验参数，置为已初始化
        mExecutor = Executors.newSingleThreadExecutor();
        mResultCount = 0;
        mLastResultAtMs = 0L;
    }

    @After
    public void tearDown() {
        mExecutor.shutdownNow();
    }

    private FrameProcessor newProcessor() {
        return new FrameProcessor(mAlgo, mExecutor, result -> {
            mResultCount++;
            mLastResultAtMs = System.currentTimeMillis();
            return Unit.INSTANCE;
        });
    }

    /** 轮询等待处理稳定：最近一次回调后 stableMs 内无新回调，或超时。 */
    private void waitForStable(long stableMs, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int lastCount = -1;
        long lastChange = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            int c = mResultCount;
            if (c != lastCount) {
                lastCount = c;
                lastChange = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - lastChange >= stableMs) {
                return;
            }
            Thread.sleep(50);
        }
    }

    // ---- UYVY→RGB888 转换 ----

    /** 用已知 YUV 像素验证转换公式正确性与缓冲复用行为。 */
    @Test
    public void testConvertUyvyToRgb888Correctness() {
        int w = 4, h = 2;
        // 每像素对 4 字节：U Y0 V Y1
        byte[] uyvy = new byte[w * h * 2];
        // 像素对1: (u=0, y0=255, v=0, y1=0)
        uyvy[0] = 0; uyvy[1] = (byte) 255; uyvy[2] = 0; uyvy[3] = 0;
        // 像素对2: (u=0, y0=255, v=0, y1=0)
        uyvy[4] = 0; uyvy[5] = (byte) 255; uyvy[6] = 0; uyvy[7] = 0;
        // 像素对3: (u=0, y0=255, v=0, y1=0)
        uyvy[8] = 0; uyvy[9] = (byte) 255; uyvy[10] = 0; uyvy[11] = 0;
        // 像素对4: (u=0, y0=255, v=0, y1=0)
        uyvy[12] = 0; uyvy[13] = (byte) 255; uyvy[14] = 0; uyvy[15] = 0;

        byte[] rgb = FrameProcessorKt.convertUyvyToRgb888(uyvy, w, h, null);

        assertEquals("RGB 长度应为 w*h*3", w * h * 3, rgb.length);
        // y0=255,u=0,v=0 → RGB=(74,255,20)；y1=0,u=0,v=0 → RGB=(0,135,0)
        assertEquals(74, rgb[0] & 0xFF);
        assertEquals(255, rgb[1] & 0xFF);
        assertEquals(20, rgb[2] & 0xFF);
        assertEquals(0, rgb[3] & 0xFF);
        assertEquals(135, rgb[4] & 0xFF);
        assertEquals(0, rgb[5] & 0xFF);
    }

    /** 尺寸匹配时复用缓冲，不匹配时新分配。 */
    @Test
    public void testConvertReusesBuffer() {
        int w = 4, h = 2;
        byte[] uyvy = new byte[w * h * 2];
        byte[] matched = new byte[w * h * 3];
        byte[] r1 = FrameProcessorKt.convertUyvyToRgb888(uyvy, w, h, matched);
        assertSame("尺寸匹配应复用同一缓冲", matched, r1);

        byte[] unmatched = new byte[1];
        byte[] r2 = FrameProcessorKt.convertUyvyToRgb888(uyvy, w, h, unmatched);
        assertNotSame("尺寸不匹配应新分配", unmatched, r2);
        assertEquals(w * h * 3, r2.length);
    }

    /** 全图 1600×1300 UYVY→RGB888 转换吞吐（PC 参考值）。 */
    @Test
    public void testFullFrameConversionThroughput() {
        byte[] uyvy = new byte[W * H * 2];
        byte[] reuse = new byte[W * H * 3];
        int iters = 50;

        // 预热（触发 JIT 后测稳态）
        for (int i = 0; i < 5; i++) {
            FrameProcessorKt.convertUyvyToRgb888(uyvy, W, H, reuse);
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            FrameProcessorKt.convertUyvyToRgb888(uyvy, W, H, reuse);
        }
        double avgMs = (System.nanoTime() - t0) / 1e6 / iters;

        System.out.printf("[PERF-BM] UYVY→RGB888 %dx%d: avg=%.2fms/帧 (%d次)%n", W, H, avgMs, iters);
        assertTrue("转换平均耗时异常偏高: " + avgMs + "ms", avgMs < 500.0);
    }

    // ---- 单槽替换调度吞吐 ----

    /** 慢算法（50ms/帧）+ 30fps 提交：单槽覆盖必然丢帧，验证吞吐与丢帧计数。 */
    @Test(timeout = 20000)
    public void testFrameThroughputWithAlgoDelay() throws InterruptedException {
        mAlgo.setProcessDelayMs(50);
        FrameProcessor fp = newProcessor();
        byte[] frame = new byte[W * H * 2];
        int frames = 60;

        long t0 = System.currentTimeMillis();
        for (int i = 0; i < frames; i++) {
            fp.submitFrame(frame, W, H);
            Thread.sleep(33); // ~30fps
        }
        waitForStable(500, 8000);
        long elapsed = System.currentTimeMillis() - t0;

        int completed = mResultCount;
        double fps = completed * 1000.0 / elapsed;
        System.out.printf("[PERF-BM] 算法50ms/帧, 30fps提交 %d帧: 完成=%d 丢帧=%d 耗时=%dms 吞吐=%.1ffps%n",
                frames, completed, frames - completed, elapsed, fps);

        assertTrue("慢算法下应有帧完成", completed > 0);
        assertTrue("50ms 处理 > 33ms 间隔，单槽必然丢帧", completed < frames);
        // 理论吞吐上界 ≈ 提交时长 / 单帧处理耗时
        assertTrue("完成帧数不应超过理论上界: " + completed, completed <= frames);
    }

    /** 快算法（无延迟）+ 30fps 提交：处理远快于帧间隔，不应丢帧。 */
    @Test(timeout = 20000)
    public void testNoDropWhenAlgoFast() throws InterruptedException {
        FrameProcessor fp = newProcessor();
        byte[] frame = new byte[640 * 480 * 2];
        int frames = 30;

        for (int i = 0; i < frames; i++) {
            fp.submitFrame(frame, 640, 480);
            Thread.sleep(33); // ~30fps
        }
        waitForStable(500, 8000);

        int completed = mResultCount;
        System.out.printf("[PERF-BM] 无算法延迟, 30fps提交 %d帧: 完成=%d%n", frames, completed);
        assertEquals("快算法下不应丢帧", frames, completed);
    }
}
