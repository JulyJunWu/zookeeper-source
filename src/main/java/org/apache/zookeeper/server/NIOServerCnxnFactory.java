/**
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

package org.apache.zookeeper.server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 接受客户端请求/处理转发请求
 */
public class NIOServerCnxnFactory extends ServerCnxnFactory implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(NIOServerCnxnFactory.class);

    static {
        /**
         * this is to avoid the jvm bug:
         * NullPointerException in Selector.open()
         * http://bugs.sun.com/view_bug.do?bug_id=6427854
         */
        try {
            Selector.open().close();
        } catch(IOException ie) {
            LOG.error("Selector failed to open", ie);
        }
    }

    ServerSocketChannel ss;

    final Selector selector = Selector.open();

    /**
     * We use this buffer to do efficient socket I/O. Since there is a single
     * sender thread per NIOServerCnxn instance, we can use a member variable to
     * only allocate it once.
    */
    final ByteBuffer directBuffer = ByteBuffer.allocateDirect(64 * 1024);

    /**
     * 存放IP -> 连接
     */
    final HashMap<InetAddress, Set<NIOServerCnxn>> ipMap =
        new HashMap<InetAddress, Set<NIOServerCnxn>>( );

    int maxClientCnxns = 60;

    /**
     * Construct a new server connection factory which will accept an unlimited number
     * of concurrent connections from each client (up to the file descriptor
     * limits of the operating system). startup(zks) must be called subsequently.
     * @throws IOException
     */
    public NIOServerCnxnFactory() throws IOException {
    }

    Thread thread;
    @Override
    public void configure(InetSocketAddress addr, int maxcc) throws IOException {
        configureSaslLogin();
        // 监听2181连接(ACCEPT)事件线程
        thread = new ZooKeeperThread(this, "NIOServerCxn.Factory:" + addr);
        thread.setDaemon(true);
        maxClientCnxns = maxcc;
        // 创建服务端ServerSocketChannel
        this.ss = ServerSocketChannel.open();
        ss.socket().setReuseAddress(true);
        LOG.info("binding to port " + addr);
        // 绑定端口2181
        ss.socket().bind(addr);
        // 设置为非阻塞
        ss.configureBlocking(false);
        // 将channel注册到selector中,订阅事件为ACCEPT(16)
        ss.register(selector, SelectionKey.OP_ACCEPT);
    }

    /** {@inheritDoc} */
    public int getMaxClientCnxnsPerHost() {
        return maxClientCnxns;
    }

    /** {@inheritDoc} */
    public void setMaxClientCnxnsPerHost(int max) {
        maxClientCnxns = max;
    }

    /**
     * 启动监听2181连接的线程
     */
    @Override
    public void start() {
        // ensure thread is started once and only once
        if (thread.getState() == Thread.State.NEW) {
            LOG.info("启动监听2181端口线程");
            thread.start();
        }
    }

    @Override
    public void startup(ZooKeeperServer zks) throws IOException,
            InterruptedException {
        // 启动监听2181线程
        start();
        //
        setZooKeeperServer(zks);
        // 加载磁盘数据到内存中
        zks.startdata();
        //
        zks.startup();
    }

    @Override
    public InetSocketAddress getLocalAddress(){
        return (InetSocketAddress)ss.socket().getLocalSocketAddress();
    }

    @Override
    public int getLocalPort(){
        return ss.socket().getLocalPort();
    }

    /**
     * 1.保存客户端连接
     * 2.记录每个IP对应有多少个客户端连接
     * @param cnxn
     */
    private void addCnxn(NIOServerCnxn cnxn) {
        synchronized (cnxns) {
            // 保存客户端连接
            cnxns.add(cnxn);
            synchronized (ipMap){
                // 限制相同IP连接的客户端数量
                InetAddress addr = cnxn.sock.socket().getInetAddress();
                Set<NIOServerCnxn> s = ipMap.get(addr);
                if (s == null) {
                    // in general we will see 1 connection from each
                    // host, setting the initial cap to 2 allows us
                    // to minimize mem usage in the common case
                    // of 1 entry --  we need to set the initial cap
                    // to 2 to avoid rehash when the first entry is added
                    s = new HashSet<NIOServerCnxn>(2);
                    s.add(cnxn);
                    ipMap.put(addr,s);
                } else {
                    s.add(cnxn);
                }
            }
        }
    }

    /**
     * 移除连接
     * @param cnxn
     */
    public void removeCnxn(NIOServerCnxn cnxn) {
        synchronized(cnxns) {
            // Remove the related session from the sessionMap.
            long sessionId = cnxn.getSessionId();
            if (sessionId != 0) {
                sessionMap.remove(sessionId);
            }

            // if this is not in cnxns then it's already closed
            if (!cnxns.remove(cnxn)) {
                return;
            }

            synchronized (ipMap) {
                Set<NIOServerCnxn> s =
                        ipMap.get(cnxn.getSocketAddress());
                s.remove(cnxn);
            }

            unregisterConnection(cnxn);
        }
    }

    protected NIOServerCnxn createConnection(SocketChannel sock,
            SelectionKey sk) throws IOException {
        return new NIOServerCnxn(zkServer, sock, sk, this);
    }

    /**\
     * 获取该IP下有多少个客户端连接
     */
    private int getClientCnxnCount(InetAddress cl) {
        // The ipMap lock covers both the map, and its contents
        // (that is, the cnxn sets shouldn't be modified outside of
        // this lock)
        synchronized (ipMap) {
            Set<NIOServerCnxn> s = ipMap.get(cl);
            if (s == null) return 0;
            return s.size();
        }
    }

    /**
     * 监听处理来自客户端的连接(2181)
     *
     * 单线程模型:
     *  既监听ACCEPT , 同时也监听 READ
     */
    public void run() {
        while (!ss.socket().isClosed()) {
            try {
                selector.select(1000);
                Set<SelectionKey> selected;
                synchronized (this) {
                    selected = selector.selectedKeys();
                }
                ArrayList<SelectionKey> selectedList = new ArrayList<SelectionKey>(
                        selected);
                Collections.shuffle(selectedList);
                for (SelectionKey k : selectedList) {
                    // 处理客户端连接请求,ACCEPT事件
                    if ((k.readyOps() & SelectionKey.OP_ACCEPT) != 0) {
                        SocketChannel sc = ((ServerSocketChannel) k
                                .channel()).accept();
                        InetAddress ia = sc.socket().getInetAddress();
                        int cnxncount = getClientCnxnCount(ia);
                        // 单个IP可连接的客户端数量有上线,上线为60
                        if (maxClientCnxns > 0 && cnxncount >= maxClientCnxns){
                            LOG.warn("Too many connections from " + ia
                                     + " - max is " + maxClientCnxns );
                            sc.close();
                        } else {
                            LOG.info("Accepted socket connection from "
                                     + sc.socket().getRemoteSocketAddress());
                            // 设置为非阻塞
                            sc.configureBlocking(false);
                            //注册到selector中,监听READ操作
                            SelectionKey sk = sc.register(selector,
                                    SelectionKey.OP_READ);
                            // 包装SocketChannel
                            NIOServerCnxn cnxn = createConnection(sc, sk);
                            // 附加参数
                            sk.attach(cnxn);
                            addCnxn(cnxn);
                        }
                        // 处理读写事件
                    } else if ((k.readyOps() & (SelectionKey.OP_READ | SelectionKey.OP_WRITE)) != 0) {
                        LOG.info("处理客户端请求,监听端口[]",ss.getLocalAddress());
                        NIOServerCnxn c = (NIOServerCnxn) k.attachment();
                        c.doIO(k);
                    } else {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Unexpected ops in select "
                                      + k.readyOps());
                        }
                    }
                }
                selected.clear();
            } catch (RuntimeException e) {
                LOG.warn("Ignoring unexpected runtime exception", e);
            } catch (Exception e) {
                LOG.warn("Ignoring exception", e);
            }
        }
        closeAll();
        LOG.info("NIOServerCnxn factory exited run method");
    }

    /**
     * clear all the connections in the selector
     *
     */
    @Override
    @SuppressWarnings("unchecked")
    synchronized public void closeAll() {
        selector.wakeup();
        HashSet<NIOServerCnxn> cnxns;
        synchronized (this.cnxns) {
            cnxns = (HashSet<NIOServerCnxn>)this.cnxns.clone();
        }
        // got to clear all the connections that we have in the selector
        //断开所有的客户端连接
        for (NIOServerCnxn cnxn: cnxns) {
            try {
                // don't hold this.cnxns lock as deadlock may occur
                cnxn.close();
            } catch (Exception e) {
                LOG.warn("Ignoring exception closing cnxn sessionid 0x"
                         + Long.toHexString(cnxn.sessionId), e);
            }
        }
    }

    public void shutdown() {
        try {
            ss.close();
            closeAll();
            thread.interrupt();
            thread.join();
            if (login != null) {
                login.shutdown();
            }
        } catch (InterruptedException e) {
            LOG.warn("Ignoring interrupted exception during shutdown", e);
        } catch (Exception e) {
            LOG.warn("Ignoring unexpected exception during shutdown", e);
        }
        try {
            selector.close();
        } catch (IOException e) {
            LOG.warn("Selector closing", e);
        }
        if (zkServer != null) {
            zkServer.shutdown();
        }
    }

    @Override
    public synchronized void closeSession(long sessionId) {
        selector.wakeup();
        closeSessionWithoutWakeup(sessionId);
    }

    @SuppressWarnings("unchecked")
    private void closeSessionWithoutWakeup(long sessionId) {
        NIOServerCnxn cnxn = (NIOServerCnxn) sessionMap.remove(sessionId);
        if (cnxn != null) {
            try {
                cnxn.close();
            } catch (Exception e) {
                LOG.warn("exception during session close", e);
            }
        }
    }

    @Override
    public void join() throws InterruptedException {
        thread.join();
    }

    @Override
    public Iterable<ServerCnxn> getConnections() {
        return cnxns;
    }
}
