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
 * Enough of a browser for {@code /api/me}: it keeps the cookies by hand, since Java's cookie jar
 * would not send a {@code Secure} cookie over plain HTTP, and sends the CSRF token as the page
 * does.
 */
final class Browser {

  private final HttpClient http = HttpClient.newHttpClient();
  private final Map<String, String> cookies = new LinkedHashMap<>();
  private int port;
  List<String> setCookies = List.of();

  Browser(int port) {
    this.port = port;
  }

  /** The same browser, now talking to another replica. */
  void use(int otherPort) {
    port = otherPort;
  }

  String cookie(String name) {
    return cookies.get(name);
  }

  void setCookie(String name, String value) {
    cookies.put(name, value);
  }

  HttpResponse<String> get() {
    return send("GET", null, true);
  }

  HttpResponse<String> put(String json) {
    return send("PUT", json, true);
  }

  HttpResponse<String> delete() {
    return send("DELETE", null, true);
  }

  HttpResponse<String> send(String method, String json, boolean withToken) {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/me"))
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
    var token = cookies.get("__Host-XSRF-TOKEN");
    if (withToken && token != null && !method.equals("GET")) {
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
