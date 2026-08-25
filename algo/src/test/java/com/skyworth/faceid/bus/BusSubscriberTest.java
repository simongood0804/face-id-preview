package com.skyworth.faceid.bus;

import org.junit.Before;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * BusHub + BusPublisher + BusSubscriber 集成测试。
 *
 * 重点验证 openpilot SubMaster 式的 per-topic 健康检查：
 * 某个 topic 断流只影响它自己的 alive，不影响其他 topic——即"单层故障不拖垮其他层"。
 */
public class BusSubscriberTest {

    private BusHub mHub;
    private BusPublisher mPublisher;

    @Before
    public void setUp() {
        mHub = new BusHub();
        mPublisher = new BusPublisher(mHub);
    }

    private BusSubscriber subscriber(ServiceRegistry.Topic... topics) {
        Set<ServiceRegistry.Topic> set = new HashSet<>();
        for (ServiceRegistry.Topic t : topics) set.add(t);
        return new BusSubscriber(mHub, set);
    }

    @Test
    public void testSubscribeAndReceive() {
        BusSubscriber sub = subscriber(
                ServiceRegistry.Topic.ALGO_RESULT,
                ServiceRegistry.Topic.VEHICLE_SPEED);

        mPublisher.publish(ServiceRegistry.Topic.ALGO_RESULT, "face1");
        mPublisher.publish(ServiceRegistry.Topic.VEHICLE_SPEED, 55.5f);

        sub.update();

        assertTrue("ALGO_RESULT 应存活", sub.alive(ServiceRegistry.Topic.ALGO_RESULT));
        assertTrue("VEHICLE_SPEED 应存活", sub.alive(ServiceRegistry.Topic.VEHICLE_SPEED));
        assertEquals("face1", sub.latestPayload(ServiceRegistry.Topic.ALGO_RESULT, String.class));
        assertEquals(55.5f, sub.latestPayload(ServiceRegistry.Topic.VEHICLE_SPEED, Float.class), 0.001f);
    }

    @Test
    public void testPerTopicFaultIsolation() {
        // 订阅两个 topic
        BusSubscriber sub = subscriber(
                ServiceRegistry.Topic.ALGO_RESULT,
                ServiceRegistry.Topic.VEHICLE_SPEED);

        // 只发布 ALGO_RESULT，VEHICLE_SPEED 无数据
        mPublisher.publish(ServiceRegistry.Topic.ALGO_RESULT, "face1");
        sub.update();

        assertTrue("有数据的 topic 应存活", sub.alive(ServiceRegistry.Topic.ALGO_RESULT));
        assertFalse("无数据的 topic 应失活", sub.alive(ServiceRegistry.Topic.VEHICLE_SPEED));

        // 再次 update，VEHICLE_SPEED 仍无数据 → 依然失活；ALGO_RESULT 不受影响
        sub.update();
        assertTrue(sub.alive(ServiceRegistry.Topic.ALGO_RESULT));
        assertFalse(sub.alive(ServiceRegistry.Topic.VEHICLE_SPEED));
    }

    @Test
    public void testHealthCheckTimeout() {
        BusSubscriber sub = subscriber(ServiceRegistry.Topic.ALGO_STATE);

        // 发布一次，alive
        mPublisher.publish(ServiceRegistry.Topic.ALGO_STATE, "ok");
        sub.update(System.nanoTime());
        assertTrue(sub.alive(ServiceRegistry.Topic.ALGO_STATE));

        // 超过健康超时（ALGO_STATE freq=1Hz，超时 3s）后仍无新数据 → 失活
        long timeoutMs = ServiceRegistry.INSTANCE.healthTimeoutMs(ServiceRegistry.Topic.ALGO_STATE);
        assertTrue("健康超时应为 3000ms", timeoutMs == 3000L);

        // 用一个"很久之后"的 nowNanos 调用 update，模拟距上次收消息已超时
        long futureTs = System.nanoTime() + (timeoutMs + 100) * 1_000_000L;
        sub.update(futureTs);
        assertFalse("超过超时未更新应失活", sub.alive(ServiceRegistry.Topic.ALGO_STATE));
    }

    @Test
    public void testAliveAfterNewDataFollowingTimeout() {
        BusSubscriber sub = subscriber(ServiceRegistry.Topic.ALGO_STATE);

        mPublisher.publish(ServiceRegistry.Topic.ALGO_STATE, "ok");
        sub.update(System.nanoTime());
        assertTrue(sub.alive(ServiceRegistry.Topic.ALGO_STATE));

        // 模拟超时失活：很久之后 update，无新消息
        long timeoutMs = ServiceRegistry.INSTANCE.healthTimeoutMs(ServiceRegistry.Topic.ALGO_STATE);
        long futureTs = System.nanoTime() + (timeoutMs + 100) * 1_000_000L;
        sub.update(futureTs);
        assertFalse(sub.alive(ServiceRegistry.Topic.ALGO_STATE));

        // 新数据到来 → 恢复 alive
        mPublisher.publish(ServiceRegistry.Topic.ALGO_STATE, "ok2");
        sub.update(System.nanoTime());
        assertTrue(sub.alive(ServiceRegistry.Topic.ALGO_STATE));
        assertEquals("ok2", sub.latestPayload(ServiceRegistry.Topic.ALGO_STATE, String.class));
    }

    @Test
    public void testMultipleMessagesKeepsLatest() {
        BusSubscriber sub = subscriber(ServiceRegistry.Topic.FRAME_OVERLAY);

        mPublisher.publish(ServiceRegistry.Topic.FRAME_OVERLAY, "overlay1");
        mPublisher.publish(ServiceRegistry.Topic.FRAME_OVERLAY, "overlay2");
        mPublisher.publish(ServiceRegistry.Topic.FRAME_OVERLAY, "overlay3");

        sub.update();

        // 轮询后应保留最新一条
        assertEquals("overlay3", sub.latestPayload(ServiceRegistry.Topic.FRAME_OVERLAY, String.class));
    }

    @Test
    public void testCloseReleasesReaders() {
        BusSubscriber sub = subscriber(ServiceRegistry.Topic.ALGO_RESULT);
        mPublisher.publish(ServiceRegistry.Topic.ALGO_RESULT, "x");
        sub.update();
        assertTrue(sub.alive(ServiceRegistry.Topic.ALGO_RESULT));

        sub.close();
        // 关闭后不再 alive
        sub.update();
        assertFalse(sub.alive(ServiceRegistry.Topic.ALGO_RESULT));
    }

    @Test
    public void testUnsubscribedTopicNotAlive() {
        BusSubscriber sub = subscriber(ServiceRegistry.Topic.ALGO_RESULT);
        assertFalse("未订阅的 topic 不应 alive",
                sub.alive(ServiceRegistry.Topic.VEHICLE_SPEED));
    }

    @Test
    public void testUpdatedFlag() {
        BusSubscriber sub = subscriber(ServiceRegistry.Topic.ALGO_RESULT);

        mPublisher.publish(ServiceRegistry.Topic.ALGO_RESULT, "face1");
        sub.update();
        assertTrue("收到新消息后 updated 应为 true", sub.updated(ServiceRegistry.Topic.ALGO_RESULT));

        // 无新消息再 update → updated 变为 false
        sub.update();
        assertFalse("无新消息时 updated 应为 false", sub.updated(ServiceRegistry.Topic.ALGO_RESULT));

        // 新消息到达 → updated 恢复 true
        mPublisher.publish(ServiceRegistry.Topic.ALGO_RESULT, "face2");
        sub.update();
        assertTrue(sub.updated(ServiceRegistry.Topic.ALGO_RESULT));
    }

    @Test
    public void testPublisherSequenceIncrement() {
        long s1 = mPublisher.publish(ServiceRegistry.Topic.VEHICLE_SPEED, 10f);
        long s2 = mPublisher.publish(ServiceRegistry.Topic.VEHICLE_SPEED, 20f);
        long s3 = mPublisher.publish(ServiceRegistry.Topic.VEHICLE_SPEED, 30f);

        assertEquals(1L, s1);
        assertEquals(2L, s2);
        assertEquals(3L, s3);
        assertEquals(3L, mPublisher.publishedCount(ServiceRegistry.Topic.VEHICLE_SPEED));
    }
}
