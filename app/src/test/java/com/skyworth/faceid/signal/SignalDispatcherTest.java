package com.skyworth.faceid.signal;

import com.skyworth.faceid.algorithm.IFaceIDAlgorithm;
import com.skyworth.faceid.bus.BusHub;
import com.skyworth.faceid.bus.BusPublisher;
import com.skyworth.faceid.bus.BusSubscriber;
import com.skyworth.faceid.bus.ServiceRegistry;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/**
 * SignalDispatcher（信号分发器）集成测试。
 *
 * 结合消息总线，验证：
 * - 轮询车速 + 算法结果 → 分心防抖 → 发布 FRAME_OVERLAY
 * - 无人脸延时复位分心（防算法短暂漏检误消除信号）
 * - 车速分档影响 overlay 阈值
 * - 车速 topic 断流不影响算法结果处理（故障隔离）
 */
public class SignalDispatcherTest {

    private BusHub mHub;
    private BusPublisher mPublisher;
    private AtomicLong mClock;
    private SignalDispatcher mDispatcher;

    @Before
    public void setUp() {
        mHub = new BusHub();
        mPublisher = new BusPublisher(mHub);
        mClock = new AtomicLong(0L);
        DistractionStateMachine machine = new DistractionStateMachine(
                () -> mClock.get(), DistractionStateMachine.NO_FACE_RESET_MS);
        mDispatcher = new SignalDispatcher(mHub, mPublisher,
                r -> new SignalTypes.AlgoDistractionInput(r.getFaceId().length() > 0, r.getGazeDistracted()),
                machine);
    }

    private void advance(long ms) {
        mClock.addAndGet(ms);
    }

    private IFaceIDAlgorithm.FaceIDResult distractedResult(boolean hasFace) {
        return new IFaceIDAlgorithm.FaceIDResult(
                hasFace ? "detected" : "",
                0.9f,
                null,                       // faceRect
                null,                       // processedData
                null,                       // landmarks
                false,                      // isNewEnrollment
                false,                      // enrollmentReady
                null,                       // keypoints
                0f, 0f, 0f,                 // headposePitch/Yaw/Roll
                0f, 0f, 0f,                 // gazeValid/Yaw/Pitch
                hasFace ? 1f : 0f,          // gazeDistracted
                0f,                         // gazeCalibrated
                0f, 0f, 0f,                 // distractionScore/HpScore/GazeScore
                0f, 0f,                     // zoneId/zoneConfidence
                false, false                // eyeOpen/mouthOpen
        );
    }

    @Test
    public void testPublishOverlayAfterDistraction() {
        // 先发布车速（无数据/高速）
        mPublisher.publish(ServiceRegistry.Topic.VEHICLE_SPEED,
                new SignalTypes.VehicleSpeed(-1f, false));
        mPublisher.publish(ServiceRegistry.Topic.ALGO_RESULT, distractedResult(true));
        mDispatcher.poll();

        // 初始未分心
        assertFalse(mDispatcher.getLastDistraction().getDistracted());

        // 持续分心超过快速档 1.5s
        advance(1500);
        mPublisher.publish(ServiceRegistry.Topic.ALGO_RESULT, distractedResult(true));
        mDispatcher.poll();

        assertTrue("持续分心 1.5s 应触发", mDispatcher.getLastDistraction().getDistracted());
        assertEquals("fast 档阈值应为 1500ms",
                DistractionStateMachine.TRIGGER_MS_FAST, mDispatcher.getLastDistraction().getActiveThresholdMs());
    }

    @Test
    public void testSlowBandThresholdFromSpeedTopic() {
        // 低速（30km/h）→ slow 档
        mPublisher.publish(ServiceRegistry.Topic.VEHICLE_SPEED,
                new SignalTypes.VehicleSpeed(30f, true));
        mDispatcher.poll();

        mPublisher.publish(ServiceRegistry.Topic.ALGO_RESULT, distractedResult(true));
        mDispatcher.poll();
        advance(1500);
        mPublisher.publish(ServiceRegistry.Topic.ALGO_RESULT, distractedResult(true));
        mDispatcher.poll();

        assertFalse("低速 1.5s 不应触发", mDispatcher.getLastDistraction().getDistracted());
        assertEquals("slow 档阈值应为 3000ms",
                DistractionStateMachine.TRIGGER_MS_SLOW, mDispatcher.getLastDistraction().getActiveThresholdMs());

        advance(1500);
        mPublisher.publish(ServiceRegistry.Topic.ALGO_RESULT, distractedResult(true));
        mDispatcher.poll();
        assertTrue("低速达到 3s 应触发", mDispatcher.getLastDistraction().getDistracted());
    }

    @Test
    public void testNoFaceResetsDistractionAfterConfirm() {
        mPublisher.publish(ServiceRegistry.Topic.VEHICLE_SPEED,
                new SignalTypes.VehicleSpeed(-1f, false));
        mDispatcher.poll();

        // 触发分心
        mPublisher.publish(ServiceRegistry.Topic.ALGO_RESULT, distractedResult(true));
        mDispatcher.poll();
        advance(1500);
        mPublisher.publish(ServiceRegistry.Topic.ALGO_RESULT, distractedResult(true));
        mDispatcher.poll();
        assertTrue(mDispatcher.getLastDistraction().getDistracted());

        // 无人脸：未达确认时长 → 保持分心（防算法短暂漏检误消除信号）
        mPublisher.publish(ServiceRegistry.Topic.ALGO_RESULT, distractedResult(false));
        mDispatcher.poll();
        assertTrue("无人脸未达确认时长应保持分心", mDispatcher.getLastDistraction().getDistracted());

        // 持续无人脸达确认时长 → 复位
        advance(DistractionStateMachine.NO_FACE_RESET_MS);
        mPublisher.publish(ServiceRegistry.Topic.ALGO_RESULT, distractedResult(false));
        mDispatcher.poll();
        assertFalse("持续无人脸达确认时长应复位", mDispatcher.getLastDistraction().getDistracted());
    }

    @Test
    public void testSpeedTopicFaultDoesNotBreakAlgoProcessing() {
        // 只发布算法结果，不发布车速 → 车速 topic 断流
        mPublisher.publish(ServiceRegistry.Topic.ALGO_RESULT, distractedResult(true));
        mDispatcher.poll();
        advance(1500);
        mPublisher.publish(ServiceRegistry.Topic.ALGO_RESULT, distractedResult(true));
        mDispatcher.poll();

        assertTrue("车速断流不影响算法分心判定（按无数据 fast 档）",
                mDispatcher.getLastDistraction().getDistracted());
        assertEquals("无车速应按 fast 档", "fast", mDispatcher.getLastDistraction().getSpeedBand());
    }

    @Test
    public void testCurrentSpeedFromTopic() {
        mPublisher.publish(ServiceRegistry.Topic.VEHICLE_SPEED,
                new SignalTypes.VehicleSpeed(42.5f, true));
        mDispatcher.poll();
        assertEquals(42.5f, mDispatcher.getCurrentSpeedKmh(), 0.001f);
    }

    @Test
    public void testDirectProcessVehicleSpeed() {
        mDispatcher.processVehicleSpeed(new SignalTypes.VehicleSpeed(80f, true));
        assertEquals(80f, mDispatcher.getCurrentSpeedKmh(), 0.001f);
    }

    @Test
    public void testDirectProcessAlgorithmResultPublishesOverlay() {
        // 先订阅 FRAME_OVERLAY（须在发布之前创建订阅器，否则错过历史消息）
        BusSubscriber overlaySub = new BusSubscriber(mHub,
                java.util.Collections.singleton(ServiceRegistry.Topic.FRAME_OVERLAY));

        mDispatcher.processVehicleSpeed(new SignalTypes.VehicleSpeed(-1f, false));
        mDispatcher.processAlgorithmResult(distractedResult(true));  // 开始计时
        advance(1500);
        mDispatcher.processAlgorithmResult(distractedResult(true));  // 达到 1.5s 触发
        assertTrue(mDispatcher.getLastDistraction().getDistracted());

        // 验证 FRAME_OVERLAY 已发布到总线
        overlaySub.update();
        SignalTypes.DistractionOutput out = overlaySub.latestPayload(
                ServiceRegistry.Topic.FRAME_OVERLAY, SignalTypes.DistractionOutput.class);
        assertNotNull("FRAME_OVERLAY 应已发布", out);
        assertTrue(out.getDistracted());
    }

    @Test
    public void testClose() {
        mDispatcher.close();
        mDispatcher.poll();  // 不抛异常
    }

    // ============================================================
    // 阶段五：容错 - 故障上报与降级
    // ============================================================

    @Test
    public void testSpeedTopicDownReportsFault() {
        // 不发布车速 topic → 失活 → poll 上报 SIGNAL_ERROR
        mDispatcher.poll(System.nanoTime());
        assertFalse("车速 topic 应失活", mDispatcher.getVehicleSpeedHealthy());
        assertNotNull("应记录车速故障", mDispatcher.getLastFault());
        assertEquals(SignalTypes.FaultEvent.CODE_VEHICLE_SPEED_UNAVAILABLE,
                mDispatcher.getLastFault().getCode());
    }

    @Test
    public void testSpeedDownDoesNotBreakAlgoProcessing() {
        // 只发布算法结果，车速断流 → 算法分心判定仍正常（按 fast 档）
        mPublisher.publish(ServiceRegistry.Topic.ALGO_RESULT, distractedResult(true));
        mDispatcher.poll(System.nanoTime());
        advance(1500);
        mPublisher.publish(ServiceRegistry.Topic.ALGO_RESULT, distractedResult(true));
        mDispatcher.poll(System.nanoTime());

        assertTrue("车速断流不影响算法分心判定", mDispatcher.getLastDistraction().getDistracted());
        assertEquals("无车速应按 fast 档", "fast", mDispatcher.getLastDistraction().getSpeedBand());
        // 车速故障已上报
        assertFalse(mDispatcher.getVehicleSpeedHealthy());
    }

    @Test
    public void testFaultRecoveryWhenSpeedRestored() {
        // 车速断流 → 故障
        mDispatcher.poll(System.nanoTime());
        assertFalse(mDispatcher.getVehicleSpeedHealthy());

        // 恢复车速 → 健康，lastFault 保持记录但 healthy 恢复
        mPublisher.publish(ServiceRegistry.Topic.VEHICLE_SPEED,
                new SignalTypes.VehicleSpeed(55f, true));
        mDispatcher.poll(System.nanoTime());
        assertTrue(mDispatcher.getVehicleSpeedHealthy());
        assertEquals(55f, mDispatcher.getCurrentSpeedKmh(), 0.001f);
    }

    @Test
    public void testSignalErrorPublishedToBus() {
        // 先订阅 SIGNAL_ERROR
        BusSubscriber errSub = new BusSubscriber(mHub,
                java.util.Collections.singleton(ServiceRegistry.Topic.SIGNAL_ERROR));

        // 车速断流 → poll 发布 SIGNAL_ERROR
        mDispatcher.poll(System.nanoTime());

        errSub.update();
        SignalTypes.FaultEvent fault = errSub.latestPayload(
                ServiceRegistry.Topic.SIGNAL_ERROR, SignalTypes.FaultEvent.class);
        assertNotNull("SIGNAL_ERROR 应已发布", fault);
        assertEquals(SignalTypes.FaultEvent.CODE_VEHICLE_SPEED_UNAVAILABLE, fault.getCode());
    }
}
