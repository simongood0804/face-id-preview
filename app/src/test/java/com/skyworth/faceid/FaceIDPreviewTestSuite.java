package com.skyworth.faceid;

import com.skyworth.faceid.algorithm.EyeMouthCalibratorTest;
import com.skyworth.faceid.algorithm.EyeMouthStateEstimatorTest;
import com.skyworth.faceid.algorithm.EyeMouthStateMachineTest;
import com.skyworth.faceid.algorithm.IFaceIDAlgorithmTest;
import com.skyworth.faceid.algorithm.LandmarkIndexMappingTest;
import com.skyworth.faceid.bus.BusQueueTest;
import com.skyworth.faceid.bus.BusSubscriberTest;
import com.skyworth.faceid.bus.HealthMonitorTest;
import com.skyworth.faceid.pipeline.BufferManagerTest;
import com.skyworth.faceid.pipeline.PipelineConfigTest;
import com.skyworth.faceid.signal.DistractionStateMachineTest;
import com.skyworth.faceid.signal.SignalDispatcherTest;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * Face ID 预览项目单元测试套件
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
        PipelineConfigTest.class,
        IFaceIDAlgorithmTest.class,
        LandmarkIndexMappingTest.class,
        EyeMouthStateEstimatorTest.class,
        EyeMouthStateMachineTest.class,
        EyeMouthCalibratorTest.class,
        BufferManagerTest.class,
        BusQueueTest.class,
        BusSubscriberTest.class,
        HealthMonitorTest.class,
        DistractionStateMachineTest.class,
        SignalDispatcherTest.class
})
public class FaceIDPreviewTestSuite {
}
