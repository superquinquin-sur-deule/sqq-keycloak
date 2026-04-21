package com.sqq.keycloak.odoo;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Stream;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.keycloak.component.ComponentModel;
import org.keycloak.credential.CredentialInput;
import org.keycloak.credential.CredentialInputValidator;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
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
    static final String ATTR_ODOO_PARTNER_ID = "odoo_partner_id";
    static final String ATTR_ODOO_IS_MEMBER = "odoo_is_member";

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final KeycloakSession session;
    private final ComponentModel model;
    private final OdooJsonRpcClient odooClient;
    private final int adminUid;
    private final String adminPassword;
    private final boolean storePassword;
    private final String encryptionKey;

    public OdooUserStorageProvider(KeycloakSession session, ComponentModel model,
                                   OdooJsonRpcClient odooClient, int adminUid, String adminPassword,
                                   boolean storePassword, String encryptionKey) {
        this.session = session;
        this.model = model;
        this.odooClient = odooClient;
        this.adminUid = adminUid;
        this.adminPassword = adminPassword;
        this.storePassword = storePassword;
        this.encryptionKey = encryptionKey;
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

        if (storePassword) {
            try {
                String encryptedPassword = encrypt(password, encryptionKey);
                var authSession = session.getContext().getAuthenticationSession();
                if (authSession != null) {
                    authSession.setUserSessionNote("odoo_password", encryptedPassword);
                    logger.debugf("Stored encrypted Odoo password as session note for user=%s", username);
                } else {
                    logger.warnf("No authentication session available to store Odoo password note for user=%s", username);
                }
            } catch (Exception e) {
                logger.errorf(e, "Failed to encrypt Odoo password for user: %s", username);
            }
        }

        logger.debugf("Odoo authentication succeeded for user: %s (uid=%d)", username, uid);
        return true;
    }

    @Override
    public UserModel getUserByUsername(RealmModel realm, String username) {
        if (adminUid <= 0) {
            return null;
        }
        OdooUserInfo info = odooClient.searchPartnerByBarcode(username, adminUid, adminPassword);
        return info == null ? null : importOrUpdate(realm, info);
    }

    @Override
    public UserModel getUserByEmail(RealmModel realm, String email) {
        if (adminUid <= 0) {
            return null;
        }
        OdooUserInfo info = odooClient.searchPartnerByEmail(email, adminUid, adminPassword);
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

    private static String encrypt(String plaintext, String base64Key) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");

        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        byte[] output = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, output, 0, iv.length);
        System.arraycopy(ciphertext, 0, output, iv.length, ciphertext.length);

        return Base64.getEncoder().encodeToString(output);
    }

    
    private UserModel importOrUpdate(RealmModel realm, OdooUserInfo info) {
        if (info.getBarcodeBase() == null || info.getBarcodeBase().isBlank()) {
            logger.warnf("Skipping Odoo partner %d: missing barcode_base (email=%s)", info.getPartnerId(), info.getEmail());
            return null;
        }
        UserModel user = findByPartnerId(session, realm, model, info.getPartnerId());
        if (user == null) {
            logger.infof("Importing Odoo partner %d as user '%s'", info.getPartnerId(), info.getBarcodeBase());
            user = session.users().addUser(realm, info.getBarcodeBase());
            user.setFederationLink(model.getId());
        } else if (!info.getBarcodeBase().equals(user.getUsername())) {
            logger.infof("Renaming federated user (partnerId=%d): '%s' -> '%s'",
                    info.getPartnerId(), user.getUsername(), info.getBarcodeBase());
            user.setUsername(info.getBarcodeBase());
        } else {
            logger.debugf("Updating federated user (partnerId=%d, username=%s)", info.getPartnerId(), user.getUsername());
        }
        applyAttributes(user, info);
        return user;
    }

    static UserModel findByPartnerId(KeycloakSession session, RealmModel realm, ComponentModel model, int partnerId) {
        return session.users()
                .searchForUserByUserAttributeStream(realm, ATTR_ODOO_PARTNER_ID, String.valueOf(partnerId))
                .filter(u -> model.getId().equals(u.getFederationLink()))
                .findFirst()
                .orElse(null);
    }

    static void applyAttributes(UserModel user, OdooUserInfo info) {
        user.setEnabled(info.isMember());
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
        user.setSingleAttribute(ATTR_ODOO_PARTNER_ID, String.valueOf(info.getPartnerId()));
        user.setSingleAttribute(ATTR_ODOO_IS_MEMBER, String.valueOf(info.isMember()));
    }
}