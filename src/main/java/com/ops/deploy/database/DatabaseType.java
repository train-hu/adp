package com.ops.deploy.database;

/**
 * 数据库类型。实体中以 String 存储（name()）。
 *
 * <p>各类型默认端口：MYSQL 3306 / POSTGRESQL 5432 / REDIS 6379，
 * 创建登记时未显式指定端口则按类型取默认值。
 */
public enum DatabaseType {
    MYSQL(3306),
    POSTGRESQL(5432),
    REDIS(6379);

    private final int defaultPort;

    DatabaseType(int defaultPort) {
        this.defaultPort = defaultPort;
    }

    public int getDefaultPort() {
        return defaultPort;
    }

    public static DatabaseType parse(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        for (DatabaseType t : values()) {
            if (t.name().equalsIgnoreCase(value)) {
                return t;
            }
        }
        return null;
    }
}
