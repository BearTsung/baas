package com.trading.platform.exception;

/**
 * 使用者輸入不合法，對應 HTTP 400。
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
