package com.example.ordermanagement.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Collections;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.client.*;
import org.springframework.web.filter.OncePerRequestFilter;

/** Uses the same BTH2 validation contract as BTH3. */
public class JwtAuthenticationFilter extends OncePerRequestFilter {
  private final RestClient auth;

  public JwtAuthenticationFilter(String authUrl) {
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(5));
    auth = RestClient.builder().baseUrl(authUrl).requestFactory(factory).build();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String header = req.getHeader(HttpHeaders.AUTHORIZATION);
    if (header != null && header.startsWith("Bearer ") && header.length() > 7) {
      try {
        auth.get()
            .uri("/auth")
            .header(HttpHeaders.AUTHORIZATION, header)
            .retrieve()
            .toBodilessEntity();
        var authentication =
            new UsernamePasswordAuthenticationToken(
                "authenticated-user", null, Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(authentication);
      } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden ex) {
        SecurityContextHolder.clearContext();
      } catch (RestClientException ex) {
        res.sendError(503, "Authentication service unavailable");
        return;
      }
    }
    chain.doFilter(req, res);
  }
}
