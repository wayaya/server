/*
 * Copyright (c) 2012-2017 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 * The Eclipse Public License is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * The Apache License v2.0 is available at
 * http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package io.moquette.server;

import cn.wildfirechat.push.PushServer;
import cn.wildfirechat.server.ThreadPoolExecutorWrapper;
import com.hazelcast.config.ClasspathXmlConfig;
import com.hazelcast.config.Config;
import com.hazelcast.config.FileSystemXmlConfig;
import com.hazelcast.core.*;
import com.xiaoleilu.loServer.LoServer;
import com.xiaoleilu.loServer.ServerSetting;
import com.xiaoleilu.loServer.action.admin.AdminAction;
import io.moquette.BrokerConstants;
import io.moquette.persistence.*;
import io.moquette.connections.IConnectionsManager;
import io.moquette.interception.*;
import io.moquette.server.config.*;
import io.moquette.server.netty.NettyAcceptor;
import io.moquette.spi.IStore;
import io.moquette.spi.impl.ProtocolProcessor;
import io.moquette.spi.impl.ProtocolProcessorBootstrapper;
import io.moquette.spi.impl.security.AES;
import io.moquette.spi.security.IAuthenticator;
import io.moquette.spi.security.IAuthorizator;
import io.moquette.spi.security.ISslContextCreator;
import io.moquette.spi.security.Tokenor;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.internal.StringUtil;
import win.liyufan.im.DBUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import win.liyufan.im.MessageShardingUtil;
import win.liyufan.im.Utility;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;

import static io.moquette.BrokerConstants.*;
import static io.moquette.logging.LoggingUtils.getInterceptorIds;

/**
 * Launch a configured version of the server.
 */
public class Server {
    private final static String BANNER =
        "            _  _      _   __  _                    _             _   \n" +
            " __      __(_)| |  __| | / _|(_) _ __  ___    ___ | |__    __ _ | |_ \n" +
            " \\ \\ /\\ / /| || | / _` || |_ | || '__|/ _ \\  / __|| '_ \\  / _` || __|\n" +
            "  \\ V  V / | || || (_| ||  _|| || |  |  __/ | (__ | | | || (_| || |_ \n" +
            "   \\_/\\_/  |_||_| \\__,_||_|  |_||_|   \\___|  \\___||_| |_| \\__,_| \\__|\n";


    private static final Logger logger = LoggerFactory.getLogger(Server.class);

    private static Server instance;

    public static Server getServer() {
        return instance;
    }

    private ServerAcceptor m_acceptor;

    private boolean m_shutdowning = false;

    public volatile boolean m_initialized;

    private ProtocolProcessor m_processor;

    private HazelcastInstance hazelcastInstance;

    private ProtocolProcessorBootstrapper m_processorBootstrapper;

    private ThreadPoolExecutorWrapper dbScheduler;  // 数据库线程池
    private ThreadPoolExecutorWrapper imBusinessScheduler; // 业务线程池

    private IConfig mConfig;

    private IStore m_store;

    static {
        logger.debug("打印banner");
        System.out.println(BANNER);
    }

    public static void start(String[] args) throws IOException {

        logger.debug("start ...");

        instance = new Server(); // 默认调用无参构造函数
        final IConfig config = defaultConfig();  // 解析配置文件

        System.setProperty("hazelcast.logging.type", "none");
        instance.mConfig = config;
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.ADVANCED); // 内存泄露级别

        logger.debug("准备启动服务器...");
        instance.startServer(config);

        logger.debug("准备开启网络端口与管理端口...");
        int httpLocalPort = Integer.parseInt(config.getProperty(BrokerConstants.HTTP_LOCAL_PORT));
        int httpAdminPort = Integer.parseInt(config.getProperty(BrokerConstants.HTTP_ADMIN_PORT));

        AdminAction.setSecretKey(config.getProperty(HTTP_SERVER_SECRET_KEY));
        AdminAction.setNoCheckTime(config.getProperty(HTTP_SERVER_API_NO_CHECK_TIME));

        logger.debug("启动主服务对象...");
        final LoServer httpServer = new LoServer(httpLocalPort, httpAdminPort, instance.m_processor.getMessagesStore(), instance.m_store.sessionsStore());
        try {
            httpServer.start();
        } catch (Exception e) {
            logger.error("主服务对象启动出错了:", e);
            e.printStackTrace();
            Utility.printExecption(logger, e);
        }

        final PushServer pushServer = PushServer.getServer();
        pushServer.init(config, instance.getStore().sessionsStore());

        //Bind  a shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(instance::stopServer));
        Runtime.getRuntime().addShutdownHook(new Thread(httpServer::shutdown));

        logger.debug("Wildfire IM server start success...");
        System.out.println("Wildfire IM server start success...");
    }

    /**
     * Starts Moquette bringing the configuration from the file located at m_config/wildfirechat.conf
     *
     * @throws IOException in case of any IO error.
     */
    public void startServer() throws IOException {
        final IConfig config = defaultConfig();
        startServer(config);
    }

    public static IConfig defaultConfig() {
        File defaultConfigurationFile = defaultConfigFile(); // 从系统参数中获取配置文件目录
        logger.info("Starting Moquette server. Configuration file path={}", defaultConfigurationFile.getAbsolutePath());
        IResourceLoader filesystemLoader = new FileResourceLoader(defaultConfigurationFile);
        return new ResourceLoaderConfig(filesystemLoader);
    }

    private static File defaultConfigFile() {
        String configPath = System.getProperty("wildfirechat.path", null);
        return new File(configPath, IConfig.DEFAULT_CONFIG);
    }


    /**
     * Starts Moquette bringing the configuration from the given file
     *
     * @param configFile text file that contains the configuration.
     * @throws IOException in case of any IO Error.
     */
    public void startServer(File configFile) throws IOException {
        logger.info("Starting Moquette server. Configuration file path={}", configFile.getAbsolutePath());
        IResourceLoader filesystemLoader = new FileResourceLoader(configFile);
        final IConfig config = new ResourceLoaderConfig(filesystemLoader);
        startServer(config);
    }

    /**
     * Starts the server with the given properties.
     *
     * @param configProps the properties map to use as configuration.
     * @throws IOException in case of any IO Error.
     */
    public void startServer(Properties configProps) throws IOException {
        logger.info("Starting Moquette server using properties object");
        final IConfig config = new MemoryConfig(configProps);
        startServer(config);
    }

    /**
     * Starts Moquette bringing the configuration files from the given Config implementation.
     *
     * @param config the configuration to use to start the broker.
     * @throws IOException in case of any IO Error.
     */
    public void startServer(IConfig config) throws IOException {
        logger.info("Starting Moquette server using IConfig instance...");
        startServer(config, null);
    }

    /**
     * Starts Moquette with config provided by an implementation of IConfig class and with the set
     * of InterceptHandler.
     *
     * @param config   the configuration to use to start the broker.
     * @param handlers the handlers to install in the broker.
     * @throws IOException in case of any IO Error.
     */
    public void startServer(IConfig config, List<? extends InterceptHandler> handlers) throws IOException {
        logger.info("Starting moquette server using IConfig instance and intercept handlers");
        startServer(config, handlers, null, null, null);
    }

    public void startServer(IConfig config, List<? extends InterceptHandler> handlers, ISslContextCreator sslCtxCreator,
                            IAuthenticator authenticator, IAuthorizator authorizator) throws IOException {
        if (handlers == null) {
            handlers = Collections.emptyList();
        }
        DBUtil.init(config); // 初始化数据库

        logger.debug("解析客户端协议栈密钥...");
        String strKey = config.getProperty(BrokerConstants.CLIENT_PROTO_SECRET_KEY); // 客户端协议栈密钥
        String[] strs = strKey.split(",");
        if (strs.length != 16) {
            logger.error("Key error, it length should be 16");
        }
        byte[] keys = new byte[16];
        for (int i = 0; i < 16; i++) {
            keys[i] = (byte) (Integer.parseInt(strs[i].replace("0X", "").replace("0x", ""), 16));
        }

        AES.init(keys);
        logger.debug("完成客户端协议栈密钥解析");

        // 输出消息拦截器ID
        logger.info("Starting Moquette Server. MQTT message interceptors={}", getInterceptorIds(handlers));

        int threadNum = Runtime.getRuntime().availableProcessors() * 2; // 计算线程数量
        // 数据库线程池
        dbScheduler = new ThreadPoolExecutorWrapper(Executors.newScheduledThreadPool(threadNum), threadNum, "db");
        // 业务线城市
        imBusinessScheduler = new ThreadPoolExecutorWrapper(Executors.newScheduledThreadPool(threadNum), threadNum, "business");

        logger.debug("配置拦截器...");
        final String handlerProp = System.getProperty(BrokerConstants.INTERCEPT_HANDLER_PROPERTY_NAME);
        if (handlerProp != null) {
            config.setProperty(BrokerConstants.INTERCEPT_HANDLER_PROPERTY_NAME, handlerProp);
        }
        logger.debug("完成拦截器配置");

        logger.debug("初始化服务器属性信息...");
        initMediaServerConfig(config); // 初始化服务器属性信息

        final String persistencePath = config.getProperty(BrokerConstants.PERSISTENT_STORE_PROPERTY_NAME);
        logger.info("Configuring Using persistent store file, path={}", persistencePath);
        m_store = initStore(config, this); // 在内存中存储 消息对象与会话对象
        m_processorBootstrapper = new ProtocolProcessorBootstrapper(); // 协议执行对象

        logger.info("构建分布式内存集群...");
        boolean configured = configureCluster(config);

        m_store.initStore();
        final ProtocolProcessor processor = m_processorBootstrapper.init(config, handlers, authenticator, authorizator,
            this, m_store);
        logger.info("Initialized MQTT protocol processor");
        if (sslCtxCreator == null) {
            logger.warn("Using default SSL context creator");
            sslCtxCreator = new DefaultMoquetteSslContextCreator(config);
        }

        m_processor = processor;

        logger.info("Binding server to the configured ports");
        m_acceptor = new NettyAcceptor();
        m_acceptor.initialize(processor, config, sslCtxCreator);


        logger.info("Moquette server has been initialized successfully");
        m_initialized = configured;
    }

    private IStore initStore(IConfig props, Server server) {
        logger.info("Initializing messages and sessions stores...");
        IStore store = instantiateConfiguredStore(props, server.getDbScheduler(), server);
        if (store == null) {
            throw new IllegalArgumentException("Can't start the persistence layer");
        }
        return store;
    }

    private IStore instantiateConfiguredStore(IConfig props,
                                              ThreadPoolExecutorWrapper scheduledExecutor, Server server) {
        return new MemoryStorageService(props, scheduledExecutor, server);
    }

    public IStore getStore() {
        return m_store;
    }

    private void initMediaServerConfig(IConfig config) {
        MediaServerConfig.QINIU_ACCESS_KEY = config.getProperty(BrokerConstants.QINIU_ACCESS_KEY, MediaServerConfig.QINIU_ACCESS_KEY);
        MediaServerConfig.QINIU_SECRET_KEY = config.getProperty(BrokerConstants.QINIU_SECRET_KEY, MediaServerConfig.QINIU_SECRET_KEY);
        MediaServerConfig.QINIU_SERVER_URL = config.getProperty(BrokerConstants.QINIU_SERVER_URL, MediaServerConfig.QINIU_SERVER_URL);
        if (MediaServerConfig.QINIU_SERVER_URL.contains("//")) {
            MediaServerConfig.QINIU_SERVER_URL = MediaServerConfig.QINIU_SERVER_URL.substring(MediaServerConfig.QINIU_SERVER_URL.indexOf("//") + 2);
        }

        MediaServerConfig.QINIU_BUCKET_GENERAL_NAME = config.getProperty(BrokerConstants.QINIU_BUCKET_GENERAL_NAME);
        MediaServerConfig.QINIU_BUCKET_GENERAL_DOMAIN = config.getProperty(BrokerConstants.QINIU_BUCKET_GENERAL_DOMAIN);

        MediaServerConfig.QINIU_BUCKET_IMAGE_NAME = config.getProperty(BrokerConstants.QINIU_BUCKET_IMAGE_NAME);
        MediaServerConfig.QINIU_BUCKET_IMAGE_DOMAIN = config.getProperty(BrokerConstants.QINIU_BUCKET_IMAGE_DOMAIN);

        MediaServerConfig.QINIU_BUCKET_VOICE_NAME = config.getProperty(BrokerConstants.QINIU_BUCKET_VOICE_NAME);
        MediaServerConfig.QINIU_BUCKET_VOICE_DOMAIN = config.getProperty(BrokerConstants.QINIU_BUCKET_VOICE_DOMAIN);

        MediaServerConfig.QINIU_BUCKET_VIDEO_NAME = config.getProperty(BrokerConstants.QINIU_BUCKET_VIDEO_NAME);
        MediaServerConfig.QINIU_BUCKET_VIDEO_DOMAIN = config.getProperty(BrokerConstants.QINIU_BUCKET_VIDEO_DOMAIN);

        MediaServerConfig.QINIU_BUCKET_FILE_NAME = config.getProperty(BrokerConstants.QINIU_BUCKET_FILE_NAME);
        MediaServerConfig.QINIU_BUCKET_FILE_DOMAIN = config.getProperty(BrokerConstants.QINIU_BUCKET_FILE_DOMAIN);

        MediaServerConfig.QINIU_BUCKET_STICKER_NAME = config.getProperty(BrokerConstants.QINIU_BUCKET_STICKER_NAME);
        MediaServerConfig.QINIU_BUCKET_STICKER_DOMAIN = config.getProperty(BrokerConstants.QINIU_BUCKET_STICKER_DOMAIN);

        MediaServerConfig.QINIU_BUCKET_MOMENTS_NAME = config.getProperty(BrokerConstants.QINIU_BUCKET_MOMENTS_NAME);
        MediaServerConfig.QINIU_BUCKET_MOMENTS_DOMAIN = config.getProperty(BrokerConstants.QINIU_BUCKET_MOMENTS_DOMAIN);

        MediaServerConfig.QINIU_BUCKET_PORTRAIT_NAME = config.getProperty(BrokerConstants.QINIU_BUCKET_PORTRAIT_NAME);
        MediaServerConfig.QINIU_BUCKET_PORTRAIT_DOMAIN = config.getProperty(BrokerConstants.QINIU_BUCKET_PORTRAIT_DOMAIN);

        MediaServerConfig.QINIU_BUCKET_FAVORITE_NAME = config.getProperty(BrokerConstants.QINIU_BUCKET_FAVORITE_NAME);
        MediaServerConfig.QINIU_BUCKET_FAVORITE_DOMAIN = config.getProperty(BrokerConstants.QINIU_BUCKET_FAVORITE_DOMAIN);


        MediaServerConfig.SERVER_IP = getServerIp(config);

        MediaServerConfig.HTTP_SERVER_PORT = Integer.parseInt(config.getProperty(BrokerConstants.HTTP_SERVER_PORT));

        MediaServerConfig.FILE_STROAGE_ROOT = config.getProperty(BrokerConstants.FILE_STORAGE_ROOT, MediaServerConfig.FILE_STROAGE_ROOT);
        File file = new File(MediaServerConfig.FILE_STROAGE_ROOT);
        if (!file.exists()) {
            file.mkdirs();
        }
        ServerSetting.setRoot(file);

        MediaServerConfig.USER_QINIU = Integer.parseInt(config.getProperty(BrokerConstants.USER_QINIU)) > 0;
    }

    private String getServerIp(IConfig config) {
        String serverIp = config.getProperty(BrokerConstants.SERVER_IP_PROPERTY_NAME);

        if (serverIp == null || serverIp.equals("0.0.0.0")) {
            serverIp = Utility.getLocalAddress().getHostAddress();
        }
        return serverIp;
    }

    private boolean configureCluster(IConfig config) throws FileNotFoundException {
        logger.info("Configuring embedded Hazelcast instance");
        String serverIp = getServerIp(config); // 获取服务器ip

        String sysConfigPath = System.getProperty(BrokerConstants.HAZELCAST_CONFIGURATION, null);
        String bizConfigPath = config.getProperty(BrokerConstants.HAZELCAST_CONFIGURATION); // 获取配置文件位置
        String hzConfigPath = sysConfigPath + File.separator + bizConfigPath;

        String hzClientIp = config.getProperty(BrokerConstants.HAZELCAST_CLIENT_IP, "localhost");
        String hzClientPort = config.getProperty(BrokerConstants.HAZELCAST_CLIENT_PORT, "5703");

        if (hzConfigPath != null) {
            logger.debug("hazelcast配置文件位置:{}", hzConfigPath);
            boolean isHzConfigOnClasspath = this.getClass().getClassLoader().getResource(hzConfigPath) != null;
            Config hzconfig = isHzConfigOnClasspath
                ? new ClasspathXmlConfig(hzConfigPath)
                : new FileSystemXmlConfig(hzConfigPath);
            logger.info("Starting Hazelcast instance. ConfigurationFile={}", hzconfig);
            hazelcastInstance = Hazelcast.newHazelcastInstance(hzconfig);
        } else {
            logger.info("Starting Hazelcast instance with default configuration");
            hazelcastInstance = Hazelcast.newHazelcastInstance();
        }


        String longPort = config.getProperty(BrokerConstants.PORT_PROPERTY_NAME);
        String shortPort = config.getProperty(BrokerConstants.HTTP_SERVER_PORT); // http 80端口
        String nodeIdStr = config.getProperty(BrokerConstants.NODE_ID);
        ISet<Integer> nodeIdSet = hazelcastInstance.getSet(BrokerConstants.NODE_IDS);
        int nodeId;
        try {
            nodeId = Integer.parseInt(nodeIdStr);
        } catch (Exception e) {
            throw new IllegalArgumentException("nodeId error: " + nodeIdStr);
        }
        if (nodeIdSet != null && nodeIdSet.contains(nodeId)) {
            logger.error("只允许一个实例运行，多个实例会引起冲突，进程终止");
            System.exit(-1);
        }

        MessageShardingUtil.setNodeId(nodeId);
        nodeIdSet.add(nodeId);

        hazelcastInstance.getCluster().getLocalMember().setStringAttribute(HZ_Cluster_Node_External_Long_Port, longPort);
        hazelcastInstance.getCluster().getLocalMember().setStringAttribute(HZ_Cluster_Node_External_Short_Port, shortPort);
        hazelcastInstance.getCluster().getLocalMember().setIntAttribute(HZ_Cluster_Node_ID, nodeId);
        hazelcastInstance.getCluster().getLocalMember().setStringAttribute(HZ_Cluster_Node_External_IP, serverIp);
        Tokenor.setKey(config.getProperty(BrokerConstants.TOKEN_SECRET_KEY));
        String expirTimeStr = config.getProperty(TOKEN_EXPIRE_TIME);
        if (!StringUtil.isNullOrEmpty(expirTimeStr)) {
            try {
                Tokenor.setExpiredTime(Long.parseLong(expirTimeStr));
            } catch (NumberFormatException e) {
                e.printStackTrace();
            }
        }

        logger.debug("初始化远程调用...");
        RPCCenter.getInstance().init(this);
        return true;
    }

    public HazelcastInstance getHazelcastInstance() {
        return hazelcastInstance;
    }

    public void internalRpcMsg(String fromUser, String clientId, byte[] message, int messageId, String from, String request, boolean isAdmin) {

        if (!m_initialized) {
            logger.error("Moquette is not started, internal message cannot be notify");
            return;
        }
        logger.debug("internalNotifyMsg");
        m_processor.onRpcMsg(fromUser, clientId, message, messageId, from, request, isAdmin);
    }

    public boolean isShutdowning() {
        return m_shutdowning;
    }

    public void stopServer() {
        System.out.println("Server will flush data to db before shutting down, please wait 5 seconds!");
        logger.info("Unbinding server from the configured ports");
        m_shutdowning = true;

        m_acceptor.close();
        logger.trace("Stopping MQTT protocol processor");
        m_processorBootstrapper.shutdown();
        m_initialized = false;
        if (hazelcastInstance != null) {
            logger.trace("Stopping embedded Hazelcast instance");
            try {
                hazelcastInstance.shutdown();
            } catch (HazelcastInstanceNotActiveException e) {
                logger.warn("embedded Hazelcast instance is already shut down.");
            }
        }

        dbScheduler.shutdown();
        imBusinessScheduler.shutdown();

        logger.info("Moquette server has been stopped.");
    }

    /**
     * SPI method used by Broker embedded applications to add intercept handlers.
     *
     * @param interceptHandler the handler to add.
     */
    public void addInterceptHandler(InterceptHandler interceptHandler) {
        if (!m_initialized) {
            logger.error("Moquette is not started, MQTT message interceptor cannot be added. InterceptorId={}",
                interceptHandler.getID());
            throw new IllegalStateException("Can't register interceptors on a server that is not yet started");
        }
        logger.info("Adding MQTT message interceptor. InterceptorId={}", interceptHandler.getID());
        m_processor.addInterceptHandler(interceptHandler);
    }

    /**
     * SPI method used by Broker embedded applications to remove intercept handlers.
     *
     * @param interceptHandler the handler to remove.
     */
    public void removeInterceptHandler(InterceptHandler interceptHandler) {
        if (!m_initialized) {
            logger.error("Moquette is not started, MQTT message interceptor cannot be removed. InterceptorId={}",
                interceptHandler.getID());
            throw new IllegalStateException("Can't deregister interceptors from a server that is not yet started");
        }
        logger.info("Removing MQTT message interceptor. InterceptorId={}", interceptHandler.getID());
        m_processor.removeInterceptHandler(interceptHandler);
    }

    public IConfig getConfig() {
        return mConfig;
    }

    /**
     * Returns the connections manager of this broker.
     *
     * @return IConnectionsManager the instance used bt the broker.
     */
    public IConnectionsManager getConnectionsManager() {
        return m_processorBootstrapper.getConnectionDescriptors();
    }

    public ProtocolProcessor getProcessor() {
        return m_processor;
    }

    public ThreadPoolExecutorWrapper getDbScheduler() {
        return dbScheduler;
    }

    public ThreadPoolExecutorWrapper getImBusinessScheduler() {
        return imBusinessScheduler;
    }
}
