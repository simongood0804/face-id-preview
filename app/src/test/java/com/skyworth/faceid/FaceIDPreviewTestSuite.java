package com.skyworth.faceid;

import com.skyworth.faceid.algorithm.FrameProcessingBenchmarkTest;
import com.skyworth.faceid.algorithm.IFaceIDAlgorithmTest;
import com.skyworth.faceid.pipeline.BufferManagerTest;
import com.skyworth.faceid.signal.SignalDispatcherTest;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * Face ID preview project unit test suite (app side).
 *
 * Aggregates tests that remain in the app module, including algorithm interface
 * tests that depend on Robolectric / Android classes. Pure-JVM tests moved to the
 * :algo module (bus / EyeMouth* / PipelineConfig / DistractionStateMachine etc.)
 * run independently there. See FACEP-014.
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
        IFaceIDAlgorithmTest.class,
        FrameProcessingBenchmarkTest.class,
        BufferManagerTest.class,
        SignalDispatcherTest.class
})
public class FaceIDPreviewTestSuite {
}
