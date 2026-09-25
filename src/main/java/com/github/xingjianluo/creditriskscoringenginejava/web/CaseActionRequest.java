package com.github.xingjianluo.creditriskscoringenginejava.web;

import com.github.xingjianluo.creditriskscoringenginejava.domain.CaseAction;
import com.github.xingjianluo.creditriskscoringenginejava.domain.Role;

/**
 * 案件处置请求：动作、角色、操作人、原因。
 */
public record CaseActionRequest(
        CaseAction action,
        Role role,
        String actor,
        String reason
) {
}
