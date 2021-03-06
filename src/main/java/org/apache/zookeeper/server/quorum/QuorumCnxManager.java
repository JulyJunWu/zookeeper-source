/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server.quorum;

import org.apache.zookeeper.server.ZooKeeperThread;
import org.apache.zookeeper.server.quorum.auth.QuorumAuthLearner;
import org.apache.zookeeper.server.quorum.auth.QuorumAuthServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.UnresolvedAddressException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This class implements a connection manager for leader election using TCP. It
 * maintains one connection for every pair of servers. The tricky part is to
 * guarantee that there is exactly one connection for every pair of servers that
 * are operating correctly and that can communicate over the network.
 * <p>
 * If two servers try to start a connection concurrently, then the connection
 * manager uses a very simple tie-breaking mechanism to decide which connection
 * to drop based on the IP addressed of the two parties.
 * <p>
 * For every peer, the manager maintains a queue of messages to send. If the
 * connection to any particular peer drops, then the sender thread puts the
 * message back on the list. As this implementation currently uses a queue
 * implementation to maintain messages to send to another peer, we add the
 * message to the tail of the queue, thus changing the order of messages.
 * Although this is not a problem for the leader election, it could be a problem
 * when consolidating peer communication. This is to be verified, though.
 */

public class QuorumCnxManager {
    private static final Logger LOG = LoggerFactory.getLogger(QuorumCnxManager.class);

    /*
     * Maximum capacity of thread queues
     */
    static final int RECV_CAPACITY = 100;
    // Initialized to 1 to prevent sending
    // stale notifications to peers
    static final int SEND_CAPACITY = 1;

    static final int PACKETMAXSIZE = 1024 * 512;

    /*
     * Max buffer size to be read from the network.
     */
    static public final int maxBuffer = 2048;

    /*
     * Negative counter for observer server ids.
     */

    private AtomicLong observerCounter = new AtomicLong(-1);

    /*
     * Connection time out value in milliseconds
     */

    private int cnxTO = 5000;

    /*
     * Local IP address
     */
    final long mySid;
    final int socketTimeout;
    /**
     * key = sid , value = 节点的地址和 端口
     */
    final Map<Long, QuorumPeer.QuorumServer> view;
    final boolean tcpKeepAlive = Boolean.getBoolean("zookeeper.tcpKeepAlive");
    final boolean listenOnAllIPs;
    private ThreadPoolExecutor connectionExecutor;
    private final Set<Long> inprogressConnections = Collections
            .synchronizedSet(new HashSet<Long>());
    private QuorumAuthServer authServer;
    private QuorumAuthLearner authLearner;
    private boolean quorumSaslAuthEnabled;
    /*
     * Counter to count connection processing threads.
     */
    private AtomicInteger connectionThreadCnt = new AtomicInteger(0);

    /*
     * Mapping from Peer to Thread number
     *
     * server.id 对应的SendWorker线程
     * 该线程持有对象如下:
     *   职责        : 负责对该server.id数据发送,使用Socket发送
     *   Socket     : 与目标server.id服务器选举端口通讯的连接
     *   RecvWorker : 处理该Socket对象的接收数据
     */
    final ConcurrentHashMap<Long, SendWorker> senderWorkerMap;
    /**
     * key=sid , value=需要发送的数据
     */
    final ConcurrentHashMap<Long, ArrayBlockingQueue<ByteBuffer>> queueSendMap;
    /**
     * 存储最近一次向目标sid发送的数据
     */
    final ConcurrentHashMap<Long, ByteBuffer> lastMessageSent;

    /*
     * Reception queue
     * 等待FastLeaderElection类中的WorkerReceiver消费
     * 等待RecvWorker读取的数据 保存到该队列
     *                 recvQueue
     *         /--------------------------\
     *      RecvWorker offer    WorkerReceiver poll
     */
    public final ArrayBlockingQueue<Message> recvQueue;
    /*
     * Object to synchronize access to recvQueue
     */
    private final Object recvQLock = new Object();

    /*
     * Shutdown flag
     */

    volatile boolean shutdown = false;

    /*
     * Listener thread
     * 监听连接选举端口的连接
     */
    public final Listener listener;

    /*
     * Counter to count worker threads
     * 统计对应的 SendWorker线程和RecvWorker线程的数量
     *
     * 当对应的线程启动时,加- ;
     * 当对应的线程结束时,减一 ;
     */
    private AtomicInteger threadCnt = new AtomicInteger(0);

    /**
     * 收到的数据
     */
    static public class Message {

        Message(ByteBuffer buffer, long sid) {
            this.buffer = buffer;
            this.sid = sid;
        }

        /**
         * server.id 所在的服务器发送的数据
         */
        ByteBuffer buffer;
        /**
         * server.id
         */
        long sid;
    }

    public QuorumCnxManager(final long mySid,
                            Map<Long, QuorumPeer.QuorumServer> view,
                            QuorumAuthServer authServer,
                            QuorumAuthLearner authLearner,
                            int socketTimeout,
                            boolean listenOnAllIPs,
                            int quorumCnxnThreadsSize,
                            boolean quorumSaslAuthEnabled) {
        this(mySid, view, authServer, authLearner, socketTimeout, listenOnAllIPs,
                quorumCnxnThreadsSize, quorumSaslAuthEnabled, new ConcurrentHashMap<Long, SendWorker>());
    }

    // visible for testing
    public QuorumCnxManager(final long mySid,
                            Map<Long, QuorumPeer.QuorumServer> view,
                            QuorumAuthServer authServer,
                            QuorumAuthLearner authLearner,
                            int socketTimeout,
                            boolean listenOnAllIPs,
                            int quorumCnxnThreadsSize,
                            boolean quorumSaslAuthEnabled,
                            ConcurrentHashMap<Long, SendWorker> senderWorkerMap) {
        this.senderWorkerMap = senderWorkerMap;

        this.recvQueue = new ArrayBlockingQueue<Message>(RECV_CAPACITY);
        this.queueSendMap = new ConcurrentHashMap<Long, ArrayBlockingQueue<ByteBuffer>>();
        this.lastMessageSent = new ConcurrentHashMap<Long, ByteBuffer>();
        String cnxToValue = System.getProperty("zookeeper.cnxTimeout");
        if (cnxToValue != null) {
            this.cnxTO = Integer.parseInt(cnxToValue);
        }

        this.mySid = mySid;
        this.socketTimeout = socketTimeout;
        this.view = view;
        this.listenOnAllIPs = listenOnAllIPs;

        initializeAuth(mySid, authServer, authLearner, quorumCnxnThreadsSize,
                quorumSaslAuthEnabled);

        // Starts listener thread that waits for connection requests 
        listener = new Listener();
    }

    private void initializeAuth(final long mySid,
                                final QuorumAuthServer authServer,
                                final QuorumAuthLearner authLearner,
                                final int quorumCnxnThreadsSize,
                                final boolean quorumSaslAuthEnabled) {
        this.authServer = authServer;
        this.authLearner = authLearner;
        this.quorumSaslAuthEnabled = quorumSaslAuthEnabled;
        if (!this.quorumSaslAuthEnabled) {
            LOG.debug("Not initializing connection executor as quorum sasl auth is disabled");
            return;
        }

        // init connection executors
        final AtomicInteger threadIndex = new AtomicInteger(1);
        SecurityManager s = System.getSecurityManager();
        final ThreadGroup group = (s != null) ? s.getThreadGroup()
                : Thread.currentThread().getThreadGroup();
        ThreadFactory daemonThFactory = new ThreadFactory() {

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(group, r, "QuorumConnectionThread-"
                        + "[myid=" + mySid + "]-"
                        + threadIndex.getAndIncrement());
                return t;
            }
        };
        this.connectionExecutor = new ThreadPoolExecutor(3,
                quorumCnxnThreadsSize, 60, TimeUnit.SECONDS,
                new SynchronousQueue<Runnable>(), daemonThFactory);
        this.connectionExecutor.allowCoreThreadTimeOut(true);
    }

    /**
     * Invokes initiateConnection for testing purposes
     *
     * @param sid
     */
    public void testInitiateConnection(long sid) throws Exception {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Opening channel to server " + sid);
        }
        Socket sock = new Socket();
        setSockOpts(sock);
        sock.connect(QuorumPeer.viewToVotingView(view).get(sid).electionAddr,
                cnxTO);
        initiateConnection(sock, sid);
    }

    /**
     * If this server has initiated the connection, then it gives up on the
     * connection if it loses challenge. Otherwise, it keeps the connection.
     * <p>
     * 创建socket对应的读写2个线程
     */
    public void initiateConnection(final Socket sock, final Long sid) {
        try {
            startConnection(sock, sid);
        } catch (IOException e) {
            LOG.error("Exception while connecting, id: {}, addr: {}, closing learner connection",
                    new Object[]{sid, sock.getRemoteSocketAddress()}, e);
            closeSocket(sock);
            return;
        }
    }

    /**
     * Server will initiate the connection request to its peer server
     * asynchronously via separate connection thread.
     */
    public void initiateConnectionAsync(final Socket sock, final Long sid) {
        if (!inprogressConnections.add(sid)) {
            // simply return as there is a connection request to
            // server 'sid' already in progress.
            LOG.debug("Connection request to server id: {} is already in progress, so skipping this request",
                    sid);
            closeSocket(sock);
            return;
        }
        try {
            connectionExecutor.execute(
                    new QuorumConnectionReqThread(sock, sid));
            connectionThreadCnt.incrementAndGet();
        } catch (Throwable e) {
            // Imp: Safer side catching all type of exceptions and remove 'sid'
            // from inprogress connections. This is to avoid blocking further
            // connection requests from this 'sid' in case of errors.
            inprogressConnections.remove(sid);
            LOG.error("Exception while submitting quorum connection request", e);
            closeSocket(sock);
        }
    }

    /**
     * Thread to send connection request to peer server.
     */
    private class QuorumConnectionReqThread extends ZooKeeperThread {
        final Socket sock;
        final Long sid;

        QuorumConnectionReqThread(final Socket sock, final Long sid) {
            super("QuorumConnectionReqThread-" + sid);
            this.sock = sock;
            this.sid = sid;
        }

        @Override
        public void run() {
            try {
                initiateConnection(sock, sid);
            } finally {
                inprogressConnections.remove(sid);
            }
        }
    }

    private boolean startConnection(Socket sock, Long sid)
            throws IOException {
        DataOutputStream dout = null;
        DataInputStream din = null;
        try {
            // Sending id and challenge
            // 包装的输出流
            dout = new DataOutputStream(sock.getOutputStream());
            // 直接发送本节点的sid
            dout.writeLong(this.mySid);
            dout.flush();
            // 包装的输入流
            din = new DataInputStream(
                    new BufferedInputStream(sock.getInputStream()));
        } catch (IOException e) {
            LOG.warn("Ignoring exception reading or writing challenge: ", e);
            closeSocket(sock);
            return false;
        }

        // authenticate learner
        // 默认不启用安全验证
        authLearner.authenticate(sock, view.get(sid).hostname);

        // If lost the challenge, then drop the new connection
        if (sid > this.mySid) {
            // 如果外部的sid 大于本节点的sid , 则将本节点中关于该外部sid的线程结束
            // 保证是sid大的一方发起连接
            LOG.info("Have smaller server identifier, so dropping the " +
                    "connection: (" + sid + ", " + this.mySid + ")");
            closeSocket(sock);
            // Otherwise proceed with the connection
        } else {
            // 说明本节点的sid大
            //建立该sock数据发送线程
            SendWorker sw = new SendWorker(sock, sid);
            //建立该sock数据接收线程
            RecvWorker rw = new RecvWorker(sock, din, sid, sw);
            sw.setRecv(rw);

            SendWorker vsw = senderWorkerMap.get(sid);
            //如果之前已经存在了,那么先将之前的SendWorker线程停止
            if (vsw != null)
                vsw.finish();
            //将处理sid相关数据的线程放入队列
            senderWorkerMap.put(sid, sw);
            queueSendMap.putIfAbsent(sid, new ArrayBlockingQueue<ByteBuffer>(SEND_CAPACITY));
            //启动sid发送线程
            sw.start();
            //启动sid接受线程
            rw.start();

            return true;
        }
        return false;
    }

    /**
     * If this server receives a connection request, then it gives up on the new
     * connection if it wins. Notice that it checks whether it has a connection
     * to this server already or not. If it does, then it sends the smallest
     * possible long value to lose the challenge.
     */
    public void receiveConnection(final Socket sock) {
        DataInputStream din = null;
        try {
            din = new DataInputStream(
                    new BufferedInputStream(sock.getInputStream()));

            handleConnection(sock, din);
        } catch (IOException e) {
            LOG.error("Exception handling connection, addr: {}, closing server connection",
                    sock.getRemoteSocketAddress());
            closeSocket(sock);
        }
    }

    /**
     * Server receives a connection request and handles it asynchronously via
     * separate thread.
     */
    public void receiveConnectionAsync(final Socket sock) {
        try {
            connectionExecutor.execute(
                    new QuorumConnectionReceiverThread(sock));
            connectionThreadCnt.incrementAndGet();
        } catch (Throwable e) {
            LOG.error("Exception handling connection, addr: {}, closing server connection",
                    sock.getRemoteSocketAddress());
            closeSocket(sock);
        }
    }

    /**
     * Thread to receive connection request from peer server.
     */
    private class QuorumConnectionReceiverThread extends ZooKeeperThread {
        private final Socket sock;

        QuorumConnectionReceiverThread(final Socket sock) {
            super("QuorumConnectionReceiverThread-" + sock.getRemoteSocketAddress());
            this.sock = sock;
        }

        @Override
        public void run() {
            receiveConnection(sock);
        }
    }

    private void handleConnection(Socket sock, DataInputStream din)
            throws IOException {
        Long sid = null;
        try {
            // Read server id
            // 首次连接都是发送sid,所以接收一开始直接读取sid
            sid = din.readLong();
            if (sid < 0) { // this is not a server id but a protocol version (see ZOOKEEPER-1633)
                sid = din.readLong();

                // next comes the #bytes in the remainder of the message
                // note that 0 bytes is fine (old servers)
                int num_remaining_bytes = din.readInt();
                if (num_remaining_bytes < 0 || num_remaining_bytes > maxBuffer) {
                    LOG.error("Unreasonable buffer length: {}", num_remaining_bytes);
                    closeSocket(sock);
                    return;
                }
                byte[] b = new byte[num_remaining_bytes];

                // remove the remainder of the message from din
                int num_read = din.read(b);
                if (num_read != num_remaining_bytes) {
                    LOG.error("Read only " + num_read + " bytes out of " + num_remaining_bytes + " sent by server " + sid);
                }
            }
            if (sid == QuorumPeer.OBSERVER_ID) {
                /*
                 * Choose identifier at random. We need a value to identify
                 * the connection.
                 */
                sid = observerCounter.getAndDecrement();
                LOG.info("Setting arbitrary identifier to observer: " + sid);
            }
        } catch (IOException e) {
            closeSocket(sock);
            LOG.warn("Exception reading or writing challenge: " + e.toString());
            return;
        }

        // do authenticating learner
        LOG.debug("Authenticating learner server.id: {}", sid);
        // 安全验证,默认无
        authServer.authenticate(sock, din);

        //If wins the challenge, then close the new connection.
        if (sid < this.mySid) {
            /*
             * This replica might still believe that the connection to sid is
             * up, so we have to shut down the workers before trying to open a
             * new connection.
             *
             * 如果说外节点的sid 小于当前节点的sid , 则将与与节点sid相关联的读写线程和socket进行关闭,然后由本节点发起一个连接,
             *
             * 意味着:只能由数值大的sid的服务器发起连接,是为了避免重复连接??
             */
            SendWorker sw = senderWorkerMap.get(sid);
            //关闭当前外部sid关联的线程,同时也从集合中移除
            if (sw != null) {
                sw.finish();
            }

            /*
             * Now we start a new connection
             */
            LOG.info("Create new connection to server: " + sid);
            closeSocket(sock);
            // 由本节点发起连接
            connectOne(sid);

            // Otherwise start worker threads to receive data.
        } else {
            // 说明sid 大于本节点的myid
            SendWorker sw = new SendWorker(sock, sid);
            RecvWorker rw = new RecvWorker(sock, din, sid, sw);
            sw.setRecv(rw);

            SendWorker vsw = senderWorkerMap.get(sid);
            // 如果之前存在连接以及相关线程,停止
            if (vsw != null)
                vsw.finish();

            senderWorkerMap.put(sid, sw);
            queueSendMap.putIfAbsent(sid, new ArrayBlockingQueue<ByteBuffer>(SEND_CAPACITY));
            // 启动对该Socket的读取和写线程
            sw.start();
            rw.start();
            return;
        }
    }

    /**
     * Processes invoke this message to queue a message to send. Currently,
     * only leader election uses it.
     * sid : 将要发往的目标myid
     */
    public void toSend(Long sid, ByteBuffer b) {
        /*
         * If sending message to myself, then simply enqueue it (loopback).
         * 如果发送的目标是本节点,那么直接放入接收队列
         */
        if (this.mySid == sid) {
            b.position(0);
            addToRecvQueue(new Message(b.duplicate(), sid));
            /*
             * Otherwise send to the corresponding thread to send.
             */
        } else {
            /*
             * Start a new connection if doesn't have one already.
             */
            ArrayBlockingQueue<ByteBuffer> bq = new ArrayBlockingQueue<ByteBuffer>(SEND_CAPACITY);
            ArrayBlockingQueue<ByteBuffer> bqExisting = queueSendMap.putIfAbsent(sid, bq);
            // 将缓冲数据保存到对应的sid的队列中,等待目标sid的SendWorker线程去处理
            if (bqExisting != null) {
                addToSendQueue(bqExisting, b);
            } else {
                addToSendQueue(bq, b);
            }
            //如果对应目标服务器的客户端socket未启动,则启动一个socket与sid对应的选举端口进行连接
            // 启动对应socket客户端读写线程
            connectOne(sid);

        }
    }

    /**
     * Try to establish a connection to server with id sid.
     *
     * @param sid server id
     */
    synchronized public void connectOne(long sid) {
        //连接不存在就创建
        if (!connectedToPeer(sid)) {
            InetSocketAddress electionAddr;
            //获取目标sid服务器的选举端口
            if (view.containsKey(sid)) {
                electionAddr = view.get(sid).electionAddr;
            } else {
                LOG.warn("Invalid server id: " + sid);
                // 不存在该sid数据,非法的,直接return
                return;
            }
            try {

                LOG.info("Opening channel to server " + sid);
                //创建一个客户端与目标sid服务器选举端口通讯
                Socket sock = new Socket();
                // 设置socket属性
                setSockOpts(sock);
                // 连接sid对应的选举地址
                sock.connect(view.get(sid).electionAddr, cnxTO);
                LOG.info("Connected to server " + sid);

                // Sends connection request asynchronously if the quorum
                // sasl authentication is enabled. This is required because
                // sasl server authentication process may take few seconds to
                // finish, this may delay next peer connection requests.
                if (quorumSaslAuthEnabled) {
                    initiateConnectionAsync(sock, sid);
                } else {
                    initiateConnection(sock, sid);
                }
            } catch (UnresolvedAddressException e) {
                // Sun doesn't include the address that causes this
                // exception to be thrown, also UAE cannot be wrapped cleanly
                // so we log the exception in order to capture this critical
                // detail.
                LOG.warn("Cannot open channel to " + sid
                        + " at election address " + electionAddr, e);
                // Resolve hostname for this server in case the
                // underlying ip address has changed.
                if (view.containsKey(sid)) {
                    view.get(sid).recreateSocketAddresses();
                }
                throw e;
            } catch (IOException e) {
                LOG.warn("Cannot open channel to " + sid
                                + " at election address " + electionAddr,
                        e);
                // We can't really tell if the server is actually down or it failed
                // to connect to the server because the underlying IP address
                // changed. Resolve the hostname again just in case.
                if (view.containsKey(sid)) {
                    // 重建地址
                    view.get(sid).recreateSocketAddresses();
                }
            }
        } else {
            LOG.info("There is a connection already for server " + sid);
        }
    }


    /**
     * Try to establish a connection with each server if one
     * doesn't exist.
     */

    public void connectAll() {
        long sid;
        for (Enumeration<Long> en = queueSendMap.keys();
             en.hasMoreElements(); ) {
            sid = en.nextElement();
            connectOne(sid);
        }
    }


    /**
     * Check if all queues are empty, indicating that all messages have been delivered.
     */
    boolean haveDelivered() {
        for (ArrayBlockingQueue<ByteBuffer> queue : queueSendMap.values()) {
            LOG.debug("Queue size: " + queue.size());
            if (queue.size() == 0) {
                return true;
            }
        }

        return false;
    }

    /**
     * Flag that it is time to wrap up all activities and interrupt the listener.
     */
    public void halt() {
        shutdown = true;
        LOG.debug("Halting listener");
        listener.halt();

        softHalt();

        // clear data structures used for auth
        if (connectionExecutor != null) {
            connectionExecutor.shutdown();
        }
        inprogressConnections.clear();
        resetConnectionThreadCount();
    }

    /**
     * A soft halt simply finishes workers.
     */
    public void softHalt() {
        for (SendWorker sw : senderWorkerMap.values()) {
            LOG.debug("Halting sender: " + sw);
            sw.finish();
        }
    }

    /**
     * Helper method to set socket options.
     *
     * @param sock Reference to socket
     *             <p>
     *             设置Socket一些属性
     */
    private void setSockOpts(Socket sock) throws SocketException {
        // 禁止启用Nagle算法
        sock.setTcpNoDelay(true);
        // 是否长连接
        sock.setKeepAlive(tcpKeepAlive);
        // 超时时间
        sock.setSoTimeout(socketTimeout);
    }

    /**
     * Helper method to close a socket.
     *
     * @param sock Reference to socket
     */
    private void closeSocket(Socket sock) {
        try {
            sock.close();
        } catch (IOException ie) {
            LOG.error("Exception while closing", ie);
        }
    }

    /**
     * Return number of worker threads
     */
    public long getThreadCount() {
        return threadCnt.get();
    }

    /**
     * Return number of connection processing threads.
     */
    public long getConnectionThreadCount() {
        return connectionThreadCnt.get();
    }

    /**
     * Reset the value of connection processing threads count to zero.
     */
    private void resetConnectionThreadCount() {
        connectionThreadCnt.set(0);
    }

    /**
     * Thread to listen on some port
     * <p>
     * 监听 3888选举端口连接
     */
    public class Listener extends ZooKeeperThread {

        volatile ServerSocket ss = null;

        public Listener() {
            // During startup of thread, thread name will be overridden to
            // specific election address
            super("ListenerThread");
        }

        /**
         * Sleeps on accept().
         * <p>
         * 处理选举端口 socket连接
         */
        @Override
        public void run() {
            int numRetries = 0;
            InetSocketAddress addr;
            while ((!shutdown) && (numRetries < 3)) {
                try {
                    ss = new ServerSocket();
                    ss.setReuseAddress(true);
                    if (listenOnAllIPs) {
                        int port = view.get(QuorumCnxManager.this.mySid)
                                .electionAddr.getPort();
                        addr = new InetSocketAddress(port);
                    } else {
                        //选举的端口
                        addr = view.get(QuorumCnxManager.this.mySid)
                                .electionAddr;
                    }
                    LOG.info("My election bind port: " + addr.toString());
                    setName(view.get(QuorumCnxManager.this.mySid)
                            .electionAddr.toString());
                    //绑定端口
                    ss.bind(addr);
                    LOG.info("{} | {} thread start", this.getName(), this.getClass().getSimpleName());
                    while (!shutdown) {
                        Socket client = ss.accept();
                        setSockOpts(client);
                        LOG.info("Received connection request "
                                + client.getRemoteSocketAddress());

                        // Receive and handle the connection request
                        // asynchronously if the quorum sasl authentication is
                        // enabled. This is required because sasl server
                        // authentication process may take few seconds to finish,
                        // this may delay next peer connection requests.
                        if (quorumSaslAuthEnabled) {
                            receiveConnectionAsync(client);
                        } else {
                            receiveConnection(client);
                        }

                        numRetries = 0;
                    }
                } catch (IOException e) {
                    LOG.error("Exception while listening", e);
                    numRetries++;
                    try {
                        ss.close();
                        Thread.sleep(1000);
                    } catch (IOException ie) {
                        LOG.error("Error closing server socket", ie);
                    } catch (InterruptedException ie) {
                        LOG.error("Interrupted while sleeping. " +
                                "Ignoring exception", ie);
                    }
                }
            }
            LOG.info("Leaving listener");
            if (!shutdown) {
                LOG.error("As I'm leaving the listener thread, "
                        + "I won't be able to participate in leader "
                        + "election any longer: "
                        + view.get(QuorumCnxManager.this.mySid).electionAddr);
            }
        }

        /**
         * Halts this listener thread.
         */
        void halt() {
            try {
                LOG.debug("Trying to close listener: " + ss);
                if (ss != null) {
                    LOG.debug("Closing listener: "
                            + QuorumCnxManager.this.mySid);
                    ss.close();
                }
            } catch (IOException e) {
                LOG.warn("Exception when shutting down listener: " + e);
            }
        }
    }

    /**
     * Thread to send messages. Instance waits on a queue, and send a message as
     * soon as there is one available. If connection breaks, then opens a new
     * one.
     *
     * 从 queueSendMap不断拉取属于自身sid对应的数据进行发送
     */
    class SendWorker extends ZooKeeperThread {
        /**
         * 目标节点的server.id
         */
        Long sid;
        /**
         * 目标节点的选举端口的客户端连接
         */
        Socket sock;
        /**
         * 接受目标节点服务器发来的选举相关数据
         */
        RecvWorker recvWorker;
        volatile boolean running = true;
        DataOutputStream dout;

        /**
         * An instance of this thread receives messages to send
         * through a queue and sends them to the server sid.
         *
         * @param sock Socket to remote peer
         * @param sid  Server identifier of remote peer
         */
        SendWorker(Socket sock, Long sid) {
            super("SendWorker:" + sid);
            this.sid = sid;
            this.sock = sock;
            recvWorker = null;
            try {
                dout = new DataOutputStream(sock.getOutputStream());
            } catch (IOException e) {
                LOG.error("Unable to access socket output stream", e);
                closeSocket(sock);
                running = false;
            }
            LOG.debug("Address of remote peer: " + this.sid);
        }

        synchronized void setRecv(RecvWorker recvWorker) {
            this.recvWorker = recvWorker;
        }

        /**
         * Returns RecvWorker that pairs up with this SendWorker.
         *
         * @return RecvWorker
         */
        synchronized RecvWorker getRecvWorker() {
            return recvWorker;
        }

        synchronized boolean finish() {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Calling finish for " + sid);
            }

            if (!running) {
                /*
                 * Avoids running finish() twice.
                 */
                return running;
            }

            running = false;
            // 关闭socket连接
            closeSocket(sock);
            // channel = null;

            this.interrupt();
            //关闭接收线程
            if (recvWorker != null) {
                recvWorker.finish();
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug("Removing entry from senderWorkerMap sid=" + sid);
            }
            //从集合中移除该节点对应的线程
            senderWorkerMap.remove(sid, this);
            //线程数量--
            threadCnt.decrementAndGet();
            LOG.info("接收选举通讯线程注销, sid={}",sid);
            return running;
        }

        /**
         * 将数据发送到目标sid服务器选举端口
         */
        synchronized void send(ByteBuffer b) throws IOException {
            byte[] msgBytes = new byte[b.capacity()];
            try {
                //这个操作有点不明白了,验证是否溢出??? 为什么不直接验证remain和长度或者capacity????
                // TODO::::  疑问!!! 感觉代码时多余的!!! 可否使用b.remaining() == b.capacity()
                b.position(0);
                b.get(msgBytes);
            } catch (BufferUnderflowException be) {
                LOG.error("BufferUnderflowException ", be);
                return;
            }
            //写入消息正文长度
            dout.writeInt(b.capacity());
            //写入消息正文数据
            dout.write(b.array());
            //发送
            dout.flush();
        }

        @Override
        public void run() {
            threadCnt.incrementAndGet();
            try {
                /**
                 * If there is nothing in the queue to send, then we
                 * send the lastMessage to ensure that the last message
                 * was received by the peer. The message could be dropped
                 * in case self or the peer shutdown their connection
                 * (and exit the thread) prior to reading/processing
                 * the last message. Duplicate messages are handled correctly
                 * by the peer.
                 *
                 * If the send queue is non-empty, then we have a recent
                 * message than that stored in lastMessage. To avoid sending
                 * stale message, we should send the message in the send queue.
                 */
                //获取自己sid对应需要处理的发送数据
                ArrayBlockingQueue<ByteBuffer> bq = queueSendMap.get(sid);
                if (bq == null || isSendQueueEmpty(bq)) {
                    ByteBuffer b = lastMessageSent.get(sid);
                    if (b != null) {
                        LOG.debug("Attempting to send lastMessage to sid=" + sid);
                        send(b);
                    }
                }
            } catch (IOException e) {
                LOG.error("Failed to send last message. Shutting down thread.", e);
                this.finish();
            }

            try {
                while (running && !shutdown && sock != null) {

                    ByteBuffer b = null;
                    try {
                        ArrayBlockingQueue<ByteBuffer> bq = queueSendMap
                                .get(sid);
                        if (bq != null) {
                            //取出一条数据,没有的话等到一定的时间
                            b = pollSendQueue(bq, 1000, TimeUnit.MILLISECONDS);
                        } else {
                            LOG.error("No queue of incoming messages for " +
                                    "server " + sid);
                            break;
                        }

                        if (b != null) {
                            //设置为最后发送的数据
                            lastMessageSent.put(sid, b);
                            //发送数据
                            send(b);
                        }
                    } catch (InterruptedException e) {
                        LOG.warn("Interrupted while waiting for message on queue",
                                e);
                    }
                }
            } catch (Exception e) {
                LOG.warn("Exception when using channel: for id " + sid
                        + " my id = " + QuorumCnxManager.this.mySid
                        + " error = " + e);
            }
            this.finish();
            LOG.warn("Send worker leaving thread");
        }
    }

    /**
     * Thread to receive messages. Instance waits on a socket read. If the
     * channel breaks, then removes itself from the pool of receivers.
     *
     * 负责节点的选举信息接收 以及处理转发到msgBytes队列
     */
    class RecvWorker extends ZooKeeperThread {
        /**
         * 目标节点server.id
         */
        Long sid;
        /**
         * 与目标节点选举端口建立的socket客户端连接
         */
        Socket sock;
        volatile boolean running = true;
        final DataInputStream din;
        /**
         * 处理发送目标节点选举相关数据的线程
         */
        final SendWorker sw;

        RecvWorker(Socket sock, DataInputStream din, Long sid, SendWorker sw) {
            super("RecvWorker:" + sid);
            this.sid = sid;
            this.sock = sock;
            this.sw = sw;
            this.din = din;
            try {
                // OK to wait until socket disconnects while reading.
                sock.setSoTimeout(0);
            } catch (IOException e) {
                LOG.error("Error while accessing socket for " + sid, e);
                closeSocket(sock);
                running = false;
            }
        }

        /**
         * Shuts down this worker
         *
         * @return boolean  Value of variable running
         */
        synchronized boolean finish() {
            if (!running) {
                /*
                 * Avoids running finish() twice.
                 */
                return running;
            }
            running = false;

            this.interrupt();
            threadCnt.decrementAndGet();
            return running;
        }

        @Override
        public void run() {
            threadCnt.incrementAndGet();
            try {
                while (running && !shutdown && sock != null) {
                    /**
                     * Reads the first int to determine the length of the
                     * message
                     *
                     * 先读取4个字节,代表着消息的长度 , 无消息则阻塞
                     */
                    int length = din.readInt();
                    if (length <= 0 || length > PACKETMAXSIZE) {
                        throw new IOException(
                                "Received packet with invalid packet: "
                                        + length);
                    }
                    /**
                     * Allocates a new ByteBuffer to receive the message
                     */
                    byte[] msgArray = new byte[length];
                    din.readFully(msgArray, 0, length);
                    ByteBuffer message = ByteBuffer.wrap(msgArray);
                    //将收到的数据保存到recvQueue队列中
                    addToRecvQueue(new Message(message.duplicate(), sid));
                }
            } catch (Exception e) {
                LOG.warn("Connection broken for id " + sid + ", my id = "
                        + QuorumCnxManager.this.mySid + ", error = ", e);
            } finally {
                LOG.warn("Interrupting SendWorker");
                sw.finish();
                if (sock != null) {
                    closeSocket(sock);
                }
            }
        }
    }

    /**
     * Inserts an element in the specified queue. If the Queue is full, this
     * method removes an element from the head of the Queue and then inserts
     * the element at the tail. It can happen that the an element is removed
     * by another thread in {@link SendWorker#processMessage() processMessage}
     * method before this method attempts to remove an element from the queue.
     * This will cause {@link ArrayBlockingQueue#remove() remove} to throw an
     * exception, which is safe to ignore.
     * <p>
     * Unlike {@link #addToRecvQueue(Message) addToRecvQueue} this method does
     * not need to be synchronized since there is only one thread that inserts
     * an element in the queue and another thread that reads from the queue.
     *
     * @param queue  Reference to the Queue
     * @param buffer Reference to the buffer to be inserted in the queue
     */
    private void addToSendQueue(ArrayBlockingQueue<ByteBuffer> queue,
                                ByteBuffer buffer) {
        if (queue.remainingCapacity() == 0) {
            try {
                queue.remove();
            } catch (NoSuchElementException ne) {
                // element could be removed by poll()
                LOG.debug("Trying to remove from an empty " +
                        "Queue. Ignoring exception " + ne);
            }
        }
        try {
            queue.add(buffer);
        } catch (IllegalStateException ie) {
            // This should never happen
            LOG.error("Unable to insert an element in the queue " + ie);
        }
    }

    /**
     * Returns true if queue is empty.
     *
     * @param queue Reference to the queue
     * @return true if the specified queue is empty
     */
    private boolean isSendQueueEmpty(ArrayBlockingQueue<ByteBuffer> queue) {
        return queue.isEmpty();
    }

    /**
     * Retrieves and removes buffer at the head of this queue,
     * waiting up to the specified wait time if necessary for an element to
     * become available.
     * <p>
     * {@link ArrayBlockingQueue#poll(long, TimeUnit)}
     */
    private ByteBuffer pollSendQueue(ArrayBlockingQueue<ByteBuffer> queue,
                                     long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    /**
     * Inserts an element in the {@link #recvQueue}. If the Queue is full, this
     * methods removes an element from the head of the Queue and then inserts
     * the element at the tail of the queue.
     * <p>
     * This method is synchronized to achieve fairness between two threads that
     * are trying to insert an element in the queue. Each thread checks if the
     * queue is full, then removes the element at the head of the queue, and
     * then inserts an element at the tail. This three-step process is done to
     * prevent a thread from blocking while inserting an element in the queue.
     * If we do not synchronize the call to this method, then a thread can grab
     * a slot in the queue created by the second thread. This can cause the call
     * to insert by the second thread to fail.
     * Note that synchronizing this method does not block another thread
     * from polling the queue since that synchronization is provided by the
     * queue itself.
     *
     * @param msg Reference to the message to be inserted in the queue
     *
     *  将数据添加到recvQueue队列中 , recvQueue队列数据等待FastLeaderElection.WorkerReceiver线程消费
     */
    public void addToRecvQueue(Message msg) {
        synchronized (recvQLock) {
            if (recvQueue.remainingCapacity() == 0) {
                try {
                    recvQueue.remove();
                } catch (NoSuchElementException ne) {
                    // element could be removed by poll()
                    LOG.debug("Trying to remove from an empty " +
                            "recvQueue. Ignoring exception " + ne);
                }
            }
            try {
                recvQueue.add(msg);
            } catch (IllegalStateException ie) {
                // This should never happen
                LOG.error("Unable to insert element in the recvQueue " + ie);
            }
        }
    }

    /**
     * Retrieves and removes a message at the head of this queue,
     * waiting up to the specified wait time if necessary for an element to
     * become available.
     * <p>
     * {@link ArrayBlockingQueue#poll(long, TimeUnit)}
     */
    public Message pollRecvQueue(long timeout, TimeUnit unit)
            throws InterruptedException {
        return recvQueue.poll(timeout, unit);
    }

    /**
     * 是否存在 该节点的对应处理socket数据的线程
     *
     * @param peerSid
     * @return
     */
    public boolean connectedToPeer(long peerSid) {
        return senderWorkerMap.get(peerSid) != null;
    }
}
