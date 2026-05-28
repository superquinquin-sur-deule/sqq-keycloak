package com.sqq.keycloak.odoo;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.keycloak.component.ComponentModel;
import org.keycloak.credential.CredentialInput;
import org.keycloak.credential.CredentialInputValidator;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.credential.PasswordCredentialModel;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.user.UserLookupProvider;
import org.keycloak.storage.user.UserCountMethodsProvider;
import org.keycloak.storage.user.UserQueryProvider;
import org.jboss.logging.Logger;

public class OdooUserStorageProvider implements UserStorageProvider, UserLookupProvider, CredentialInputValidator, UserQueryProvider, UserCountMethodsProvider {

    private static final Logger logger = Logger.getLogger(OdooUserStorageProvider.class);

    static final String ATTR_ODOO_UID = "odoo_uid";
    static final String ATTR_ODOO_ROLES = "odoo_roles";

    private final KeycloakSession session;
    private final ComponentModel model;
    private final OdooJsonRpcClient odooClient;
    private final int adminUid;
    private final String adminPassword;

    public OdooUserStorageProvider(KeycloakSession session, ComponentModel model,
                                   OdooJsonRpcClient odooClient, int adminUid, String adminPassword) {
        this.session = session;
        this.model = model;
        this.odooClient = odooClient;
        this.adminUid = adminUid;
        this.adminPassword = adminPassword;
    }

    @Override
    public boolean supportsCredentialType(String credentialType) {
        return PasswordCredentialModel.TYPE.equals(credentialType);
    }

    @Override
    public boolean isConfiguredFor(RealmModel realm, UserModel user, String credentialType) {
        return supportsCredentialType(credentialType);
    }

    @Override
    public boolean isValid(RealmModel realm, UserModel user, CredentialInput input) {
        if (!supportsCredentialType(input.getType())) {
            logger.debugf("isValid: unsupported credential type %s for user %s", input.getType(), user.getUsername());
            return false;
        }

        String username = user.getUsername();
        String email = user.getEmail();
        String password = input.getChallengeResponse();

        if (email == null || email.isBlank()) {
            logger.warnf("Cannot authenticate user %s: no email stored", username);
            return false;
        }
        if (password == null || password.isEmpty()) {
            logger.warnf("Cannot authenticate user %s: empty password submitted", username);
            return false;
        }

        logger.debugf("Delegating credential validation to Odoo for user=%s email=%s", username, email);
        int uid = odooClient.authenticate(email, password);
        if (uid <= 0) {
            logger.debugf("Odoo authentication failed for user: %s (email=%s)", username, email);
            return false;
        }

        logger.debugf("Odoo authentication succeeded for user: %s (uid=%d)", username, uid);
        return true;
    }

    @Override
    public UserModel getUserByUsername(RealmModel realm, String username) {
        if (adminUid <= 0) {
            return null;
        }
        OdooUserInfo info = odooClient.searchUserByEmail(username, adminUid, adminPassword);
        return info == null ? null : importOrUpdate(realm, info);
    }

    @Override
    public UserModel getUserByEmail(RealmModel realm, String email) {
        if (adminUid <= 0) {
            return null;
        }
        OdooUserInfo info = odooClient.searchUserByEmail(email, adminUid, adminPassword);
        return info == null ? null : importOrUpdate(realm, info);
    }

    @Override
    public UserModel getUserById(RealmModel realm, String id) {
        return null;
    }

    @Override
    public Stream<UserModel> searchForUserStream(RealmModel realm, Map<String, String> params, Integer firstResult, Integer maxResults) {
        return Stream.empty();
    }

    @Override
    public Stream<UserModel> getGroupMembersStream(RealmModel realm, GroupModel group, Integer firstResult, Integer maxResults) {
        return Stream.empty();
    }

    @Override
    public Stream<UserModel> searchForUserByUserAttributeStream(RealmModel realm, String attrName, String attrValue) {
        return Stream.empty();
    }

    @Override
    public int getUsersCount(RealmModel realm) {
        return 0;
    }

    @Override
    public void close() {
    }

    private UserModel importOrUpdate(RealmModel realm, OdooUserInfo info) {
        if (info.getEmail() == null || info.getEmail().isBlank()) {
            logger.warnf("Skipping Odoo user %d: missing email (login=%s)", info.getUid(), info.getLogin());
            return null;
        }
        info.setRoles(odooClient.resolveRolesFor(info, adminUid, adminPassword));
        UserModel user = findByOdooUid(session, realm, model, info.getUid());
        if (user == null) {
            logger.infof("Importing Odoo user %d as '%s'", info.getUid(), info.getEmail());
            user = session.users().addUser(realm, info.getEmail());
            user.setFederationLink(model.getId());
        } else if (!info.getEmail().equals(user.getUsername())) {
            logger.infof("Renaming federated user (uid=%d): '%s' -> '%s'",
                    info.getUid(), user.getUsername(), info.getEmail());
            user.setUsername(info.getEmail());
        } else {
            logger.debugf("Updating federated user (uid=%d, username=%s)", info.getUid(), user.getUsername());
        }
        applyAttributes(realm, user, info);
        return user;
    }

    static UserModel findByOdooUid(KeycloakSession session, RealmModel realm, ComponentModel model, int uid) {
        return session.users()
                .searchForUserByUserAttributeStream(realm, ATTR_ODOO_UID, String.valueOf(uid))
                .filter(u -> model.getId().equals(u.getFederationLink()))
                .findFirst()
                .orElse(null);
    }

    static void applyAttributes(RealmModel realm, UserModel user, OdooUserInfo info) {
        user.setEnabled(true);
        if (info.getEmail() != null) {
            user.setEmail(info.getEmail());
            user.setEmailVerified(true);
        }
        if (info.getName() != null) {
            String name = info.getName().strip();
            if (name.contains(",")) {
                String[] parts = name.split(",", 2);
                user.setLastName(parts[0].strip());
                user.setFirstName(parts[1].strip());
            } else {
                String[] parts = name.split("\\s+", 2);
                user.setFirstName(parts[0]);
                if (parts.length > 1) {
                    user.setLastName(parts[1]);
                }
            }
        }
        user.setSingleAttribute(ATTR_ODOO_UID, String.valueOf(info.getUid()));
        applyRoles(realm, user, info.getRoles());
    }

    static void applyRoles(RealmModel realm, UserModel user, List<String> odooRoles) {
        Set<String> desired = new TreeSet<>();
        if (odooRoles != null) {
            for (String name : odooRoles) {
                if (name != null && !name.isBlank()) {
                    desired.add(name);
                }
            }
        }

        Set<String> previous = parsePreviousRoles(user.getFirstAttribute(ATTR_ODOO_ROLES));

        for (String roleName : previous) {
            if (desired.contains(roleName)) {
                continue;
            }
            RoleModel role = realm.getRole(roleName);
            if (role != null && user.hasRole(role)) {
                user.deleteRoleMapping(role);
                logger.debugf("Removed realm role '%s' from user '%s'", roleName, user.getUsername());
            }
        }

        for (String roleName : desired) {
            RoleModel role = realm.getRole(roleName);
            if (role == null) {
                role = realm.addRole(roleName);
                logger.infof("Created realm role '%s'", roleName);
            }
            if (!user.hasRole(role)) {
                user.grantRole(role);
                logger.debugf("Granted realm role '%s' to user '%s'", roleName, user.getUsername());
            }
        }

        user.setSingleAttribute(ATTR_ODOO_ROLES, String.join(",", desired));
    }

    private static Set<String> parsePreviousRoles(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        Set<String> result = new HashSet<>();
        for (String token : Arrays.asList(csv.split(","))) {
            String trimmed = token.strip();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
