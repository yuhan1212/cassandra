/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.streaming.async;

import java.io.IOError;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPipeline;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.apache.cassandra.concurrent.DebuggableThreadPoolExecutor;
import org.apache.cassandra.concurrent.NamedThreadFactory;
import org.apache.cassandra.config.Config;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.io.util.DataOutputBufferFixed;
import org.apache.cassandra.io.util.DataOutputStreamPlus;
import org.apache.cassandra.net.AsyncChannelPromise;
import org.apache.cassandra.net.OutboundConnectionSettings;
import org.apache.cassandra.net.AsyncStreamingOutputPlus;
import org.apache.cassandra.streaming.StreamConnectionFactory;
import org.apache.cassandra.streaming.StreamSession;
import org.apache.cassandra.streaming.StreamingMessageSender;
import org.apache.cassandra.streaming.messages.IncomingStreamMessage;
import org.apache.cassandra.streaming.messages.KeepAliveMessage;
import org.apache.cassandra.streaming.messages.OutgoingStreamMessage;
import org.apache.cassandra.streaming.messages.StreamInitMessage;
import org.apache.cassandra.streaming.messages.StreamMessage;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.JVMStabilityInspector;

import static org.apache.cassandra.utils.Clock.Global.nanoTime;

/**
 * Responsible for sending {@link StreamMessage}s to a given peer. We manage an array of netty {@link Channel}s
 * for sending {@link OutgoingStreamMessage} instances; all other {@link StreamMessage} types are sent via
 * a special control channel. The reason for this is to treat those messages carefully and not let them get stuck
 * behind a stream transfer.
 *
 * One of the challenges when sending streams is we might need to delay shipping the stream if:
 *
 * - we've exceeded our network I/O use due to rate limiting (at the cassandra level)
 * - the receiver isn't keeping up, which causes the local TCP socket buffer to not empty, which causes epoll writes to not
 * move any bytes to the socket, which causes buffers to stick around in user-land (a/k/a cassandra) memory.
 *
 * When those conditions occur, it's easy enough to reschedule processing the stream once the resources pick up
 * (we acquire the permits from the rate limiter, or the socket drains). However, we need to ensure that
 * no other messages are submitted to the same channel while the current stream is still being processed.
 */
public class NettyStreamingMessageSender implements StreamingMessageSender
{
    private static final Logger logger = LoggerFactory.getLogger(NettyStreamingMessageSender.class);

    private static final int DEFAULT_MAX_PARALLEL_TRANSFERS = FBUtilities.getAvailableProcessors();
    private static final int MAX_PARALLEL_TRANSFERS = Integer.parseInt(System.getProperty(Config.PROPERTY_PREFIX + "streaming.session.parallelTransfers", Integer.toString(DEFAULT_MAX_PARALLEL_TRANSFERS)));

    private static final long DEFAULT_CLOSE_WAIT_IN_MILLIS = TimeUnit.MINUTES.toMillis(5);

    // a simple mechansim for allowing a degree of fairnes across multiple sessions
    private static final Semaphore fileTransferSemaphore = new Semaphore(DEFAULT_MAX_PARALLEL_TRANSFERS, true);

    private final StreamSession session;
    private final boolean isPreview;
    private final int streamingVersion;
    private final OutboundConnectionSettings template;
    private final StreamConnectionFactory factory;

    private volatile boolean closed;

    /**
     * A special {@link Channel} for sending non-stream streaming messages, basically anything that isn't an
     * {@link OutgoingStreamMessage} (or an {@link IncomingStreamMessage}, but a node doesn't send that, it's only received).
     */
    private volatile Channel controlMessageChannel;

    // note: this really doesn't need to be a LBQ, just something that's thread safe
    private final Collection<ScheduledFuture<?>> channelKeepAlives = new LinkedBlockingQueue<>();

    private final ThreadPoolExecutor fileTransferExecutor;

    /**
     * A mapping of each {@link #fileTransferExecutor} thread to a channel that can be written to (on that thread).
     */
    private final ConcurrentMap<Thread, Channel> threadToChannelMap = new ConcurrentHashMap<>();

    /**
     * A netty channel attribute used to indicate if a channel is currently transferring a stream. This is primarily used
     * to indicate to the {@link KeepAliveTask} if it is safe to send a {@link KeepAliveMessage}, as sending the
     * (application level) keep-alive in the middle of a stream would be bad news.
     */
    @VisibleForTesting
    static final AttributeKey<Boolean> TRANSFERRING_FILE_ATTR = AttributeKey.valueOf("transferringFile");

    public NettyStreamingMessageSender(StreamSession session, OutboundConnectionSettings template, StreamConnectionFactory factory, int streamingVersion, boolean isPreview)
    {
        this.session = session;
        this.streamingVersion = streamingVersion;
        this.template = template;
        this.factory = factory;
        this.isPreview = isPreview;

        String name = session.peer.toString().replace(':', '.');
        fileTransferExecutor = new DebuggableThreadPoolExecutor(1, MAX_PARALLEL_TRANSFERS, 1L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                                                                new NamedThreadFactory("NettyStreaming-Outbound-" + name));
        fileTransferExecutor.allowCoreThreadTimeOut(true);
    }

    @Override
    public void initialize()
    {
        StreamInitMessage message = new StreamInitMessage(FBUtilities.getBroadcastAddressAndPort(),
                                                          session.sessionIndex(),
                                                          session.planId(),
                                                          session.streamOperation(),
                                                          session.getPendingRepair(),
                                                          session.getPreviewKind());
        sendMessage(message);
    }

    public boolean hasControlChannel()
    {
        return controlMessageChannel != null;
    }

    /**
     * Used by follower to setup control message channel created by initiator
     */
    public void injectControlMessageChannel(Channel channel)
    {
        this.controlMessageChannel = channel;
        channel.attr(TRANSFERRING_FILE_ATTR).set(Boolean.FALSE);
        scheduleKeepAliveTask(channel);
    }

    /**
     * Used by initiator to setup control message channel connecting to follower
     */
    private void setupControlMessageChannel() throws IOException
    {
        if (controlMessageChannel == null)
        {
            /*
             * Inbound handlers are needed:
             *  a) for initiator's control channel(the first outbound channel) to receive follower's message.
             *  b) for streaming receiver (note: both initiator and follower can receive streaming files) to reveive files,
             *     in {@link Handler#setupStreamingPipeline}
             */
            controlMessageChannel = createChannel(true);
            scheduleKeepAliveTask(controlMessageChannel);
        }
    }

    private void scheduleKeepAliveTask(Channel channel)
    {
        int keepAlivePeriod = DatabaseDescriptor.getStreamingKeepAlivePeriod();
        if (logger.isDebugEnabled())
            logger.debug("{} Scheduling keep-alive task with {}s period.", createLogTag(session, channel), keepAlivePeriod);

        KeepAliveTask task = new KeepAliveTask(channel, session);
        ScheduledFuture<?> scheduledFuture = channel.eventLoop().scheduleAtFixedRate(task, 0, keepAlivePeriod, TimeUnit.SECONDS);
        channelKeepAlives.add(scheduledFuture);
        task.future = scheduledFuture;
    }
    
    private Channel createChannel(boolean isInboundHandlerNeeded) throws IOException
    {
        Channel channel = factory.createConnection(template, streamingVersion);
        session.attachOutbound(channel);

        if (isInboundHandlerNeeded)
        {
            ChannelPipeline pipeline = channel.pipeline();
            pipeline.addLast("stream", new StreamingInboundHandler(template.to, streamingVersion, session));
        }
        channel.attr(TRANSFERRING_FILE_ATTR).set(Boolean.FALSE);
        logger.debug("Creating channel id {} local {} remote {}", channel.id(), channel.localAddress(), channel.remoteAddress());
        return channel;
    }

    static String createLogTag(StreamSession session, Channel channel)
    {
        StringBuilder sb = new StringBuilder(64);
        sb.append("[Stream");

        if (session != null)
                sb.append(" #").append(session.planId());

        if (channel != null)
                sb.append(" channel: ").append(channel.id());

        sb.append(']');
        return sb.toString();
    }

    @Override
    public void sendMessage(StreamMessage message)
    {
        if (closed)
            throw new RuntimeException("stream has been closed, cannot send " + message);

        if (message instanceof OutgoingStreamMessage)
        {
            if (isPreview)
                throw new RuntimeException("Cannot send stream data messages for preview streaming sessions");
            if (logger.isDebugEnabled())
                logger.debug("{} Sending {}", createLogTag(session, null), message);
            fileTransferExecutor.submit(new FileStreamTask((OutgoingStreamMessage)message));
            return;
        }

        try
        {
            setupControlMessageChannel();
            sendControlMessage(controlMessageChannel, message, future -> onControlMessageComplete(future, message));
        }
        catch (Exception e)
        {
            close();
            session.onError(e);
        }
    }

    private void sendControlMessage(Channel channel, StreamMessage message, GenericFutureListener listener) throws IOException
    {
        if (logger.isDebugEnabled())
            logger.debug("{} Sending {}", createLogTag(session, channel), message);

        // we anticipate that the control messages are rather small, so allocating a ByteBuf shouldn't  blow out of memory.
        long messageSize = StreamMessage.serializedSize(message, streamingVersion);
        if (messageSize > 1 << 30)
        {
            throw new IllegalStateException(String.format("%s something is seriously wrong with the calculated stream control message's size: %d bytes, type is %s",
                                                          createLogTag(session, channel), messageSize, message.type));
        }

        // as control messages are (expected to be) small, we can simply allocate a ByteBuf here, wrap it, and send via the channel
        ByteBuf buf = channel.alloc().directBuffer((int) messageSize, (int) messageSize);
        ByteBuffer nioBuf = buf.nioBuffer(0, (int) messageSize);
        @SuppressWarnings("resource")
        DataOutputBufferFixed out = new DataOutputBufferFixed(nioBuf);
        StreamMessage.serialize(message, out, streamingVersion, session);
        assert nioBuf.position() == nioBuf.limit();
        buf.writerIndex(nioBuf.position());

        AsyncChannelPromise.writeAndFlush(channel, buf, listener);
    }

    /**
     * Decides what to do after a {@link StreamMessage} is processed.
     *
     * Note: this is called from the netty event loop.
     *
     * @return null if the message was processed sucessfully; else, a {@link java.util.concurrent.Future} to indicate
     * the status of aborting any remaining tasks in the session.
     */
    java.util.concurrent.Future onControlMessageComplete(Future<?> future, StreamMessage msg)
    {
        ChannelFuture channelFuture = (ChannelFuture)future;
        Throwable cause = future.cause();
        if (cause == null)
            return null;

        Channel channel = channelFuture.channel();
        logger.error("{} failed to send a stream message/data to peer {}: msg = {}",
                     createLogTag(session, channel), template.to, msg, future.cause());

        // StreamSession will invoke close(), but we have to mark this sender as closed so the session doesn't try
        // to send any failure messages
        return session.onError(cause);
    }

    class FileStreamTask implements Runnable
    {
        /**
         * Time interval, in minutes, to wait between logging a message indicating that we're waiting on a semaphore
         * permit to become available.
         */
        private static final int SEMAPHORE_UNAVAILABLE_LOG_INTERVAL = 3;

        /**
         * Even though we expect only an {@link OutgoingStreamMessage} at runtime, the type here is {@link StreamMessage}
         * to facilitate simpler testing.
         */
        private final StreamMessage msg;

        FileStreamTask(OutgoingStreamMessage ofm)
        {
            this.msg = ofm;
        }

        /**
         * For testing purposes
         */
        FileStreamTask(StreamMessage msg)
        {
            this.msg = msg;
        }

        @Override
        public void run()
        {
            if (!acquirePermit(SEMAPHORE_UNAVAILABLE_LOG_INTERVAL))
                return;

            Channel channel = null;
            try
            {
                channel = getOrCreateChannel();
                if (!channel.attr(TRANSFERRING_FILE_ATTR).compareAndSet(false, true))
                    throw new IllegalStateException("channel's transferring state is currently set to true. refusing to start new stream");

                // close the DataOutputStreamPlus as we're done with it - but don't close the channel
                try (DataOutputStreamPlus outPlus = new AsyncStreamingOutputPlus(channel))
                {
                    StreamMessage.serialize(msg, outPlus, streamingVersion, session);
                }
                finally
                {
                    channel.attr(TRANSFERRING_FILE_ATTR).set(Boolean.FALSE);
                }
            }
            catch (Exception e)
            {
                session.onError(e);
            }
            catch (Throwable t)
            {
                if (closed && Throwables.getRootCause(t) instanceof ClosedByInterruptException && fileTransferExecutor.isShutdown())
                {
                    logger.debug("{} Streaming channel was closed due to the executor pool being shutdown", createLogTag(session, channel));
                }
                else
                {
                    JVMStabilityInspector.inspectThrowable(t);
                    if (!session.state().isFinalState())
                        session.onError(t);
                }
            }
            finally
            {
                fileTransferSemaphore.release();
            }
        }

        boolean acquirePermit(int logInterval)
        {
            long logIntervalNanos = TimeUnit.MINUTES.toNanos(logInterval);
            long timeOfLastLogging = nanoTime();
            while (true)
            {
                if (closed)
                    return false;
                try
                {
                    if (fileTransferSemaphore.tryAcquire(1, TimeUnit.SECONDS))
                        return true;

                    // log a helpful message to operators in case they are wondering why a given session might not be making progress.
                    long now = nanoTime();
                    if (now - timeOfLastLogging > logIntervalNanos)
                    {
                        timeOfLastLogging = now;
                        OutgoingStreamMessage ofm = (OutgoingStreamMessage)msg;

                        if (logger.isInfoEnabled())
                            logger.info("{} waiting to acquire a permit to begin streaming {}. This message logs every {} minutes",
                                        createLogTag(session, null), ofm.getName(), logInterval);
                    }
                }
                catch (InterruptedException ie)
                {
                    //ignore
                }
            }
        }

        private Channel getOrCreateChannel()
        {
            Thread currentThread = Thread.currentThread();
            try
            {
                Channel channel = threadToChannelMap.get(currentThread);
                if (channel != null)
                    return channel;

                channel = createChannel(false);
                threadToChannelMap.put(currentThread, channel);
                return channel;
            }
            catch (Exception e)
            {
                throw new IOError(e);
            }
        }

        private void onError(Throwable t)
        {
            try
            {
                session.onError(t).get(DEFAULT_CLOSE_WAIT_IN_MILLIS, TimeUnit.MILLISECONDS);
            }
            catch (Exception e)
            {
                // nop - let the Throwable param be the main failure point here, and let session handle it
            }
        }

        /**
         * For testing purposes
         */
        void injectChannel(Channel channel)
        {
            Thread currentThread = Thread.currentThread();
            if (threadToChannelMap.get(currentThread) != null)
                throw new IllegalStateException("previous channel already set");

            threadToChannelMap.put(currentThread, channel);
        }

        /**
         * For testing purposes
         */
        void unsetChannel()
        {
            threadToChannelMap.remove(Thread.currentThread());
        }
    }

    /**
     * Periodically sends the {@link KeepAliveMessage}.
     *
     * NOTE: this task, and the callback function {@link #keepAliveListener(Future)} is executed in the netty event loop.
     */
    class KeepAliveTask implements Runnable
    {
        private final Channel channel;
        private final StreamSession session;

        /**
         * A reference to the scheduled task for this instance so that it may be cancelled.
         */
        ScheduledFuture<?> future;

        KeepAliveTask(Channel channel, StreamSession session)
        {
            this.channel = channel;
            this.session = session;
        }

        public void run()
        {
            // if the channel has been closed, cancel the scheduled task and return
            if (!channel.isOpen() || closed)
            {
                future.cancel(false);
                return;
            }

            // if the channel is currently processing streaming, skip this execution. As this task executes
            // on the event loop, even if there is a race with a FileStreamTask which changes the channel attribute
            // after we check it, the FileStreamTask cannot send out any bytes as this KeepAliveTask is executing
            // on the event loop (and FileStreamTask publishes it's buffer to the channel, consumed after we're done here).
            if (channel.attr(TRANSFERRING_FILE_ATTR).get())
                return;

            try
            {
                if (logger.isTraceEnabled())
                    logger.trace("{} Sending keep-alive to {}.", createLogTag(session, channel), session.peer);
                sendControlMessage(channel, new KeepAliveMessage(), this::keepAliveListener);
            }
            catch (IOException ioe)
            {
                future.cancel(false);
            }
        }

        private void keepAliveListener(Future<? super Void> future)
        {
            if (future.isSuccess() || future.isCancelled())
                return;

            if (logger.isDebugEnabled())
                logger.debug("{} Could not send keep-alive message (perhaps stream session is finished?).",
                             createLogTag(session, channel), future.cause());
        }
    }

    /**
     * For testing purposes only.
     */
    public void setClosed()
    {
        closed = true;
    }

    void setControlMessageChannel(Channel channel)
    {
        controlMessageChannel = channel;
    }

    int semaphoreAvailablePermits()
    {
        return fileTransferSemaphore.availablePermits();
    }

    @Override
    public boolean connected()
    {
        return !closed && (controlMessageChannel == null || controlMessageChannel.isOpen());
    }

    @Override
    public void close()
    {
        if (closed)
            return;

        closed = true;
        if (logger.isDebugEnabled())
            logger.debug("{} Closing stream connection channels on {}", createLogTag(session, null), template.to);
        for (ScheduledFuture<?> future : channelKeepAlives)
            future.cancel(false);
        channelKeepAlives.clear();

        threadToChannelMap.clear();
        fileTransferExecutor.shutdownNow();
    }
}
