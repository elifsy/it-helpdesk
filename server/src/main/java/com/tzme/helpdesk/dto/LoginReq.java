package com.tzme.helpdesk.dto;

import lombok.Data;

@Data
public class LoginReq {
    /** 钉钉 JSAPI 免登拿到的临时授权码；演示模式下可为空 */
    private String authCode;
    /** 仅演示模式：指定演示身份（如 王敏 / 李强 / 陈静） */
    private String demoUser;
}
