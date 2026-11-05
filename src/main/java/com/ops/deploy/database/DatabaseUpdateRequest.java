package com.ops.deploy.database;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 更新数据库登记请求。全部字段可空 = 不修改。
 * password 可空：null/空 = 保留原凭证；提供新值 = 重新加密。
 * toString 中密码打码。
 */
public class DatabaseUpdateRequest {

    @Size(max = 64)
    private String name;

    @Size(max = 16)
    private String dbType;

    @Size(max = 128)
    private String host;

    @Min(1)
    @Max(65535)
    private Integer port;

    @Size(max = 128)
    private String databaseName;

    @Size(max = 128)
    private String username;

    private String password;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDbType() {
        return dbType;
    }

    public void setDbType(String dbType) {
        this.dbType = dbType;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    @Override
    public String toString() {
        return "DatabaseUpdateRequest{name=" + name
                + ", dbType=" + dbType + ", host=" + host
                + ", port=" + port + ", databaseName=" + databaseName
                + ", username=" + username + ", password=***" + "}";
    }
}
