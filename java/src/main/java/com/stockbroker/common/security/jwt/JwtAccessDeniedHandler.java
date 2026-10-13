package com.stockbroker.common.security.jwt;

import com.stockbroker.common.error.BusinessProblem;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Handler for access denied (authorization failures).
 * Returns a Problem+JSON response with 403 status.
 */
@Component
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException, ServletException {

        BusinessProblem problem = new BusinessProblem.AuthInsufficientPermissions("USER");

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(toJson(problem));
    }

    private String toJson(BusinessProblem problem) {
        return """
                {
                  "type": "%s",
                  "title": "%s",
                  "status": %d,
                  "detail": "%s",
                  "code": "%s"
                }
                """.formatted(
                problem.getType().toString(),
                problem.getTitle(),
                problem.getStatus().getStatusCode(),
                problem.getDetail().replace("\"", "\\\""),
                problem.getCode()
        );
    }
}