package com.skyworth.faceid.bus;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static kotlin.Unit.INSTANCE;

/**
 * HealthMonitor（健康监控器 / Watchdog）单元测试。
 *
 * 验证"单层故障不拖垮其他层"的容错机制：
 * - 健康 topic 不失活；
 * - topic 断流超时 → 触发 onFault 回调；
 * - 单个 topic 故障不影响其他 topic；
 * - 恢复后重新健康；
 * - 故障回调异常不向外传播。
 */
public class HealthMonitorTest {

    private BusHub mHub;
    private BusPublisher mPublisher;

    /** 受监控的 topic 集合（车速 + 算法结果）。 */
    private static final Map<ServiceRegistry.Topic, String> MONITORED = new HashMap<>();
    static {
        MONITORED.put(ServiceRegistry.Topic.VEHICLE_SPEED, "VEHICLE_SPEED_DOWN");
        MONITORED.put(ServiceRegistry.Topic.ALGO_RESULT, "ALGO_RESULT_DOWN");
    }

    @Before
    public void setUp() {
        mHub = new BusHub();
        mPublisher = new BusPublisher(mHub);
    }

    @Test
    public void testHealthyTopicsStayHealthy() {
        HealthMonitor monitor = new HealthMonitor(MONITORED, mHub);

        // 发布两个 topic 并 update → 均健康
        mPublisher.publish(ServiceRegistry.Topic.VEHICLE_SPEED, 50f);
        mPublisher.publish(ServiceRegistry.Topic.ALGO_RESULT, "face1");
        monitor.update(System.nanoTime());

        assertTrue(monitor.healthy(ServiceRegistry.Topic.VEHICLE_SPEED));
        assertTrue(monitor.healthy(ServiceRegistry.Topic.ALGO_RESULT));
        assertFalse(monitor.isFaulted(ServiceRegistry.Topic.VEHICLE_SPEED));
    }

    @Test
    public void testFaultTriggeredOnTopicTimeout() {
        AtomicReference<HealthMonitor.FaultEvent> faultRef = new AtomicReference<>();
        HealthMonitor monitor = new HealthMonitor(MONITORED, mHub);
        monitor.setOnFault((topic, event) -> { faultRef.set(event); return INSTANCE; });

        // 发布一次 → 健康
        mPublisher.publish(ServiceRegistry.Topic.VEHICLE_SPEED, 50f);
        mPublisher.publish(ServiceRegistry.Topic.ALGO_RESULT, "face1");
        monitor.update(System.nanoTime());
        assertTrue(monitor.healthy(ServiceRegistry.Topic.VEHICLE_SPEED));

        // 超过健康超时后 update（无新消息）→ 触发故障回调
        long timeoutMs = ServiceRegistry.INSTANCE.healthTimeoutMs(ServiceRegistry.Topic.VEHICLE_SPEED);
        long futureTs = System.nanoTime() + (timeoutMs + 100) * 1_000_000L;
        monitor.update(futureTs);

        assertTrue("车速 topic 应判定失活", monitor.isFaulted(ServiceRegistry.Topic.VEHICLE_SPEED));
        assertNotNull("应触发故障回调", faultRef.get());
        // onFault 可能被多个 topic 触发，用 lastFault 精确断言 VEHICLE_SPEED 的故障
        HealthMonitor.FaultEvent speedFault = monitor.lastFault(ServiceRegistry.Topic.VEHICLE_SPEED);
        assertNotNull("应记录车速故障", speedFault);
        assertEquals("VEHICLE_SPEED_DOWN", speedFault.getCode());
        assertEquals(ServiceRegistry.Topic.VEHICLE_SPEED.name(), speedFault.getSource());
    }

    @Test
    public void testSingleTopicFaultDoesNotAffectOthers() {
        AtomicReference<ServiceRegistry.Topic> faultTopic = new AtomicReference<>();
        HealthMonitor monitor = new HealthMonitor(MONITORED, mHub);
        monitor.setOnFault((topic, event) -> { faultTopic.set(topic); return INSTANCE; });

        // 只发布 ALGO_RESULT，VEHICLE_SPEED 断流
        mPublisher.publish(ServiceRegistry.Topic.ALGO_RESULT, "face1");
        monitor.update(System.nanoTime());

        // ALGO_RESULT 健康
        assertTrue(monitor.healthy(ServiceRegistry.Topic.ALGO_RESULT));
        // VEHICLE_SPEED 无数据 → 失活（初始即失活，不触发 onFault 状态转变）
        assertTrue("断流 topic 应失活", monitor.isFaulted(ServiceRegistry.Topic.VEHICLE_SPEED));
        // ALGO_RESULT 仍健康，不受影响
        assertTrue(monitor.healthy(ServiceRegistry.Topic.ALGO_RESULT));
    }

    @Test
    public void testRecoveryAfterTimeout() {
        AtomicReference<HealthMonitor.FaultEvent> faultRef = new AtomicReference<>();
        HealthMonitor monitor = new HealthMonitor(MONITORED, mHub);
        monitor.setOnFault((topic, event) -> { faultRef.set(event); return INSTANCE; });

        // 发布 → 健康
        mPublisher.publish(ServiceRegistry.Topic.VEHICLE_SPEED, 50f);
        monitor.update(System.nanoTime());
        assertTrue(monitor.healthy(ServiceRegistry.Topic.VEHICLE_SPEED));

        // 超时 → 失活 + 上报
        long timeoutMs = ServiceRegistry.INSTANCE.healthTimeoutMs(ServiceRegistry.Topic.VEHICLE_SPEED);
        long futureTs = System.nanoTime() + (timeoutMs + 100) * 1_000_000L;
        monitor.update(futureTs);
        assertTrue(monitor.isFaulted(ServiceRegistry.Topic.VEHICLE_SPEED));
        assertNotNull(faultRef.get());

        // 新数据到来 → 恢复健康
        mPublisher.publish(ServiceRegistry.Topic.VEHICLE_SPEED, 60f);
        monitor.update(System.nanoTime());
        assertTrue(monitor.healthy(ServiceRegistry.Topic.VEHICLE_SPEED));
        assertFalse(monitor.isFaulted(ServiceRegistry.Topic.VEHICLE_SPEED));
    }

    @Test
    public void testFaultCallbackExceptionDoesNotPropagate() {
        // 回调抛异常 → update 不应向外传播异常
        HealthMonitor monitor = new HealthMonitor(MONITORED, mHub);
        monitor.setOnFault((topic, event) -> { throw new RuntimeException("callback boom"); });

        mPublisher.publish(ServiceRegistry.Topic.VEHICLE_SPEED, 50f);
        mPublisher.publish(ServiceRegistry.Topic.ALGO_RESULT, "face1");
        monitor.update(System.nanoTime());

        // 触发超时失活 → onFault 抛异常，但 update 不抛
        long timeoutMs = ServiceRegistry.INSTANCE.healthTimeoutMs(ServiceRegistry.Topic.VEHICLE_SPEED);
        long futureTs = System.nanoTime() + (timeoutMs + 100) * 1_000_000L;
        try {
            monitor.update(futureTs);
        } catch (Exception e) {
            fail("onFault 回调异常不应向外传播");
        }
        assertTrue("即使回调异常，也应标记失活", monitor.isFaulted(ServiceRegistry.Topic.VEHICLE_SPEED));
    }

    @Test
    public void testLastFaultRecorded() {
        HealthMonitor monitor = new HealthMonitor(MONITORED, mHub);

        mPublisher.publish(ServiceRegistry.Topic.VEHICLE_SPEED, 50f);
        monitor.update(System.nanoTime());

        long timeoutMs = ServiceRegistry.INSTANCE.healthTimeoutMs(ServiceRegistry.Topic.VEHICLE_SPEED);
        long futureTs = System.nanoTime() + (timeoutMs + 100) * 1_000_000L;
        monitor.update(futureTs);

        HealthMonitor.FaultEvent fault = monitor.lastFault(ServiceRegistry.Topic.VEHICLE_SPEED);
        assertNotNull("应记录最近故障", fault);
        assertEquals("VEHICLE_SPEED_DOWN", fault.getCode());
    }

    @Test
    public void testClose() {
        HealthMonitor monitor = new HealthMonitor(MONITORED, mHub);
        monitor.close();
        // close 后查询不抛异常
        assertFalse(monitor.healthy(ServiceRegistry.Topic.VEHICLE_SPEED));
    }
}
