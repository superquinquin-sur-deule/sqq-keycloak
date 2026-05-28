package com.sqq.keycloak.odoo;

import java.util.List;

public class OdooUserInfo {

    private final int uid;
    private final String login;
    private final String name;
    private final String email;
    private final List<Integer> groupIds;
    private List<String> roles = List.of();

    public OdooUserInfo(int uid, String login, String name, String email, List<Integer> groupIds) {
        this.uid = uid;
        this.login = login;
        this.name = name;
        this.email = email;
        this.groupIds = groupIds == null ? List.of() : groupIds;
    }

    public int getUid() {
        return uid;
    }

    public String getLogin() {
        return login;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public List<Integer> getGroupIds() {
        return groupIds;
    }

    public List<String> getRoles() {
        return roles;
    }

    public void setRoles(List<String> roles) {
        this.roles = roles == null ? List.of() : roles;
    }
}
