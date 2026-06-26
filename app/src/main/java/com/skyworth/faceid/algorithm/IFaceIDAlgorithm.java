/*
 * Copyright (C) 2024 Skyworth. All rights reserved.
 */

package com.skyworth.faceid.algorithm;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.RectF;

import java.util.List;
import java.util.Map;

/**
 * Face ID 算法抽象接口。
 *
 * <p>算法团队需实现此接口，业务层通过此接口与算法完全解耦。
 *
 * <p>数据流约定：
 * <ul>
 *   <li>输入：原始帧数据 (YUV/NV21/RGBA) + 宽高 + 格式标识</li>
 *   <li>输出：{@link FaceIDResult} 包含 faceId、置信度、人脸框、处理后帧数据</li>
 * </ul>
 *
 * <p>线程安全：实现类需保证 {@link #processFrame} 的线程安全。
 */
public interface IFaceIDAlgorithm {

    /**
     * 算法处理结果。
     *
     * <p>包含 Face ID 识别结果的全部信息，用于预览渲染和 UI 展示。
     */
    final class FaceIDResult {

        /** Face ID 唯一标识（空字符串表示未检测到人脸）。 */
        private final String mFaceId;

        /** 置信度 (0.0 ~ 1.0)。 */
        private final float mConfidence;

        /** 人脸框坐标（用于画框），null 表示未检测到人脸。 */
        private final RectF mFaceRect;

        /** 处理后的帧数据（算法绘制人脸框后的数据，原样返回时表示未处理）。 */
        private final byte[] mProcessedData;

        /** 人脸关键点（可选），null 表示未提供。 */
        private final List<PointF> mLandmarks;

        /**
         * 构造算法处理结果。
         *
         * @param faceId        Face ID 唯一标识，空字符串表示未检测到
         * @param confidence    置信度，范围 0.0 ~ 1.0
         * @param faceRect      人脸框坐标，未检测到时为 null
         * @param processedData 处理后的帧数据（可直接用于渲染）
         * @param landmarks     人脸关键点，未提供时为 null
         */
        public FaceIDResult(String faceId, float confidence, RectF faceRect,
                            byte[] processedData, List<PointF> landmarks) {
            mFaceId = faceId != null ? faceId : "";
            mConfidence = Math.max(0.0f, Math.min(1.0f, confidence));
            mFaceRect = faceRect;
            // 防御性拷贝：防止外部修改影响内部数据
            mProcessedData = processedData != null ? processedData.clone() : new byte[0];
            mLandmarks = landmarks;
        }

        public String getFaceId() {
            return mFaceId;
        }

        public float getConfidence() {
            return mConfidence;
        }

        public RectF getFaceRect() {
            return mFaceRect;
        }

        public byte[] getProcessedData() {
            return mProcessedData;
        }

        public List<PointF> getLandmarks() {
            return mLandmarks;
        }
    }

    /**
     * 初始化算法。
     *
     * <p>加载模型文件、初始化人脸检测器和特征提取器等资源。
     *
     * @param context Android 上下文
     * @param config  算法配置参数（模型路径、阈值等），不可为 null
     * @return 初始化成功返回 true，失败返回 false
     */
    boolean initialize(Context context, Map<String, Object> config);

    /**
     * 处理单帧数据。
     *
     * <p>输入原始帧数据，返回包含 Face ID 识别结果和已绘制人脸框的帧数据。
     *
     * @param frameData 原始帧数据 (YUV / NV21 / RGBA)
     * @param width     图像宽度（像素）
     * @param height    图像高度（像素）
     * @param format    图像格式标识（预留，暂传 0）
     * @return 算法处理结果，不会返回 null
     */
    FaceIDResult processFrame(byte[] frameData, int width, int height, int format);

    /**
     * 释放算法资源。
     *
     * <p>释放 native 模型、关闭文件句柄等。调用后算法实例不可再用。
     */
    void release();
}
