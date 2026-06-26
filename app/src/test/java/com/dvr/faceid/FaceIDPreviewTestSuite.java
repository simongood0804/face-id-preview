package com.skyworth.faceid;

import com.skyworth.faceid.algorithm.IFaceIDAlgorithmTest;
import com.skyworth.faceid.pipeline.BufferManagerTest;
import com.skyworth.faceid.pipeline.PipelineConfigTest;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * Face ID 预览项目单元测试套件
 *
 * 统一运行所有模块的单元测试。
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
        PipelineConfigTest.class,
        IFaceIDAlgorithmTest.class,
        BufferManagerTest.class
})
public class FaceIDPreviewTestSuite {
    // 套件类，无需实现方法
}
