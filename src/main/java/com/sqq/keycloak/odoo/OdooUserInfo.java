package com.sqq.keycloak.odoo;

public class OdooUserInfo {

    private final int uid;
    private final String login;
    private final String name;
    private final String email;

    public OdooUserInfo(int uid, String login, String name, String email) {
        this.uid = uid;
        this.login = login;
        this.name = name;
        this.email = email;
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
}
