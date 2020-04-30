/*
 * This file is part of the Wildfire Chat package.
 * (c) Heavyrain2012 <heavyrain.lee@gmail.com>
 *
 * For the full copyright and license information, please view the LICENSE
 * file that was distributed with this source code.
 */

package cn.wildfirechat.server;

import org.apache.log4j.PropertyConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class Server {
    private static final Logger logger = LoggerFactory.getLogger(Server.class);

    public static void main(String[] args) {
        System.out.println("console debug start...");

        // 日志文件配置
        // 系统配置文件位置
        // System.setProperty("log4j.configuration","file:D:\\wayaya\\study\\wayaya_github\\server\\distribution\\src\\main\\resources\\log4j.properties");
        /* 指定配置文件位置
        String configPath = "D:\\wayaya\\study\\wayaya_github\\server\\distribution\\src\\main\\resources\\log4j.properties";
        PropertyConfigurator.configure(configPath);
        //*/

        logger.debug("start...");

        // 打印参数
        logger.debug("args length:" + args.length);

        // IM配置文件位置
        // System.setProperty("wildfirechat.path","D:\\wayaya\\study\\wayaya_github\\server\\broker\\config\\")
        // 分布式内存管理配置文件位置
        // System.setProperty("hazelcast.configuration","D:\\wayaya\\study\\wayaya_github\\server\\distribution\\src\\main\\resources\\hazelcast.xml")

        // 数据库连接池配置文件
        // System.setProperty("com.mchange.v2.c3p0.cfg.xml","D:\\wayaya\\study\\wayaya_github\\server\\distribution\\src\\main\\resources\\c3p0-config.xml");

        try {
            io.moquette.server.Server.start(args);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
