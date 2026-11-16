package com.example.ordermanagement;

import static org.junit.jupiter.api.Assertions.*;

import com.example.ordermanagement.repository.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.*;
import org.springframework.web.client.RestClient;

/**
 * Real HTTP requests through security, controllers, transactions and H2. BTH2/BTH3 are HTTP stubs
 * so the suite runs without external services.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OrderApiTest {
  static final HttpServer upstream;
  static volatile boolean unavailable;
  static volatile String forwarded;

  static {
    try {
      upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      upstream.createContext(
          "/auth",
          x -> {
            String token = x.getRequestHeaders().getFirst("Authorization");
            x.sendResponseHeaders("Bearer test-token".equals(token) ? 200 : 401, -1);
            x.close();
          });
      upstream.createContext(
          "/products/",
          x -> {
            forwarded = x.getRequestHeaders().getFirst("Authorization");
            int status =
                unavailable ? 503 : x.getRequestURI().getPath().endsWith("/999") ? 404 : 200;
            byte[] body =
                "{\"id\":1,\"name\":\"Keyboard\",\"price\":100000,\"quantity\":5}".getBytes();
            x.getResponseHeaders().set("Content-Type", "application/json");
            x.sendResponseHeaders(status, body.length);
            x.getResponseBody().write(body);
            x.close();
          });
      upstream.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    String url = "http://127.0.0.1:" + upstream.getAddress().getPort();
    r.add("auth.service.base-url", () -> url);
    r.add("app.product.url", () -> url);
  }

  @LocalServerPort int port;
  @Autowired OrderRepository orders;
  @Autowired OrderItemRepository items;
  RestClient http;

  @BeforeEach
  void setup() {
    items.deleteAll();
    orders.deleteAll();
    unavailable = false;
    forwarded = null;
    http = RestClient.create("http://localhost:" + port);
  }

  @AfterAll
  static void stop() {
    upstream.stop(0);
  }

  ResponseEntity<Map> call(HttpMethod method, String path, String body, String token) {
    var request = http.method(method).uri(path);
    if (token != null) request.header("Authorization", token);
    if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(body);
    return request.exchange(
        (req, res) ->
            ResponseEntity.status(res.getStatusCode())
                .body(res.getStatusCode().value() == 204 ? null : res.bodyTo(Map.class)));
  }

  ResponseEntity<Map> call(HttpMethod method, String path, String body) {
    return call(method, path, body, "Bearer test-token");
  }

  String payload(int quantity) {
    return "{\"customerName\":\"Nguyen Van"
               + " A\",\"customerEmail\":\"a@example.com\",\"items\":[{\"productId\":1,\"quantity\":"
        + quantity
        + "}]}";
  }

  int create() {
    return ((Number) call(HttpMethod.POST, "/orders", payload(2)).getBody().get("id")).intValue();
  }

  @Test
  void requiresAuthentication() {
    assertEquals(401, call(HttpMethod.GET, "/orders", null, null).getStatusCode().value());
    assertEquals(
        401, call(HttpMethod.GET, "/orders", null, "Bearer invalid").getStatusCode().value());
  }

  @Test
  void createsOrderAndForwardsToken() {
    var response = call(HttpMethod.POST, "/orders", payload(2));
    assertEquals(201, response.getStatusCode().value());
    assertEquals(200000, ((Number) response.getBody().get("totalAmount")).intValue());
    assertEquals("pending", response.getBody().get("status"));
    assertEquals("Bearer test-token", forwarded);
    assertEquals(1, orders.count());
    assertEquals(1, items.count());
  }

  @Test
  void rejectsInvalidQuantityAndNullItem() {
    assertEquals(400, call(HttpMethod.POST, "/orders", payload(0)).getStatusCode().value());
    assertEquals(
        400,
        call(
                HttpMethod.POST,
                "/orders",
                "{\"customerName\":\"A\",\"customerEmail\":\"a@b.com\",\"items\":[null]}")
            .getStatusCode()
            .value());
    assertEquals(0, orders.count());
  }

  @Test
  void checksStockWithoutPersistingFailedOrder() {
    assertEquals(409, call(HttpMethod.POST, "/orders", payload(6)).getStatusCode().value());
    assertEquals(0, orders.count());
    assertEquals(0, items.count());
  }

  @Test
  void rejectsUnknownProduct() {
    assertEquals(
        400,
        call(HttpMethod.POST, "/orders", payload(1).replace("\"productId\":1", "\"productId\":999"))
            .getStatusCode()
            .value());
  }

  @Test
  void mapsProductServiceFailure() {
    unavailable = true;
    assertEquals(502, call(HttpMethod.POST, "/orders", payload(1)).getStatusCode().value());
    assertEquals(0, orders.count());
  }

  @Test
  void aggregatesDuplicateProductsBeforeStockCheck() {
    String body = payload(3).replace("}]}", "},{\"productId\":1,\"quantity\":3}]}");
    assertEquals(409, call(HttpMethod.POST, "/orders", body).getStatusCode().value());
    assertEquals(0, orders.count());
  }

  @Test
  void editsAndDeletesItemsRecalculatingTotal() {
    int id = create();
    var added =
        call(
            HttpMethod.POST,
            "/order_items",
            "{\"orderId\":" + id + ",\"productId\":1,\"quantity\":1}");
    assertEquals(201, added.getStatusCode().value());
    int item = ((Number) added.getBody().get("id")).intValue();
    assertEquals(
        200,
        call(HttpMethod.PUT, "/order_items/" + item, "{\"productId\":1,\"quantity\":2}")
            .getStatusCode()
            .value());
    assertEquals(
        400000,
        ((Number) call(HttpMethod.GET, "/orders/" + id, null).getBody().get("totalAmount"))
            .intValue());
    assertEquals(
        204, call(HttpMethod.DELETE, "/order_items/" + item, null).getStatusCode().value());
    assertEquals(
        200000,
        ((Number) call(HttpMethod.GET, "/orders/" + id, null).getBody().get("totalAmount"))
            .intValue());
  }

  @Test
  void preventsAddingMoreThanStockAcrossExistingItems() {
    int id = create();
    assertEquals(
        409,
        call(
                HttpMethod.POST,
                "/order_items",
                "{\"orderId\":" + id + ",\"productId\":1,\"quantity\":4}")
            .getStatusCode()
            .value());
    assertEquals(1, items.count());
  }

  @Test
  void completedOrdersAreImmutable() {
    int id = create();
    assertEquals(
        200,
        call(HttpMethod.PUT, "/orders/" + id, "{\"status\":\"completed\"}")
            .getStatusCode()
            .value());
    assertEquals(409, call(HttpMethod.DELETE, "/orders/" + id, null).getStatusCode().value());
    assertEquals(
        409,
        call(HttpMethod.PUT, "/orders/" + id, "{\"status\":\"pending\"}").getStatusCode().value());
    assertEquals(
        409,
        call(
                HttpMethod.POST,
                "/order_items",
                "{\"orderId\":" + id + ",\"productId\":1,\"quantity\":1}")
            .getStatusCode()
            .value());
  }

  @Test
  void cancelsAndRejectsUnknownStatus() {
    int id = create();
    assertEquals(
        400,
        call(HttpMethod.PUT, "/orders/" + id, "{\"status\":\"unknown\"}").getStatusCode().value());
    assertEquals(
        200,
        call(HttpMethod.PUT, "/orders/" + id, "{\"status\":\"cancelled\"}")
            .getStatusCode()
            .value());
  }

  @Test
  void deletesPendingOrderWithItsItems() {
    int id = create();
    assertEquals(204, call(HttpMethod.DELETE, "/orders/" + id, null).getStatusCode().value());
    assertEquals(0, items.count());
    assertEquals(404, call(HttpMethod.GET, "/orders/" + id, null).getStatusCode().value());
  }
}
