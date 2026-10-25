package fr.didelo.semaphore.guest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MeApiTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @LocalServerPort private int port;

  private static JsonNode body(java.net.http.HttpResponse<String> response) {
    return JSON.readTree(response.body());
  }

  @Test
  void aVisitorIsAnonymousAndGetsOnlyTheTokenCookie() {
    var browser = new Browser(port);

    var response = browser.get();

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(body(response).get("kind").asString()).isEqualTo("anonymous");
    assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    assertThat(browser.setCookies).hasSize(1);
    assertThat(browser.setCookies.getFirst()).startsWith("__Host-XSRF-TOKEN=");
    assertThat(browser.setCookies.getFirst()).contains("Secure", "Path=/").doesNotContain("Domain");
    assertThat(browser.setCookies.getFirst()).doesNotContain("HttpOnly");
  }

  @Test
  void aPutWithoutTheTokenIsRefused() {
    var browser = new Browser(port);
    browser.get();

    var response = browser.send("PUT", "{\"name\":\"ana\"}", false);

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(browser.cookie("__Host-session")).isNull();
  }

  @Test
  void aPutCreatesTheGuestWithTheCookieOfTheAdr() {
    var browser = new Browser(port);
    browser.get();

    var response = browser.put("{\"name\":\"  Ana  \"}");

    assertThat(response.statusCode()).isEqualTo(200);
    var me = body(response);
    assertThat(me.get("kind").asString()).isEqualTo("guest");
    assertThat(me.get("name").asString()).isEqualTo("Ana");
    assertThat(me.get("id").asString()).isNotBlank();
    assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    var session =
        browser.setCookies.stream().filter(c -> c.startsWith("__Host-session=")).findFirst();
    assertThat(session).isPresent();
    assertThat(session.get())
        .contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/", "Max-Age=2592000")
        .doesNotContain("Domain");
  }

  @Test
  void theGuestIsFoundAgainAndKeepsItsIdWhenRenamed() {
    var browser = new Browser(port);
    browser.get();
    var id = body(browser.put("{\"name\":\"ana\"}")).get("id").asString();

    var found = body(browser.get());
    var renamed = body(browser.put("{\"name\":\"bea\"}"));

    assertThat(found.get("kind").asString()).isEqualTo("guest");
    assertThat(found.get("id").asString()).isEqualTo(id);
    assertThat(found.get("name").asString()).isEqualTo("ana");
    assertThat(renamed.get("id").asString()).isEqualTo(id);
    assertThat(renamed.get("name").asString()).isEqualTo("bea");
  }

  @Test
  void twoBrowsersAreTwoGuests() {
    var ana = new Browser(port);
    var bea = new Browser(port);
    ana.get();
    bea.get();

    var anaId = body(ana.put("{\"name\":\"ana\"}")).get("id").asString();
    var beaId = body(bea.put("{\"name\":\"bea\"}")).get("id").asString();

    assertThat(anaId).isNotEqualTo(beaId);
  }

  @Test
  void aNameOfOneToTwentyCharactersIsRequired() {
    var browser = new Browser(port);
    browser.get();

    for (var json :
        new String[] {
          "{\"name\":\"\"}", "{\"name\":\"   \"}", "{\"name\":\"" + "x".repeat(21) + "\"}"
        }) {
      var response = browser.put(json);
      assertThat(response.statusCode()).isEqualTo(400);
      assertThat(body(response).get("error").asString()).isEqualTo("a name has 1 to 20 characters");
    }
    assertThat(browser.put("{\"name\":\"" + "x".repeat(20) + "\"}").statusCode()).isEqualTo(200);
    assertThat(browser.put("{}").statusCode()).isEqualTo(400);
  }

  @Test
  void forgettingEndsTheSession() {
    var browser = new Browser(port);
    browser.get();
    browser.put("{\"name\":\"ana\"}");

    var response = browser.delete();

    assertThat(response.statusCode()).isEqualTo(204);
    assertThat(browser.cookie("__Host-session")).isNull();
    assertThat(body(browser.get()).get("kind").asString()).isEqualTo("anonymous");
  }
}
