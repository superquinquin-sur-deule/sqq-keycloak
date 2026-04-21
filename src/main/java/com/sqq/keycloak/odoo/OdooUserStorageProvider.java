package com.sqq.keycloak.odoo;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
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

    // --- CredentialInputValidator ---

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
            return false;
        }

        String username = user.getUsername();
        String password = input.getChallengeResponse();

        int uid = odooClient.authenticate(username, password);
        if (uid <= 0) {
            logger.debugf("Odoo authentication failed for user: %s", username);
            return false;
        }

        // Refresh user attributes from Odoo on each successful login
        OdooUserInfo info = odooClient.fetchUser(uid, password);
        if (info != null) {
            updateUserAttributes(user, info);
        }

        // Store encrypted password as a user session note for token mapping
        if (storePassword) {
            try {
                String encryptedPassword = encrypt(password, encryptionKey);
                var authSession = session.getContext().getAuthenticationSession();
                if (authSession != null) {
                    authSession.setUserSessionNote("odoo_password", encryptedPassword);
                }
            } catch (Exception e) {
                logger.errorf(e, "Failed to encrypt Odoo password for user: %s", username);
            }
        }

        logger.debugf("Odoo authentication succeeded for user: %s (uid=%d)", username, uid);
        return true;
    }

    // --- UserLookupProvider ---

    @Override
    public UserModel getUserByUsername(RealmModel realm, String username) {
        // Keycloak already checked local storage before calling us.
        // Only search Odoo if admin credentials are configured.
        if (adminUid > 0) {
            OdooUserInfo info = odooClient.searchUserByLogin(username, adminUid, adminPassword);
            if (info != null) {
                return importUser(realm, info);
            }
        }

        return null;
    }

    @Override
    public UserModel getUserByEmail(RealmModel realm, String email) {
        // Keycloak already checked local storage before calling us.
        if (adminUid > 0) {
            OdooUserInfo info = odooClient.searchUserByEmail(email, adminUid, adminPassword);
            if (info != null) {
                return importUser(realm, info);
            }
        }

        return null;
    }

    @Override
    public UserModel getUserById(RealmModel realm, String id) {
        // Federated users are looked up by their storage ID;
        // once imported, Keycloak handles them via local storage.
        return null;
    }

    // --- UserQueryProvider ---

    @Override
    public Stream<UserModel> searchForUserStream(RealmModel realm, Map<String, String> params, Integer firstResult, Integer maxResults) {
        // User search is handled through local storage after import
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

    // --- UserCountMethodsProvider ---

    @Override
    public int getUsersCount(RealmModel realm) {
        // We don't know the remote user count; return 0 so Keycloak doesn't crash
        return 0;
    }

    // --- UserStorageProvider ---

    @Override
    public void close() {
        // no-op
    }

    // --- Internal helpers ---

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

    private UserModel importUser(RealmModel realm, OdooUserInfo info) {
        logger.infof("Importing Odoo user: %s (uid=%d)", info.getLogin(), info.getUid());

        UserModel user = session.users().addUser(realm, info.getLogin());
        user.setFederationLink(model.getId());
        user.setEnabled(true);

        updateUserAttributes(user, info);

        return user;
    }

    private void updateUserAttributes(UserModel user, OdooUserInfo info) {
        if (info.getEmail() != null) {
            user.setEmail(info.getEmail());
            user.setEmailVerified(true);
        }
        if (info.getName() != null) {
            // Handle "LASTNAME, Firstname" format
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
        user.setSingleAttribute("odoo_uid", String.valueOf(info.getUid()));
    }
}
