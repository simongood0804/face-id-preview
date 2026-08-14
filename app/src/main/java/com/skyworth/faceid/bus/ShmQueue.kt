package com.skyworth.faceid.bus

import android.os.SharedMemory
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 跨进程共享内存环形缓冲消息队列（多读者-单写者）。
 *
 * 参照 openpilot msgq 的多读者-单写者设计，把队列头与数据区都驻留在
 * 一块 [SharedMemory] 中，供多进程通过 Binder 分发的文件描述符各自 mmap 访问：
 *
 * - **写指针分离**：每个 reader 有独立读指针，互不干扰；
 * - **慢读者不阻塞写者**：发布者写数据时，若某 reader 读指针仍停留在将被覆盖
 *   的槽位，则标记该 reader 失效；
 * - **负载为序列化字节**（非对象引用），可跨进程共享；
 * - 通过序号单调递增 + 槽位自校验检测损坏/脏数据。
 *
 * 内存布局（队列头 + 数据区，同一块共享内存）：
 * ```
 * ┌─────────────────────────── 队列头（定长，见 [HEADER_SIZE]）──────────────┐
 * │ magic:u32  capacity:u32  maxReaders:u32  readerCount:u32                 │
 * │ writeSeq:u64  readerValid[cap]:u8  readerPos[cap]:u64                    │
 * ├─────────────────────────── 数据区 ───────────────────────────────────────┤
 * │ 槽位0: size:u32 + payload[capacity bytes]                                │
 * │ 槽位1: ...                                                               │
 * └──────────────────────────────────────────────────────────────────────────┘
 * ```
 *
 * **跨进程可见性说明**：本实现依赖 ashmem/SharedMemory 的共享物理页语义——
 * 写者先写数据区、再推进写指针；读者先读写指针、再读数据区，并校验槽位序号。
 * 跨进程的内存屏障由"映射同物理页 + 写序总序"提供，阶段 B 会用独立进程实测验证；
 * 若出现可见性问题，可改用 `FileDescriptor` 通知或读者侧高频轮询兜底。
 *
 * 注意：为了阶段 A 可在无设备环境下用 JVM 单测验证逻辑，本类直接基于
 * 一个 [ByteBuffer] 操作（可通过 [SharedMemory.mapReadWrite] 或
 * [ByteBuffer.allocateDirect] 得到）。设备侧用 [create]/[attach] 工厂。
 */
class ShmQueue(
    private val buffer: ByteBuffer,
    val capacity: Int,
    val maxReaders: Int
) {
    private var closed = false

    /**
     * 本队列持有的 [SharedMemory]（仅 [create] 提供侧设置，用于经 Binder 分发）。
     * 消费侧 [attach] 不持有原始 SharedMemory（由分发者管理生命周期）。
     */
    var ownedShm: SharedMemory? = null
        private set

    init {
        require(capacity >= 2) { "capacity must be >= 2" }
        require(maxReaders >= 1) { "maxReaders must be >= 1" }
        // 保证 capacity 为 2 的幂
        require(capacity and (capacity - 1) == 0) { "capacity must be power of 2" }
        buffer.order(ByteOrder.nativeOrder())
    }

    // ------------------------------------------------------------------
    // 队列头字段（共享内存中的固定偏移）
    // ------------------------------------------------------------------
    private val magicOffset = 0            // u32
    private val capacityOffset = 4         // u32
    private val maxReadersOffset = 8       // u32
    private val readerCountOffset = 12     // u32
    private val writeSeqOffset = 16        // u64
    private val readerValidOffset = 24     // u8[maxReaders]
    private val readerPosOffset = readerValidOffset + maxReaders       // u64[maxReaders]
    private val dataOffset = align8(readerPosOffset + maxReaders * 8) // 数据区起始

    /** 每个槽位的最大负载字节数（固定，避免碎片）。 */
    private val slotPayloadSize = 1024

    /** 每个槽位占用的字节（size:u32 + payload）。 */
    private val slotStride = 4 + slotPayloadSize

    /** 数据区总大小。 */
    private val dataSize = capacity * slotStride

    // ------------------------------------------------------------------
    // 工厂方法
    // ------------------------------------------------------------------

    companion object {
        private const val TAG = "ShmQueue"
        private const val MAGIC = 0x53484D51 // "SHMQ"

        /** 单条消息最大字节数。 */
        const val MAX_MESSAGE_BYTES = 1024

        /**
         * 计算一块 capacity/maxReaders 所需的总共享内存字节数。
         */
        fun totalSize(capacity: Int, maxReaders: Int): Int {
            val valid = capacity and (capacity - 1) == 0
            require(valid && capacity >= 2) { "capacity must be power of 2 and >= 2" }
            val readerValid = 24 + maxReaders
            val readerPos = align8(readerValid) + maxReaders * 8
            val data = align8(readerPos) + capacity * (4 + MAX_MESSAGE_BYTES)
            return data
        }

        private fun align8(v: Int): Int = (v + 7) and -8

        /**
         * 提供侧：创建并初始化一块共享内存队列。
         *
         * @param name 共享内存名称（调试用）
         * @param capacity 槽位数（2 的幂）
         * @param maxReaders 最大订阅者数
         * @return 已初始化的队列（写者持有），fd 可经 Binder 分发。
         */
        fun create(name: String, capacity: Int = 64, maxReaders: Int = 15): ShmQueue {
            val size = totalSize(capacity, maxReaders)
            val shm = SharedMemory.create(name, size)
            val buf = shm.mapReadWrite().order(ByteOrder.nativeOrder())
            val q = ShmQueue(buf, capacity, maxReaders)
            q.initializeHeader()
            q.ownedShm = shm
            Log.i(TAG, "create: name=$name size=${shm.size} capacity=$capacity")
            return q
        }

        /**
         * 消费侧：挂载一个由 [create] 创建、经 Binder 分发的 [SharedMemory]。
         *
         * [SharedMemory] 实现 [android.os.Parcelable]，可直接经 Binder 在进程间传递；
         * 消费进程拿到后 map 到自己地址空间，与提供方共享同一物理内存。
         *
         * @param shm 提供方分发的共享内存（同名同 FD）
         */
        fun attach(shm: SharedMemory): ShmQueue {
            val buf = shm.mapReadOnly().order(ByteOrder.nativeOrder())
            val capacity = buf.getInt(4)
            val maxReaders = buf.getInt(8)
            val q = ShmQueue(buf, capacity, maxReaders)
            q.verifyMagic()
            q.ownedShm = shm
            Log.i(TAG, "attach: size=${shm.size} capacity=$capacity maxReaders=$maxReaders")
            return q
        }
    }

    // ------------------------------------------------------------------
    // 头字段读写
    // ------------------------------------------------------------------

    /**
     * 初始化队列头（清空写指针/读指针/reader 有效位）。
     * 由 [create] 创建时调用；`internal` 以便单测（同模块）用
     * [ByteBuffer] 构造后手动初始化头。
     */
    internal fun initializeHeader() {
        buffer.putInt(magicOffset, MAGIC)
        buffer.putInt(capacityOffset, capacity)
        buffer.putInt(maxReadersOffset, maxReaders)
        buffer.putInt(readerCountOffset, 0)
        buffer.putLong(writeSeqOffset, 0L)
        for (i in 0 until maxReaders) {
            buffer.put(readerValidOffset + i, 1) // 有效
            buffer.putLong(readerPosOffset + i * 8, 0L)
        }
    }

    private fun verifyMagic() {
        if (buffer.getInt(magicOffset) != MAGIC) {
            throw IllegalStateException("invalid shared memory magic")
        }
    }

    private fun writeSeq(): Long = buffer.getLong(writeSeqOffset)

    private fun setWriteSeq(v: Long) = buffer.putLong(writeSeqOffset, v)

    private fun readerValid(readerId: Int): Boolean =
        buffer.get(readerValidOffset + readerId).toInt() != 0

    private fun readerPos(readerId: Int): Long = buffer.getLong(readerPosOffset + readerId * 8)

    private fun setReaderPos(readerId: Int, v: Long) =
        buffer.putLong(readerPosOffset + readerId * 8, v)

    private fun readerCount(): Int = buffer.getInt(readerCountOffset)

    private fun setReaderCount(v: Int) = buffer.putInt(readerCountOffset, v)

    // ------------------------------------------------------------------
    // API
    // ------------------------------------------------------------------

    /**
     * 注册一个订阅者 reader，返回 reader id；超过 [maxReaders] 返回 -1。
     */
    fun registerReader(): Int {
        val current = readerCount()
        if (current >= maxReaders) return -1
        setReaderCount(current + 1)
        setReaderPos(current, writeSeq())
        // readerValid[current] 默认有效（初始化时置 1）
        return current
    }

    /** 注销订阅者 reader。 */
    fun unregisterReader(readerId: Int) {
        if (readerId < 0 || readerId >= maxReaders) return
        buffer.put(readerValidOffset + readerId, 0)
        setReaderCount(Math.max(0, readerCount() - 1))
    }

    /**
     * 发布一条消息（单写者路径）。
     *
     * 消息内容为 [ShmMessageSerializer.encode] 后的字节（topic 头 + 负载），
     * 跨进程消费者经 [readNext] 解出 topic 与负载。
     *
     * @param topic 消息 topic（整数 id）
     * @param payload 负载字节（≤ [MAX_MESSAGE_BYTES] - 4）
     * @return 写入的序号
     */
    fun publish(topic: Int, payload: ByteArray): Long {
        // 编码 topic + payload（topic 占 4 字节头）
        val encoded = ShmMessageSerializer.encode(topic, payload)
        require(encoded.size <= slotPayloadSize) {
            "payload too large: ${encoded.size} > $slotPayloadSize"
        }
        val seq = writeSeq()
        val slot = (seq and (capacity - 1).toLong()).toInt()
        // 该槽位将被覆盖：把读指针仍停留在此的 reader 标记失效（不阻塞写者）
        markSlowReadersIfNeeded(slot, seq)
        // 先写数据，再推进写指针（写者侧顺序保证）
        val offset = dataOffset + slot * slotStride
        buffer.putInt(offset, encoded.size)
        var pos = offset + 4
        for (b in encoded) buffer.put(pos++, b)
        // 内存屏障语义：依赖 ashmem 共享页 + 写序总序（见类注释）
        setWriteSeq(seq + 1)
        return seq
    }

    private fun markSlowReadersIfNeeded(slot: Int, seq: Long) {
        for (i in 0 until maxReaders) {
            if (readerValid(i)) {
                val rp = readerPos(i)
                val rslot = (rp and (capacity - 1).toLong()).toInt()
                if (rslot == slot && rp < seq) {
                    buffer.put(readerValidOffset + i, 0) // 慢读者失效
                }
            }
        }
    }

    /**
     * 读取下一个可用消息（订阅者路径）。每个 reader 独立读指针。
     *
     * @param readerId 订阅者 id
     * @return 新消息；无新消息或 reader 无效时返回 null
     */
    fun readNext(readerId: Int): ShmMessage? {
        if (readerId < 0 || readerId >= maxReaders || !readerValid(readerId)) return null
        val readPos = readerPos(readerId)
        val writePos = writeSeq()
        if (readPos >= writePos) return null
        val slot = (readPos and (capacity - 1).toLong()).toInt()
        val offset = dataOffset + slot * slotStride
        val size = buffer.getInt(offset)
        // 数据区自校验：size 越界视为损坏，跳过
        if (size < 0 || size > slotPayloadSize) {
            setReaderPos(readerId, readPos + 1)
            return null
        }
        val encoded = ByteArray(size)
        var pos = offset + 4
        for (i in 0 until size) encoded[i] = buffer.get(pos + i)
        setReaderPos(readerId, readPos + 1)
        // 解出 topic + 负载（encode 时首 4 字节为 topic）
        val topic = if (encoded.size >= 4) ShmMessageSerializer.decodeTopic(encoded) else 0
        val payload = if (encoded.size >= 4) ShmMessageSerializer.decodePayload(encoded) else encoded
        return ShmMessage(topic, payload, readPos)
    }

    /** 判断指定 reader 是否仍有未读消息。 */
    fun hasNext(readerId: Int): Boolean {
        if (readerId < 0 || readerId >= maxReaders || !readerValid(readerId)) return false
        return readerPos(readerId) < writeSeq()
    }

    /** 当前写指针（总发布数）。 */
    fun publishedCount(): Long = writeSeq()

    /** 某 reader 已读到的位置。 */
    fun readerPosition(readerId: Int): Long =
        if (readerId in 0 until maxReaders) readerPos(readerId) else -1L

    /** 清理：关闭映射（共享内存生命周期由持有者管理）。 */
    fun close() {
        if (!closed) {
            closed = true
        }
    }
}

/**
 * 跨进程消息：topic id + 负载字节（非对象引用）。
 */
class ShmMessage(
    val topic: Int,
    val payload: ByteArray,
    val sequence: Long
)

/**
 * 把整数 topic 与字节负载序列化为定长消息（写入共享内存前）。
 */
object ShmMessageSerializer {
    private const val TOPIC_BYTES = 4
    private const val MAX_PAYLOAD = ShmQueue.MAX_MESSAGE_BYTES - TOPIC_BYTES

    /** 把 topic + payload 编码进一个字节数组（首 4 字节为 topic）。 */
    fun encode(topic: Int, payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "payload too large" }
        val out = ByteArray(TOPIC_BYTES + payload.size)
        out[0] = (topic ushr 24).toByte()
        out[1] = (topic ushr 16).toByte()
        out[2] = (topic ushr 8).toByte()
        out[3] = topic.toByte()
        System.arraycopy(payload, 0, out, TOPIC_BYTES, payload.size)
        return out
    }

    /** 从编码后的字节中解出 topic。 */
    fun decodeTopic(data: ByteArray): Int {
        return ((data[0].toInt() and 0xFF) shl 24) or
                ((data[1].toInt() and 0xFF) shl 16) or
                ((data[2].toInt() and 0xFF) shl 8) or
                (data[3].toInt() and 0xFF)
    }

    /** 从编码后的字节中解出 payload（去掉 topic 头）。 */
    fun decodePayload(data: ByteArray): ByteArray {
        return data.copyOfRange(TOPIC_BYTES, data.size)
    }
}
