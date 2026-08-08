package com.hypernick.data;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 数据库管理器 — 支持 SQLite (默认) 和 MySQL.
 * <p>
 * Paper 服务端内置 SQLite 和 MySQL JDBC 驱动, 无需额外打包.
 * 表结构: hypernick_data (uuid, nick, rank, original, setAt, fakeUuid, skinMode, lastNick, lastRank)
 */
public class DatabaseManager {

    private final JavaPlugin plugin;
    private Connection connection;
    private boolean useMySQL;

    public DatabaseManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void connect() throws SQLException {
        String type = plugin.getConfig().getString("database.type", "sqlite").toLowerCase();
        useMySQL = type.equals("mysql");

        if (useMySQL) {
            String host = plugin.getConfig().getString("database.mysql.host", "localhost");
            int port = plugin.getConfig().getInt("database.mysql.port", 3306);
            String database = plugin.getConfig().getString("database.mysql.database", "minecraft");
            String username = plugin.getConfig().getString("database.mysql.username", "root");
            String password = plugin.getConfig().getString("database.mysql.password", "");
            boolean ssl = plugin.getConfig().getBoolean("database.mysql.ssl", false);
            String url = String.format("jdbc:mysql://%s:%d/%s?useSSL=%s&autoReconnect=true&useUnicode=true&characterEncoding=UTF-8",
                    host, port, database, ssl);
            try {
                Class.forName("com.mysql.cj.jdbc.Driver");
            } catch (ClassNotFoundException e) {
                try {
                    Class.forName("com.mysql.jdbc.Driver");
                } catch (ClassNotFoundException e2) {
                    throw new SQLException("MySQL JDBC driver not found", e2);
                }
            }
            connection = DriverManager.getConnection(url, username, password);
            plugin.getLogger().info("已连接到 MySQL 数据库: " + host + ":" + port + "/" + database);
        } else {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            String dbPath = new File(plugin.getDataFolder(), "hypernick.db").getPath();
            String url = "jdbc:sqlite:" + dbPath;
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                throw new SQLException("SQLite JDBC driver not found", e);
            }
            connection = DriverManager.getConnection(url);
            plugin.getLogger().info("已连接到 SQLite 数据库: " + dbPath);
        }

        createTable();
    }

    private void createTable() throws SQLException {
        String sql;
        if (useMySQL) {
            sql = "CREATE TABLE IF NOT EXISTS hypernick_data (" +
                    "uuid VARCHAR(36) PRIMARY KEY," +
                    "nick VARCHAR(64)," +
                    "rank VARCHAR(64)," +
                    "original VARCHAR(64)," +
                    "setAt BIGINT," +
                    "fakeUuid VARCHAR(36)," +
                    "skinMode VARCHAR(16) DEFAULT 'REAL'," +
                    "lastNick VARCHAR(64)," +
                    "lastRank VARCHAR(64)" +
                    ") DEFAULT CHARSET=utf8mb4";
        } else {
            sql = "CREATE TABLE IF NOT EXISTS hypernick_data (" +
                    "uuid TEXT PRIMARY KEY," +
                    "nick TEXT," +
                    "rank TEXT," +
                    "original TEXT," +
                    "setAt INTEGER," +
                    "fakeUuid TEXT," +
                    "skinMode TEXT DEFAULT 'REAL'," +
                    "lastNick TEXT," +
                    "lastRank TEXT" +
                    ")";
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }

    public Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connect();
        }
        return connection;
    }

    public void close() {
        if (connection != null) {
            try {
                connection.close();
                plugin.getLogger().info("数据库连接已关闭.");
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "关闭数据库连接时出错", e);
            }
        }
    }

    public boolean isMySQL() {
        return useMySQL;
    }

    public Logger getLogger() {
        return plugin.getLogger();
    }
}
