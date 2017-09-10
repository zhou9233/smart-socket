package org.smartboot.socket.transport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.smartboot.socket.service.SmartFilter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AIO传输层会话
 * Created by seer on 2017/6/29.
 */
public class AioSession<T> {
    private static final Logger logger = LogManager.getLogger(AioSession.class);
    /**
     * Session ID生成器
     */
    private static final AtomicInteger NEXT_ID = new AtomicInteger(0);

    /**
     * 唯一标识
     */
    private final int sessionId = NEXT_ID.getAndIncrement();


    /**
     * 会话当前状态
     */
    private volatile SessionStatus status = SessionStatus.SESSION_STATUS_ENABLED;

    /**
     * 会话属性,延迟创建以减少内存消耗
     */
    private Map<String, Object> attribute;

    /**
     * 响应消息缓存队列
     */
    private ArrayBlockingQueue<ByteBuffer> writeCacheQueue;

    /**
     * Channel读写操作回调Handler
     */
    private AioCompletionHandler aioCompletionHandler;
    /**
     * 读回调附件
     */
    private Attachment readAttach = new Attachment(true);
    /**
     * 写回调附件
     */
    private Attachment writeAttach = new Attachment(false);


    /**
     * 数据read限流标志,仅服务端需要进行限流
     */
    private AtomicBoolean serverFlowLimit;

    /**
     * 底层通信channel对象
     */
    private AsynchronousSocketChannel channel;


    /**
     * 输出信号量
     */
    private Semaphore semaphore = new Semaphore(1);


    private IoServerConfig<T> ioServerConfig;

    AioSession(AsynchronousSocketChannel channel, IoServerConfig<T> config, AioCompletionHandler aioCompletionHandler) {
        this.channel = channel;
        this.aioCompletionHandler = aioCompletionHandler;
        this.serverFlowLimit = config.isServer() ? new AtomicBoolean(false) : null;
        this.writeCacheQueue = new ArrayBlockingQueue<ByteBuffer>(config.getWriteQueueSize());
        this.ioServerConfig = config;
        config.getProcessor().registerAioSession(this);//往处理中注册当前对象
        readAttach.setBuffer(ByteBuffer.allocate(config.getReadBufferSize()));
        readFromChannel();//注册消息读事件
    }

    /**
     * 触发AIO的写操作,
     * <p>需要调用控制同步</p>
     */
    void writeToChannel() {
        if (isInvalid()) {
            close();
            logger.warn("end write because of aioSession's status is" + status);
            return;
        }
        ByteBuffer writeBuffer = writeAttach.buffer;
        ByteBuffer nextBuffer = writeCacheQueue.peek();//为null说明队列已空
        if (writeBuffer == null && nextBuffer == null) {
            semaphore.release();
            if (writeCacheQueue.size() > 0 && semaphore.tryAcquire()) {
                writeToChannel();
            }
            return;
        }
        if (writeBuffer == null) {
            //对缓存中的数据进行压缩处理再输出
            Iterator<ByteBuffer> iterable = writeCacheQueue.iterator();
            int totalSize = 0;
            while (iterable.hasNext()) {
                totalSize += iterable.next().remaining();
                if (totalSize >= 32 * 1024) {
                    break;
                }
            }
            writeBuffer = ByteBuffer.allocate(totalSize);
            while (writeBuffer.hasRemaining()) {
                writeBuffer.put(writeCacheQueue.poll());
            }
            writeBuffer.flip();
        } else if (nextBuffer != null && nextBuffer.remaining() <= (writeBuffer.capacity() - writeBuffer.remaining())) {
            writeBuffer.compact();
            do {
                writeBuffer.put(writeCacheQueue.poll());
            }
            while ((nextBuffer = writeCacheQueue.peek()) != null && nextBuffer.remaining() <= writeBuffer.remaining());
            writeBuffer.flip();
        }

        writeAttach.setBuffer(writeBuffer);
        channel.write(writeBuffer, writeAttach, aioCompletionHandler);
    }

    /**
     * 如果存在流控并符合释放条件，则触发读操作
     */
    void tryReleaseFlowLimit() {
        if (serverFlowLimit != null && serverFlowLimit.get() && writeCacheQueue.size() < ioServerConfig.getReleaseLine()) {
            serverFlowLimit.set(false);
            channel.read(readAttach.getBuffer(), readAttach, aioCompletionHandler);
        }

    }

    public void write(final ByteBuffer buffer) throws IOException {
        if (isInvalid()) {
            return;
        }
        buffer.flip();
        try {
            //正常读取
            writeCacheQueue.put(buffer);
        } catch (InterruptedException e) {
            logger.error(e);
        }
        if (semaphore.tryAcquire()) {
            writeToChannel();
        }
    }

    public final void close() {
        close(true);
    }


    /**
     * * 是否立即关闭会话
     *
     * @param immediate true:立即关闭,false:响应消息发送完后关闭
     */
    public void close(boolean immediate) {
        if (immediate) {
            try {
                channel.close();
                logger.debug("close connection:" + channel);
            } catch (IOException e) {
                logger.catching(e);
            }
            status = SessionStatus.SESSION_STATUS_CLOSED;
        } else {
            status = SessionStatus.SESSION_STATUS_CLOSING;
            if (writeCacheQueue.isEmpty() && semaphore.tryAcquire()) {
                close(true);
                semaphore.release();
            }
        }
    }


    @SuppressWarnings("unchecked")
    public final <T1> T1 getAttribute(String key) {
        return attribute == null ? null : (T1) attribute.get(key);
    }

    /**
     * 获取当前Session的唯一标识
     *
     * @return
     */
    public final int getSessionID() {
        return sessionId;
    }

    /**
     * 当前会话是否已失效
     */
    public boolean isInvalid() {
        return status != SessionStatus.SESSION_STATUS_ENABLED;
    }

    /**
     * 触发通道的读操作，当发现存在严重消息积压时,会触发流控
     */
    void readFromChannel() {
        ByteBuffer readBuffer = readAttach.getBuffer();
        readBuffer.flip();
        // 将从管道流中读取到的字节数据添加至当前会话中以便进行消息解析
        T dataEntry;
        int remain = 0;
        while ((remain = readBuffer.remaining()) > 0 && (dataEntry = ioServerConfig.getProtocol().decode(readBuffer, this)) != null) {
            receive0(this, dataEntry, remain - readBuffer.remaining());
        }
        //数据读取完毕
        if (readBuffer.remaining() == 0) {
            readBuffer.clear();
        } else if (readBuffer.position() > 0) {// 仅当发生数据读取时调用compact,减少内存拷贝
            readBuffer.compact();
        } else {
            readBuffer.position(readBuffer.limit());
            readBuffer.limit(readBuffer.capacity());
        }

        //触发流控
        if (serverFlowLimit != null && writeCacheQueue.size() > ioServerConfig.getFlowLimitLine()) {
            serverFlowLimit.set(true);
        } else {
            channel.read(readBuffer, readAttach, aioCompletionHandler);
        }
    }

    public final void removeAttribute(String key) {
        if (attribute != null) {
            attribute.remove(key);
        }
    }


    public final void setAttribute(String key, Object value) {
        if (attribute == null) {
            attribute = new HashMap<String, Object>();
        }
        attribute.put(key, value);
    }


    public final void write(T t) throws IOException {
        write(ioServerConfig.getProtocol().encode(t, this));
    }

    /**
     * 接收并处理消息
     *
     * @param session
     * @param dataEntry
     * @param readSize
     */
    private void receive0(AioSession<T> session, T dataEntry, int readSize) {
        if (ioServerConfig.getFilters() == null) {
            try {
                ioServerConfig.getProcessor().process(session, dataEntry);
            } catch (Exception e) {
                logger.catching(e);
            }
            return;
        }

        // 接收到的消息进行预处理
        for (SmartFilter<T> h : ioServerConfig.getFilters()) {
            h.readFilter(session, dataEntry, readSize);
        }
        try {
            for (SmartFilter<T> h : ioServerConfig.getFilters()) {
                h.processFilter(session, dataEntry);
            }
            ioServerConfig.getProcessor().process(session, dataEntry);
        } catch (Exception e) {
            logger.catching(e);
            for (SmartFilter<T> h : ioServerConfig.getFilters()) {
                h.processFailHandler(session, dataEntry, e);
            }
        }
    }

    class Attachment {
        private ByteBuffer buffer;
        /**
         * true:read,false:write
         */
        private final boolean read;

        public Attachment(boolean optType) {
            this.read = optType;
        }

        public AioSession getAioSession() {
            return AioSession.this;
        }


        public ByteBuffer getBuffer() {
            return buffer;
        }

        public void setBuffer(ByteBuffer buffer) {
            this.buffer = buffer;
        }

        public boolean isRead() {
            return read;
        }
    }
}
