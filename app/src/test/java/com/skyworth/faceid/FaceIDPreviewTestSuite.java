package com.skyworth.faceid;

import com.skyworth.faceid.algorithm.IFaceIDAlgorithmTest;
import com.skyworth.faceid.signal.DistractionStateMachineTest;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;

/**
 * Face ID 预览项目单元测试套件
 *
 * 多进程架构（算法进程/渲染进程彻底分离）后，仅保留仍在用的模块测试：
 * - 算法接口：IFaceIDAlgorithmTest
 * - 分心状态机：DistractionStateMachineTest
 * - 共享内存队列：ShmQueueTest（Kotlin）
 * - 算法结果序列化：AlgorithmResultTest（Kotlin）
 * - 能力模块枚举：CapabilityModuleTest（Kotlin，FACEP-011）
 * - 能力模块数据序列化：ModuleDataTest（Kotlin，FACEP-011 阶段 B）
 */
@RunWith(Suite.class)
@Suite.SuiteClasses({
        IFaceIDAlgorithmTest.class,
        DistractionStateMachineTest.class,
        com.skyworth.faceid.bus.ShmQueueTest.class,
        com.skyworth.faceid.shmtest.AlgorithmResultTest.class,
        com.skyworth.faceid.shmtest.CapabilityModuleTest.class,
        com.skyworth.faceid.shmtest.ModuleDataTest.class
})
public class FaceIDPreviewTestSuite {
}
