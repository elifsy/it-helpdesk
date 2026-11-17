package com.tzme.helpdesk.security;
import java.lang.annotation.*;
@Target({ElementType.TYPE,ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission { String[] value(); }
