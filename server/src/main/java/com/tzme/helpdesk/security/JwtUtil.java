package com.tzme.helpdesk.security;

import com.tzme.helpdesk.config.HelpdeskProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {
    private final SecretKey key;
    private final long expireMillis;
    private final HelpdeskProperties props;

    public JwtUtil(HelpdeskProperties props) {
        this.props = props;
        this.key = Keys.hmacShaKeyFor(props.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
        this.expireMillis = props.getJwt().getExpireHours() * 3600_000L;
    }

    public String create(LoginUser user) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(user.id()))
                .claim("name", user.name())
                .claim("role", user.role())
                .claim("dept", user.deptName())
                .claim("loginMode", props.getDingtalk().isEnabled() ? "DINGTALK" : "DEMO")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMillis))
                .signWith(key)
                .compact();
    }

    public LoginUser parse(String token) {
        Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        if (props.getDingtalk().isEnabled() && !"DINGTALK".equals(c.get("loginMode", String.class)))
            throw new io.jsonwebtoken.JwtException("登录方式已变更，请重新登录");
        return new LoginUser(
                Long.valueOf(c.getSubject()),
                c.get("name", String.class),
                c.get("role", String.class),
                c.get("dept", String.class));
    }
}
