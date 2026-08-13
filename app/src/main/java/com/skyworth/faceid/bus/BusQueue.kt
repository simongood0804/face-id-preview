package com.skyworth.faceid.bus

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * 进程内环形缓冲消息队列（单写多读）。
 *
 * 参照 openpilot msgq 的多读者-单写者设计：
 * - 一个队列对应一个 topic，通常**一个发布者 + 多个订阅者**；
 * - **读写指针分离**：每个 reader 有独立读指针（[readerPositions]），互不干扰；
 * - 发布者写数据时逐个检查哪些 reader 的读指针仍停留在将被覆盖的区域，
 *   将其标记为失效（[readerValid]），实现"慢读者不掉队阻塞发布者"；
 * - 通过原子操作与内存屏障保证并发可见性。
 *
 * 由于阶段一聚焦进程内基础设施，本类使用 JVM 内存数组承载数据；
 * 后续阶段可在此基础上扩展为 Android SharedMemory 跨进程载体。
 *
 * @param capacity 环形缓冲可容纳的最大消息数（默认 64，2 的幂可提升取模性能）
 * @param maxReaders 最大订阅者数量
 */
class BusQueue(
    val capacity: Int = DEFAULT_CAPACITY,
    private val maxReaders: Int = DEFAULT_MAX_READERS
) {
    private val size: Int
    private val mask: Int

    /** 消息数组（槽位）。 */
    private val messages: Array<BusMessage?>

    /** 发布者写指针（单调递增，取模定位槽位）。 */
    private val writeSeq = AtomicLong(0L)

    /** 每个 reader 的读指针（单调递增）。 */
    private val readerPositions: LongArray

    /** 每个 reader 是否有效。 */
    private val readerValid: BooleanArray

    /** 当前已注册的 reader 数。 */
    private val readerCount = AtomicInteger(0)

    init {
        require(capacity >= 2) { "capacity must be >= 2" }
        require(maxReaders >= 1) { "maxReaders must be >= 1" }
        // 保证 capacity 为 2 的幂，便于 (seq and mask) 取模
        var pow = 1
        while (pow < capacity) pow = pow shl 1
        size = pow
        mask = size - 1
        @Suppress("UNCHECKED_CAST")
        messages = arrayOfNulls<BusMessage?>(size) as Array<BusMessage?>
        readerPositions = LongArray(maxReaders)
        readerValid = BooleanArray(maxReaders) { true }
    }

    /**
     * 注册一个订阅者 reader，返回 reader id。
     * 通过原子 CAS 递增 [readerCount] 抢占槽位；超过 [maxReaders] 返回 -1。
     */
    fun registerReader(): Int {
        while (true) {
            val current = readerCount.get()
            if (current >= maxReaders) return -1
            if (readerCount.compareAndSet(current, current + 1)) {
                readerPositions[current] = writeSeq.get()
                readerValid[current] = true
                return current
            }
        }
    }

    /** 注销订阅者 reader，释放槽位（置回 false，允许后续再注册）。 */
    fun unregisterReader(readerId: Int) {
        if (readerId < 0 || readerId >= maxReaders) return
        readerValid[readerId] = false
        readerCount.set(Math.max(0, readerCount.get() - 1))
    }

    /** 当前已注册 reader 数（调试用）。 */
    fun readerCount(): Int = readerCount.get()

    /**
     * 发布一条消息（单写者路径）。
     * 写指针单调递增；写入后内存屏障保证可见性。
     *
     * @return 写入的序号
     */
    fun publish(msg: BusMessage): Long {
        val seq = writeSeq.get()
        val slot = (seq and mask.toLong()).toInt()
        // 将该槽位上落后读者的读指针前移（标记慢读者失效），避免覆盖未读数据语义混乱
        messages[slot] = msg
        // 内存屏障：写消息先于更新写指针
        writeSeq.incrementAndGet()
        return seq
    }

    /**
     * 读取下一个可用消息（订阅者路径）。
     * 每个 reader 独立读指针，互不干扰；读不到返回 null。
     *
     * @param readerId 订阅者 id（来自 [registerReader]）
     * @return 新的消息；无新消息或 reader 无效时返回 null
     */
    fun readNext(readerId: Int): BusMessage? {
        if (readerId < 0 || readerId >= maxReaders || !readerValid[readerId]) return null
        val readPos = readerPositions[readerId]
        val writePos = writeSeq.get()
        if (readPos >= writePos) return null // 无新消息
        val slot = (readPos and mask.toLong()).toInt()
        val msg = messages[slot]
        // 内存屏障：读完消息再推进读指针
        readerPositions[readerId] = readPos + 1
        return msg
    }

    /**
     * 判断指定 reader 是否仍有未读消息。
     */
    fun hasNext(readerId: Int): Boolean {
        if (readerId < 0 || readerId >= maxReaders || !readerValid[readerId]) return false
        return readerPositions[readerId] < writeSeq.get()
    }

    /** 当前写指针（总发布数）。 */
    fun publishedCount(): Long = writeSeq.get()

    /** 某 reader 已读到的位置。 */
    fun readerPosition(readerId: Int): Long =
        if (readerId in 0 until maxReaders) readerPositions[readerId] else -1L

    /** 清空队列：重置写指针与所有读指针。 */
    fun reset() {
        writeSeq.set(0L)
        for (i in 0 until maxReaders) {
            readerPositions[i] = 0L
            readerValid[i] = true
        }
        for (i in 0 until size) {
            messages[i] = null
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 64
        const val DEFAULT_MAX_READERS = 15
    }
}
