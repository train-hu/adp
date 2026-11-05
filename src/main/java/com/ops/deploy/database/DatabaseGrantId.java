package com.ops.deploy.database;

import java.io.Serializable;

/**
 * 数据库分配联合主键载体（database_id + user_id），供 {@link DatabaseGrantEntity}
 * 的 @IdClass 与 {@link DatabaseGrantRepository} 的复合主键查询使用。
 */
public class DatabaseGrantId implements Serializable {

    private Long databaseId;
    private Long userId;

    public DatabaseGrantId() {
    }

    public DatabaseGrantId(Long databaseId, Long userId) {
        this.databaseId = databaseId;
        this.userId = userId;
    }

    public Long getDatabaseId() {
        return databaseId;
    }

    public void setDatabaseId(Long databaseId) {
        this.databaseId = databaseId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DatabaseGrantId)) {
            return false;
        }
        DatabaseGrantId that = (DatabaseGrantId) o;
        return databaseId != null && databaseId.equals(that.databaseId)
                && userId != null && userId.equals(that.userId);
    }

    @Override
    public int hashCode() {
        return (databaseId == null ? 0 : databaseId.hashCode())
                * 31 + (userId == null ? 0 : userId.hashCode());
    }
}
