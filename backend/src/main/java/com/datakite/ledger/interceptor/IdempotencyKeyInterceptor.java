package com.datakite.ledger.interceptor;

import com.datakite.ledger.exception.MissingIdempotencyKeyException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces the mandatory Idempotency-Key header on mutating ledger
 * endpoints before a request ever reaches controller/validation logic.
 *
 * Must let OPTIONS through unconditionally: a browser's CORS preflight for
 * POST /transfer is itself an OPTIONS request, sent without the
 * application's Idempotency-Key header (only the real follow-up request
 * carries it). Enforcing the header here on the preflight turns it into a
 * failing response, which the browser reports as a CORS error - discovered
 * by actually driving the dashboard in a browser, since curl doesn't
 * perform preflight and never hit this path.
 */
@Component
public class IdempotencyKeyInterceptor implements HandlerInterceptor {

    public static final String HEADER_NAME = "Idempotency-Key";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String key = request.getHeader(HEADER_NAME);
        if (key == null || key.isBlank()) {
            throw new MissingIdempotencyKeyException();
        }
        return true;
    }
}
