package com.sqq.keycloak.odoo;

import java.util.List;

public class OdooUserInfo {

    private final int uid;
    private final int partnerId;
    private final String name;
    private final String email;
    private final String barcodeBase;
    private final boolean isMember;
    private List<String> roles = List.of();

    public OdooUserInfo(int uid, int partnerId, String name, String email,
                        String barcodeBase, boolean isMember) {
        this.uid = uid;
        this.partnerId = partnerId;
        this.name = name;
        this.email = email;
        this.barcodeBase = barcodeBase;
        this.isMember = isMember;
    }

    public int getUid() {
        return uid;
    }

    public int getPartnerId() {
        return partnerId;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getBarcodeBase() {
        return barcodeBase;
    }

    public boolean isMember() {
        return isMember;
    }

    public List<String> getRoles() {
        return roles;
    }

    public void setRoles(List<String> roles) {
        this.roles = roles == null ? List.of() : roles;
    }
}
