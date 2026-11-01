package fr.didelo.semaphore.guest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Enough of a browser for the API: it keeps the cookies by hand, since Java's cookie jar would not
 * send a {@code Secure} cookie over plain HTTP, sends the CSRF token as the page does, and follows
 * no redirect, so a test sees each one.
 */
public final class Browser {

  private final HttpClient http = HttpClient.newHttpClient();
  private final Map<String, String> cookies = new LinkedHashMap<>();
  private int port;
  public List<String> setCookies = List.of();

  public Browser(int port) {
    this.port = port;
  }

  /** The same browser, now talking to another replica. */
  public void use(int otherPort) {
    port = otherPort;
  }

  public String cookie(String name) {
    return cookies.get(name);
  }

  public void setCookie(String name, String value) {
    cookies.put(name, value);
  }

  public HttpResponse<String> get() {
    return send("GET", null, cookie("__Host-XSRF-TOKEN"));
  }

  public HttpResponse<String> put(String json) {
    return send("PUT", json, cookie("__Host-XSRF-TOKEN"));
  }

  public HttpResponse<String> delete() {
    return send("DELETE", null, cookie("__Host-XSRF-TOKEN"));
  }

  /** A request to any path (and query), with the token as the page sends it. */
  public HttpResponse<String> request(String method, String path) {
    return send(method, path, null, cookie("__Host-XSRF-TOKEN"));
  }

  /** Sends {@code token} as the header, as the page does with the one it read from the cookie. */
  HttpResponse<String> send(String method, String json, String token) {
    return send(method, "/api/me", json, token);
  }

  private HttpResponse<String> send(String method, String path, String json, String token) {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .method(
                method,
                json == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json))
            .header("Content-Type", "application/json");
    if (!cookies.isEmpty()) {
      request.header(
          "Cookie",
          cookies.entrySet().stream()
              .map(cookie -> cookie.getKey() + "=" + cookie.getValue())
              .collect(Collectors.joining("; ")));
    }
    if (token != null && !method.equals("GET")) {
      request.header("X-XSRF-TOKEN", token);
    }
    try {
      var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      setCookies = new ArrayList<>(response.headers().allValues("Set-Cookie"));
      for (var header : setCookies) {
        var pair = header.split(";", 2)[0].split("=", 2);
        if (pair[1].isEmpty() || header.contains("Max-Age=0")) {
          cookies.remove(pair[0]);
        } else {
          cookies.put(pair[0], pair[1]);
        }
      }
      return response;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
