package com.github.xingjianluo.creditriskscoringenginejava.web;

/**
 * 统一错误响应体，code 为可区分错误原因。
 */
public record ErrorBody(String code, String message) {
}
