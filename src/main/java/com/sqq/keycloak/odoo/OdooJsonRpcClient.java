package com.sqq.keycloak.odoo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
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

    private static final Logger logger = Logger.getLogger(OdooJsonRpcClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final AtomicInteger requestId = new AtomicInteger(1);

    private static final List<String> PARTNER_FIELDS = List.of(
            "id", "name", "email", "barcode_base", "is_member", "user_ids");

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

    public OdooUserInfo searchPartnerByBarcode(String barcode, int adminUid, String adminPassword) {
        return searchPartner(List.of("barcode_base", "=", barcode), adminUid, adminPassword);
    }

    public OdooUserInfo searchPartnerByEmail(String email, int adminUid, String adminPassword) {
        return searchPartner(List.of("email", "=", email), adminUid, adminPassword);
    }

    private OdooUserInfo searchPartner(List<Object> extraCriterion, int adminUid, String adminPassword) {
        try {
            List<Object> domain = List.of(extraCriterion, List.of("user_ids", "!=", false));
            JsonNode result = callJsonRpc("object", "execute_kw",
                    List.of(odooDatabase, adminUid, adminPassword,
                            "res.partner", "search_read",
                            List.of(domain),
                            Map.of("fields", PARTNER_FIELDS, "limit", 1)));

            if (result == null || !result.isArray() || result.isEmpty()) {
                return null;
            }
            return mapToUserInfo(result.get(0));
        } catch (Exception e) {
            logger.errorf(e, "Odoo searchPartner failed for criterion=%s", extraCriterion);
            return null;
        }
    }

    public List<OdooUserInfo> listMemberPartners(int offset, int limit, int adminUid, String adminPassword) {
        try {
            List<Object> domain = List.of(
                    List.of("is_member", "=", true),
                    List.of("user_ids", "!=", false));
            JsonNode result = callJsonRpc("object", "execute_kw",
                    List.of(odooDatabase, adminUid, adminPassword,
                            "res.partner", "search_read",
                            List.of(domain),
                            Map.of("fields", PARTNER_FIELDS,
                                    "offset", offset,
                                    "limit", limit,
                                    "order", "id asc")));

            List<OdooUserInfo> results = new ArrayList<>();
            if (result != null && result.isArray()) {
                for (JsonNode node : result) {
                    OdooUserInfo info = mapToUserInfo(node);
                    if (info != null) {
                        results.add(info);
                    }
                }
            }
            logger.debugf("Odoo listMemberPartners: offset=%d returned=%d", offset, results.size());
            return results;
        } catch (Exception e) {
            logger.errorf(e, "Odoo listMemberPartners failed (offset=%d, limit=%d)", offset, limit);
            return List.of();
        }
    }

    public Map<Integer, List<String>> fetchRolesForUsers(List<Integer> uids, int adminUid, String adminPassword) {
        if (uids == null || uids.isEmpty()) {
            return Map.of();
        }
        try {
            JsonNode usersResult = callJsonRpc("object", "execute_kw",
                    List.of(odooDatabase, adminUid, adminPassword,
                            "res.users", "read",
                            List.of(uids),
                            Map.of("fields", List.of("id", "groups_id"))));
            if (usersResult == null || !usersResult.isArray()) {
                return Map.of();
            }

            Map<Integer, List<Integer>> uidToGroupIds = new HashMap<>();
            Set<Integer> allGroupIds = new HashSet<>();
            for (JsonNode u : usersResult) {
                int uid = u.get("id").intValue();
                List<Integer> gids = new ArrayList<>();
                JsonNode gidsNode = u.get("groups_id");
                if (gidsNode != null && gidsNode.isArray()) {
                    for (JsonNode g : gidsNode) {
                        gids.add(g.intValue());
                    }
                }
                uidToGroupIds.put(uid, gids);
                allGroupIds.addAll(gids);
            }
            if (allGroupIds.isEmpty()) {
                Map<Integer, List<String>> empty = new HashMap<>();
                for (Integer uid : uidToGroupIds.keySet()) {
                    empty.put(uid, List.of());
                }
                return empty;
            }

            JsonNode groupsResult = callJsonRpc("object", "execute_kw",
                    List.of(odooDatabase, adminUid, adminPassword,
                            "res.groups", "read",
                            List.of(new ArrayList<>(allGroupIds)),
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

            Map<Integer, List<String>> result = new HashMap<>();
            for (Map.Entry<Integer, List<Integer>> entry : uidToGroupIds.entrySet()) {
                List<String> names = new ArrayList<>();
                for (Integer gid : entry.getValue()) {
                    String name = groupIdToName.get(gid);
                    if (name != null) {
                        names.add(name);
                    }
                }
                result.put(entry.getKey(), names);
            }
            return result;
        } catch (Exception e) {
            logger.errorf(e, "Odoo fetchRolesForUsers failed for uids=%s", uids);
            return Map.of();
        }
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
            logger.errorf("Odoo JSON-RPC error id=%d service=%s method=%s error=%s",
                    rpcId, service, method,
                    error.has("data") ? error.get("data").get("message") : error);
            return null;
        }

        return root.get("result");
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...[truncated]";
    }

    private OdooUserInfo mapToUserInfo(JsonNode record) {
        int partnerId = record.get("id").intValue();
        String name = textOrNull(record, "name");
        String email = textOrNull(record, "email");
        String barcodeBase = textOrNull(record, "barcode_base");
        boolean isMember = record.has("is_member") && record.get("is_member").asBoolean(false);

        int uid = 0;
        JsonNode userIds = record.get("user_ids");
        if (userIds != null && userIds.isArray() && !userIds.isEmpty()) {
            uid = userIds.get(0).intValue();
        }

        return new OdooUserInfo(uid, partnerId, name, email, barcodeBase, isMember);
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isBoolean() || value.isNull()) {
            return null;
        }
        return value.asText();
    }
}