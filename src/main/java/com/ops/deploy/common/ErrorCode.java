package com.ops.deploy.common;

/**
 * 业务错误码常量。code 与 HTTP 语义对齐（401/403/404 等），
 * 5xxx 为平台自定义业务错误码。
 */
public final class ErrorCode {

    public static final int UNAUTHORIZED = 401;
    public static final int FORBIDDEN = 403;
    public static final int NOT_FOUND = 404;
    public static final int BAD_REQUEST = 400;
    public static final int CONFLICT = 409;
    public static final int SERVER_ERROR = 500;

    public static final int DEPLOY_FAILED = 5001;
    public static final int SSH_ERROR = 5002;
    public static final int CREDENTIAL_ERROR = 5003;
    public static final int TYPE_DETECT_FAILED = 5004;
    public static final int LLM_ERROR = 5005;

    private ErrorCode() {
    }
}
