/*
 * Copyright (c) 2008-2023, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.internal.networking.nio;

import com.hazelcast.internal.networking.Channel;
import com.hazelcast.internal.networking.ChannelErrorHandler;
import com.hazelcast.internal.networking.ChannelInitializer;
import com.hazelcast.internal.networking.ChannelOptions;
import com.hazelcast.internal.networking.HandlerStatus;
import com.hazelcast.internal.networking.InboundHandler;
import com.hazelcast.internal.networking.OutboundHandler;
import com.hazelcast.internal.nio.Packet;
import com.hazelcast.internal.nio.PacketIOHelper;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import org.jctools.util.PaddedAtomicLong;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import static com.hazelcast.internal.networking.ChannelOption.SO_RCVBUF;
import static com.hazelcast.internal.networking.ChannelOption.SO_SNDBUF;
import static com.hazelcast.internal.networking.ChannelOption.TCP_NODELAY;
import static com.hazelcast.internal.networking.HandlerStatus.CLEAN;
import static com.hazelcast.internal.networking.HandlerStatus.DIRTY;
import static com.hazelcast.internal.nio.IOUtil.compactOrClear;
import static com.hazelcast.internal.util.JVMUtil.upcast;
import static java.lang.System.currentTimeMillis;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Echo benchmark for Hazelcast 'classic' networking.
 * <p>
 * Warning:
 * Hazelcast classic networking requires separate threads for input and for output. So if you would
 * compare classic networking with a single reactor, then it isn't an apples vs oranges comparison because
 * with a single reactor you have only 1 thread and with classic networking you get 2,
 */
public class EchoBenchmark_Classic {
    public int payloadSize = 10_000;
    public int socketBufferSize = 256 * 1024;
    public int runtimeSeconds = 20;
    public int port = 5000;
    public int concurrency = 100;
    public String cpuAffinityClient = "1";
    public String cpuAffinityServer = "4";
    public int connections = 10;
    public boolean tcpNoDelay = true;

    private volatile boolean stop;
    private final CountDownLatch countDownLatch = new CountDownLatch(connections);
    private final ExecutorService closeListenerExecutor = Executors.newSingleThreadExecutor();
    private PaddedAtomicLong[] echoCounters;
    private final List<NioChannel> channels = new CopyOnWriteArrayList<>();

    public static void main(String[] args) throws Exception {
        EchoBenchmark_Classic benchmark = new EchoBenchmark_Classic();
        benchmark.run();
    }

    private void run() throws Exception {
        // we need to check the echo benchmark if
        echoCounters = new PaddedAtomicLong[connections];
        for (int k = 0; k < echoCounters.length; k++) {
            echoCounters[k] = new PaddedAtomicLong();
        }

        NioThread serverOutThread = newNioThread();
        NioThread serverInThread = newNioThread();
        NioThread clientOutThread = newNioThread();
        NioThread clientInThread = newNioThread();

        InetSocketAddress address = new InetSocketAddress(5000);

        Thread acceptThread = new AcceptThread(address, serverInThread, serverOutThread);
        acceptThread.start();

        for (int connection = 0; connection < connections; connection++) {
            channels.add(connect(clientInThread, clientOutThread, address));
        }

        long startMs = System.currentTimeMillis();

        for (NioChannel channel : channels) {
            for (int k = 0; k < concurrency; k++) {
                channel.write(new Packet(new byte[payloadSize]));
            }
        }

        MonitorThread monitor = new MonitorThread();
        monitor.start();
        monitor.join();

        for (NioChannel channel : channels) {
            channel.close();
        }

        countDownLatch.await();
        printResults(startMs);
    }

    private void printResults(long start) {
        long count = sum(echoCounters);
        long duration = currentTimeMillis() - start;
        System.out.println("Duration " + duration + " ms");
        System.out.println("Throughput:" + (count * 1000 / duration) + " ops");
    }

    private NioChannel connect(NioThread inThread, NioThread outThread, SocketAddress address) throws IOException {
        SocketChannel socketChannel = SocketChannel.open();
        socketChannel.connect(address);
        return newNioChannel(inThread, outThread, true, socketChannel);
    }

    private NioChannel newNioChannel(NioThread inThread,
                                     NioThread outThread,
                                     boolean clientSide,
                                     SocketChannel socketChannel) {
        ChannelInitializer channelInitializer = channel -> {
            ChannelOptions options = channel.options();
            options.setOption(TCP_NODELAY, tcpNoDelay);
            options.setOption(SO_RCVBUF, socketBufferSize);
            options.setOption(SO_SNDBUF, socketBufferSize);
        };

        NioChannel nioChannel = new NioChannel(
                socketChannel,
                clientSide,
                channelInitializer,
                closeListenerExecutor);

        NioInboundPipeline inboundPipeline = new NioInboundPipeline(nioChannel,
                inThread,
                new ChannelErrorHandlerImpl(),
                Logger.getLogger(NioInboundPipeline.class),
                null);
        inboundPipeline.addLast(new InboundHandlerImpl(clientSide ? echoCounters[0] : null));

        NioOutboundPipeline outboundPipeline = new NioOutboundPipeline(
                nioChannel,
                outThread,
                new ChannelErrorHandlerImpl(),
                Logger.getLogger(NioInboundPipeline.class),
                null,
                null,
                false,
                false);
        outboundPipeline.addLast(new OutboundHandlerImpl());

        nioChannel.init(inboundPipeline, outboundPipeline);
        nioChannel.start();

        return nioChannel;
    }

    private class AcceptThread extends Thread {
        private final InetSocketAddress address;
        private final NioThread inThread;
        private final NioThread outThread;

        private AcceptThread(InetSocketAddress address,
                            NioThread inThread,
                            NioThread outThread) {
            super("AcceptThread");
            this.address = address;
            this.inThread = inThread;
            this.outThread = outThread;
        }

        public void run() {
            try {
                try (ServerSocketChannel serverSocketChannel = ServerSocketChannel.open()) {
                    serverSocketChannel.bind(address);

                    while (!stop) {
                        SocketChannel socketChannel = serverSocketChannel.accept();
                        NioChannel channel = newNioChannel(inThread, outThread, false, socketChannel);
                        channels.add(channel);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    class InboundHandlerImpl extends InboundHandler<ByteBuffer, Void> {

        private final PacketIOHelper packetReader = new PacketIOHelper();
        private final PaddedAtomicLong completed;

        InboundHandlerImpl(PaddedAtomicLong completed) {
            this.completed = completed;
        }

        @Override
        public void handlerAdded() {
            this.initSrcBuffer();
        }

        @Override
        public HandlerStatus onRead() {
            upcast(src).flip();

            try {
                while (src.hasRemaining()) {
                    Packet packet = packetReader.readFrom(src);
                    if (packet == null) {
                        break;
                    }

                    if (completed != null) {
                        completed.incrementAndGet();
                    }

                    channel.write(packet);
                }

                return CLEAN;
            } finally {
                compactOrClear(src);
            }
        }

    }

    static class OutboundHandlerImpl extends OutboundHandler<Supplier<Packet>, ByteBuffer> {

        private final PacketIOHelper packetWriter = new PacketIOHelper();

        private Packet packet;

        @Override
        public void handlerAdded() {
            initDstBuffer();
        }

        @Override
        public HandlerStatus onWrite() {
            compactOrClear(dst);
            try {
                for (; ; ) {
                    if (packet == null) {
                        packet = src.get();

                        if (packet == null) {
                            return CLEAN;
                        }
                    }

                    if (packetWriter.writeTo(packet, dst)) {
                        packet = null;
                    } else {
                        return DIRTY;
                    }
                }
            } finally {
                upcast(dst).flip();
            }
        }
    }

    class ChannelErrorHandlerImpl implements ChannelErrorHandler {
        @Override
        public void onError(Channel channel, Throwable error) {
            try {
                channel.close();
                if (!stop) {
                    error.printStackTrace();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private NioThread newNioThread() {
        ILogger logger = Logger.getLogger(NioThread.class);
        ChannelErrorHandler channelErrorHandler = new ChannelErrorHandlerImpl();
        NioThread thread = new NioThread("foo", logger, channelErrorHandler);
        thread.start();
        return thread;
    }

    private static long sum(PaddedAtomicLong[] array) {
        long sum = 0;
        for (PaddedAtomicLong a : array) {
            sum += a.get();
        }
        return sum;
    }

    private class MonitorThread extends Thread {

        @Override
        public void run() {
            try {
                run0();
            } catch (Throwable e) {
                e.printStackTrace();
            }
            stop = true;
        }

        private void run0() throws InterruptedException {
            long end = currentTimeMillis() + SECONDS.toMillis(runtimeSeconds);
            Metrics lastMetrics = new Metrics();
            Metrics metrics = new Metrics();
            long lastMs = currentTimeMillis();
            StringBuffer sb = new StringBuffer();
            while (currentTimeMillis() < end) {
                Thread.sleep(SECONDS.toMillis(1));
                long nowMs = currentTimeMillis();

                collect(metrics);

                long diff = metrics.echos - lastMetrics.echos;
                long durationMs = nowMs - lastMs;
                double echoThp = ((diff) * 1000d) / durationMs;
                sb.append("   echo=");
                sb.append(humanReadableCountSI(echoThp));
                sb.append("/s");

                long reads = metrics.reads;
                double readsThp = ((reads - lastMetrics.reads) * 1000d) / durationMs;
                sb.append(" reads=");
                sb.append(humanReadableCountSI(readsThp));
                sb.append("/s");

                long bytesRead = metrics.bytesRead;
                double bytesReadThp = ((bytesRead - lastMetrics.bytesRead) * 1000d) / durationMs;
                sb.append(" read-bytes=");
                sb.append(humanReadableByteCountSI(bytesReadThp));
                sb.append("/s");

                long writes = metrics.writes;
                double writesThp = ((writes - lastMetrics.writes) * 1000d) / durationMs;
                sb.append(" writes=");
                sb.append(humanReadableCountSI(writesThp));
                sb.append("/s");

                long bytesWritten = metrics.bytesWritten;
                double bytesWrittehThp = ((bytesWritten - lastMetrics.bytesWritten) * 1000d) / durationMs;
                sb.append(" write-bytes=");
                sb.append(humanReadableByteCountSI(bytesWrittehThp));
                sb.append("/s");
                System.out.println(sb);
                sb.setLength(0);

                Metrics tmp = lastMetrics;
                lastMetrics = metrics;
                metrics = tmp;
                lastMs = nowMs;
            }
        }
    }

    public static String humanReadableByteCountSI(double bytes) {
        if (Double.isInfinite(bytes)) {
            return "Infinite";
        }

        if (-1000 < bytes && bytes < 1000) {
            return bytes + "B";
        }
        CharacterIterator ci = new StringCharacterIterator("kMGTPE");
        while (bytes <= -999_950 || bytes >= 999_950) {
            bytes /= 1000;
            ci.next();
        }
        return String.format("%.2f %cB", bytes / 1000.0, ci.current());
    }

    public static String humanReadableCountSI(double count) {
        if (Double.isInfinite(count)) {
            return "Infinite";
        }

        if (-1000 < count && count < 1000) {
            return String.valueOf(count);
        }
        CharacterIterator ci = new StringCharacterIterator("kMGTPE");
        while (count <= -999_950 || count >= 999_950) {
            count /= 1000;
            ci.next();
        }
        return String.format("%.2f%c", count / 1000.0, ci.current());
    }

    public static String humanReadableByteCountSI(long bytes) {
        if (-1000 < bytes && bytes < 1000) {
            return bytes + "B";
        }
        CharacterIterator ci = new StringCharacterIterator("kMGTPE");
        while (bytes <= -999_950 || bytes >= 999_950) {
            bytes /= 1000;
            ci.next();
        }
        return String.format("%.2f %cB", bytes / 1000.0, ci.current());
    }

    private void collect(Metrics target) {
        target.clear();

        target.echos = sum(echoCounters);

        for (NioChannel channel : channels) {
            target.bytesRead += channel.bytesRead();
            target.bytesWritten += channel.bytesWritten();
            target.reads += channel.inboundPipeline.processCount.get();
            target.writes += channel.outboundPipeline.processCount.get();
        }
    }

    private static class Metrics {
        private long reads;
        private long writes;
        private long bytesRead;
        private long bytesWritten;
        private long echos;

        private void clear() {
            reads = 0;
            writes = 0;
            bytesRead = 0;
            bytesWritten = 0;
            echos = 0;
        }
    }
}
