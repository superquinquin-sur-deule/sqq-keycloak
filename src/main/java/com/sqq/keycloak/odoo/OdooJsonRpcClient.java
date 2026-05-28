package com.sqq.keycloak.odoo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

public class OdooJsonRpcClient {

    public static class OdooRpcException extends RuntimeException {
        public OdooRpcException(String message) {
            super(message);
        }
    }

    private static final Logger logger = Logger.getLogger(OdooJsonRpcClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final AtomicInteger requestId = new AtomicInteger(1);

    private static final List<String> USER_FIELDS = List.of(
            "id", "login", "name", "email", "groups_id");

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private final String odooUrl;
    private final String odooDatabase;

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
    }

    public int authenticate(String login, String password) {
        try {
            JsonNode result = callJsonRpc("common", "authenticate",
                    List.of(odooDatabase, login, password, Map.of()));
            if (result != null && result.isNumber()) {
                return result.intValue();
            }
            logger.debugf("Odoo authenticate failed for login=%s (result=%s)", login, result);
            return -1;
        } catch (Exception e) {
            logger.errorf(e, "Odoo authenticate call failed for login=%s", login);
            return -1;
        }
    }

    public OdooUserInfo searchUserByEmail(String email, int adminUid, String adminPassword) {
        return searchUser(List.of("email", "=", email), adminUid, adminPassword);
    }

    public OdooUserInfo searchUserByLogin(String login, int adminUid, String adminPassword) {
        return searchUser(List.of("login", "=", login), adminUid, adminPassword);
    }

    private OdooUserInfo searchUser(List<Object> criterion, int adminUid, String adminPassword) {
        try {
            List<Object> domain = List.of(criterion);
            JsonNode result = callJsonRpc("object", "execute_kw",
                    List.of(odooDatabase, adminUid, adminPassword,
                            "res.users", "search_read",
                            List.of(domain),
                            Map.of("fields", USER_FIELDS, "limit", 1)));

            if (result == null || !result.isArray() || result.isEmpty()) {
                return null;
            }
            return mapToUserInfo(result.get(0));
        } catch (Exception e) {
            logger.errorf(e, "Odoo searchUser failed for criterion=%s", criterion);
            return null;
        }
    }

    public List<OdooUserInfo> listUsers(int offset, int limit, int adminUid, String adminPassword) {
        JsonNode result;
        try {
            result = callJsonRpc("object", "execute_kw",
                    List.of(odooDatabase, adminUid, adminPassword,
                            "res.users", "search_read",
                            List.of(Collections.emptyList()),
                            Map.of("fields", USER_FIELDS,
                                    "offset", offset,
                                    "limit", limit,
                                    "order", "id asc")));
        } catch (OdooRpcException e) {
            throw e;
        } catch (Exception e) {
            throw new OdooRpcException("listUsers failed: " + e.getMessage());
        }

        List<OdooUserInfo> results = new ArrayList<>();
        if (result != null && result.isArray()) {
            for (JsonNode node : result) {
                OdooUserInfo info = mapToUserInfo(node);
                if (info != null) {
                    results.add(info);
                }
            }
        }
        logger.debugf("Odoo listUsers: offset=%d returned=%d", offset, results.size());
        return results;
    }

    public Map<Integer, String> resolveGroupNames(Set<Integer> groupIds, int adminUid, String adminPassword) {
        if (groupIds == null || groupIds.isEmpty()) {
            return Map.of();
        }
        try {
            JsonNode groupsResult = callJsonRpc("object", "execute_kw",
                    List.of(odooDatabase, adminUid, adminPassword,
                            "res.groups", "read",
                            List.of(new ArrayList<>(groupIds)),
                            Map.of("fields", List.of("id", "name"))));
            Map<Integer, String> groupIdToName = new HashMap<>();
            if (groupsResult != null && groupsResult.isArray()) {
                for (JsonNode g : groupsResult) {
                    String name = textOrNull(g, "name");
                    if (name != null) {
                        groupIdToName.put(g.get("id").intValue(), name);
                    }
                }
            }
            return groupIdToName;
        } catch (Exception e) {
            logger.errorf(e, "Odoo resolveGroupNames failed for groupIds=%s", groupIds);
            return Map.of();
        }
    }

    public List<String> resolveRolesFor(OdooUserInfo info, int adminUid, String adminPassword) {
        if (info == null || info.getGroupIds().isEmpty()) {
            return List.of();
        }
        Map<Integer, String> names = resolveGroupNames(new HashSet<>(info.getGroupIds()), adminUid, adminPassword);
        List<String> roles = new ArrayList<>();
        for (Integer gid : info.getGroupIds()) {
            String name = names.get(gid);
            if (name != null) {
                roles.add(name);
            }
        }
        return roles;
    }

    private JsonNode callJsonRpc(String service, String method, List<Object> args) throws Exception {
        int rpcId = requestId.getAndIncrement();
        Map<String, Object> payload = Map.of(
                "jsonrpc", "2.0",
                "method", "call",
                "id", rpcId,
                "params", Map.of(
                        "service", service,
                        "method", method,
                        "args", args
                )
        );

        String body = mapper.writeValueAsString(payload);
        String endpoint = odooUrl + "/jsonrpc";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        long start = System.currentTimeMillis();
        HttpResponse<String> response;
        try {
            response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            logger.errorf(e, "Odoo JSON-RPC HTTP call failed id=%d service=%s method=%s endpoint=%s",
                    rpcId, service, method, endpoint);
            throw e;
        }
        long elapsed = System.currentTimeMillis() - start;
        logger.debugf("Odoo JSON-RPC id=%d service=%s method=%s status=%d elapsedMs=%d",
                rpcId, service, method, response.statusCode(), elapsed);

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            logger.warnf("Odoo JSON-RPC non-2xx id=%d status=%d body=%s",
                    rpcId, response.statusCode(), truncate(response.body(), 500));
        }

        JsonNode root = mapper.readTree(response.body());

        if (root.has("error")) {
            JsonNode error = root.get("error");
            JsonNode message = error.has("data") ? error.get("data").get("message") : error;
            logger.errorf("Odoo JSON-RPC error id=%d service=%s method=%s error=%s",
                    rpcId, service, method, message);
            throw new OdooRpcException(service + "." + method + ": " + message);
        }

        return root.get("result");
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...[truncated]";
    }

    private OdooUserInfo mapToUserInfo(JsonNode record) {
        int uid = record.get("id").intValue();
        String login = textOrNull(record, "login");
        String name = textOrNull(record, "name");
        String email = textOrNull(record, "email");

        List<Integer> groupIds = new ArrayList<>();
        JsonNode gidsNode = record.get("groups_id");
        if (gidsNode != null && gidsNode.isArray()) {
            for (JsonNode g : gidsNode) {
                groupIds.add(g.intValue());
            }
        }

        return new OdooUserInfo(uid, login, name, email, groupIds);
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isBoolean() || value.isNull()) {
            return null;
        }
        return value.asText();
    }
}
