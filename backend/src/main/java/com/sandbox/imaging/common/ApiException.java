package com.sandbox.imaging.common;

/** 业务规则异常（越权、超范围、阻断等），由全局处理器转为 4xx 响应 */
public class ApiException extends RuntimeException {
    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public static ApiException badRequest(String msg) { return new ApiException(400, msg); }
    public static ApiException forbidden(String msg) { return new ApiException(403, msg); }
    public static ApiException notFound(String msg) { return new ApiException(404, msg); }
    public static ApiException conflict(String msg) { return new ApiException(409, msg); }
    public static ApiException gone(String msg) { return new ApiException(410, msg); }

    public int getStatus() { return status; }
}
