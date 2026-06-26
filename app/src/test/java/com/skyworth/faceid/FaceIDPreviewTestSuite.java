package com.skyworth.faceid;

import com.skyworth.faceid.algorithm.IFaceIDAlgorithmTest;
import com.skyworth.faceid.pipeline.BufferManagerTest;
import com.skyworth.faceid.pipeline.PipelineConfigTest;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * Face ID 预览项目单元测试套件
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
        PipelineConfigTest.class,
        IFaceIDAlgorithmTest.class,
        BufferManagerTest.class
})
public class FaceIDPreviewTestSuite {
}
