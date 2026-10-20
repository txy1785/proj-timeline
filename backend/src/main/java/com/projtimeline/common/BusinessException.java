package com.projtimeline.common;

import org.springframework.http.HttpStatus;

public class BusinessException extends RuntimeException {
    private final int code;
    private final HttpStatus status;

    public BusinessException(int code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public int code() { return code; }
    public HttpStatus status() { return status; }

    public static BusinessException badRequest(String message) { return new BusinessException(4000, HttpStatus.BAD_REQUEST, message); }
    public static BusinessException unauthorized(String message) { return new BusinessException(4001, HttpStatus.UNAUTHORIZED, message); }
    public static BusinessException forbidden(String message) { return new BusinessException(4003, HttpStatus.FORBIDDEN, message); }
    public static BusinessException notFound(String message) { return new BusinessException(4004, HttpStatus.NOT_FOUND, message); }
    public static BusinessException conflict(String message) { return new BusinessException(4090, HttpStatus.CONFLICT, message); }
    public static BusinessException internal(String message) { return new BusinessException(5000, HttpStatus.INTERNAL_SERVER_ERROR, message); }
}
