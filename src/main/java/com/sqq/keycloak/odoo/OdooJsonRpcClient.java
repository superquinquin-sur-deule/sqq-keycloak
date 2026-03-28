package com.sqq.keycloak.odoo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

public class OdooJsonRpcClient {

    private static final Logger logger = Logger.getLogger(OdooJsonRpcClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final AtomicInteger requestId = new AtomicInteger(1);

    private final String odooUrl;
    private final String odooDatabase;
    private final HttpClient httpClient;

    public OdooJsonRpcClient(String odooUrl, String odooDatabase) {
        String url = odooUrl.strip();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        this.odooUrl = url;
        this.odooDatabase = odooDatabase;
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * Authenticate a user against Odoo.
     * @return uid (> 0) on success, -1 on failure
     */
    public int authenticate(String login, String password) {
        try {
            JsonNode result = callJsonRpc("common", "authenticate",
                    List.of(odooDatabase, login, password, Map.of()));

            if (result == null || result.isBoolean()) {
                return -1;
            }
            if (result.isNumber()) {
                return result.intValue();
            }
            logger.warnf("Unexpected authenticate response: %s", result);
            return -1;
        } catch (Exception e) {
            logger.errorf(e, "Odoo authenticate call failed for login=%s", login);
            return -1;
        }
    }

    /**
     * Fetch user info by uid using the user's own credentials.
     */
    public OdooUserInfo fetchUser(int uid, String password) {
        try {
            JsonNode result = callJsonRpc("object", "execute_kw",
                    List.of(odooDatabase, uid, password,
                            "res.users", "read",
                            List.of(List.of(uid)),
                            Map.of("fields", List.of("name", "email", "login"))));

            if (result == null || !result.isArray() || result.isEmpty()) {
                return null;
            }
            return mapToUserInfo(result.get(0));
        } catch (Exception e) {
            logger.errorf(e, "Odoo fetchUser failed for uid=%d", uid);
            return null;
        }
    }

    /**
     * Search for a user by login using admin credentials.
     */
    public OdooUserInfo searchUserByLogin(String login, int adminUid, String adminPassword) {
        return searchUser("login", login, adminUid, adminPassword);
    }

    /**
     * Search for a user by email using admin credentials.
     */
    public OdooUserInfo searchUserByEmail(String email, int adminUid, String adminPassword) {
        return searchUser("email", email, adminUid, adminPassword);
    }

    private OdooUserInfo searchUser(String field, String value, int adminUid, String adminPassword) {
        try {
            JsonNode result = callJsonRpc("object", "execute_kw",
                    List.of(odooDatabase, adminUid, adminPassword,
                            "res.users", "search_read",
                            List.of(List.of(List.of(field, "=", value))),
                            Map.of("fields", List.of("name", "email", "login"), "limit", 1)));

            if (result == null || !result.isArray() || result.isEmpty()) {
                return null;
            }
            return mapToUserInfo(result.get(0));
        } catch (Exception e) {
            logger.errorf(e, "Odoo searchUser failed for %s=%s", field, value);
            return null;
        }
    }

    private JsonNode callJsonRpc(String service, String method, List<Object> args) throws Exception {
        Map<String, Object> payload = Map.of(
                "jsonrpc", "2.0",
                "method", "call",
                "id", requestId.getAndIncrement(),
                "params", Map.of(
                        "service", service,
                        "method", method,
                        "args", args
                )
        );

        String body = mapper.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(odooUrl + "/jsonrpc"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        JsonNode root = mapper.readTree(response.body());

        if (root.has("error")) {
            JsonNode error = root.get("error");
            logger.errorf("Odoo JSON-RPC error: %s", error.has("data") ? error.get("data").get("message") : error);
            return null;
        }

        return root.get("result");
    }

    private OdooUserInfo mapToUserInfo(JsonNode record) {
        int uid = record.get("id").intValue();
        String login = textOrNull(record, "login");
        String name = textOrNull(record, "name");
        String email = textOrNull(record, "email");
        return new OdooUserInfo(uid, login, name, email);
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isBoolean() || value.isNull()) {
            return null;
        }
        return value.asText();
    }
}
