package com.sqq.keycloak.odoo;

import java.util.List;

import org.keycloak.component.ComponentModel;
import org.keycloak.component.ComponentValidationException;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;
import org.keycloak.storage.UserStorageProviderFactory;
import org.jboss.logging.Logger;

public class OdooUserStorageProviderFactory implements UserStorageProviderFactory<OdooUserStorageProvider> {

    private static final Logger logger = Logger.getLogger(OdooUserStorageProviderFactory.class);

    public static final String PROVIDER_ID = "odoo-user-storage";

    public static final String CONFIG_ODOO_URL = "odoo-url";
    public static final String CONFIG_ODOO_DATABASE = "odoo-database";
    public static final String CONFIG_ADMIN_LOGIN = "admin-login";
    public static final String CONFIG_ADMIN_PASSWORD = "admin-password";
    public static final String CONFIG_STORE_PASSWORD = "store-password";
    public static final String CONFIG_ENCRYPTION_KEY = "encryption-key";

    private static final List<ProviderConfigProperty> CONFIG_PROPERTIES;

    static {
        CONFIG_PROPERTIES = ProviderConfigurationBuilder.create()
                .property()
                    .name(CONFIG_ODOO_URL)
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .label("Odoo URL")
                    .helpText("Base URL of the Odoo server (e.g. https://odoo.example.com)")
                    .add()
                .property()
                    .name(CONFIG_ODOO_DATABASE)
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .label("Odoo Database")
                    .helpText("Name of the Odoo database")
                    .add()
                .property()
                    .name(CONFIG_ADMIN_LOGIN)
                    .type(ProviderConfigProperty.STRING_TYPE)
                    .label("Admin Login")
                    .helpText("Odoo service account login for user lookup (optional, enables user search)")
                    .add()
                .property()
                    .name(CONFIG_ADMIN_PASSWORD)
                    .type(ProviderConfigProperty.PASSWORD)
                    .label("Admin Password")
                    .helpText("Odoo service account password (optional)")
                    .secret(true)
                    .add()
                .property()
                    .name(CONFIG_STORE_PASSWORD)
                    .type(ProviderConfigProperty.BOOLEAN_TYPE)
                    .label("Store Encrypted Password in Token")
                    .helpText("If enabled, the user's Odoo password is encrypted and stored as a session note for inclusion in tokens")
                    .defaultValue("false")
                    .add()
                .property()
                    .name(CONFIG_ENCRYPTION_KEY)
                    .type(ProviderConfigProperty.PASSWORD)
                    .label("Encryption Key")
                    .helpText("AES-256 key (32 bytes, Base64-encoded) — required when 'Store Encrypted Password in Token' is enabled")
                    .secret(true)
                    .add()
                .build();
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public OdooUserStorageProvider create(KeycloakSession session, ComponentModel model) {
        String odooUrl = model.get(CONFIG_ODOO_URL);
        String odooDatabase = model.get(CONFIG_ODOO_DATABASE);
        String adminLogin = model.get(CONFIG_ADMIN_LOGIN);
        String adminPassword = model.get(CONFIG_ADMIN_PASSWORD);
        boolean storePassword = Boolean.parseBoolean(model.get(CONFIG_STORE_PASSWORD));
        String encryptionKey = model.get(CONFIG_ENCRYPTION_KEY);

        OdooJsonRpcClient client = new OdooJsonRpcClient(odooUrl, odooDatabase);

        int adminUid = -1;
        if (adminLogin != null && !adminLogin.isBlank()
                && adminPassword != null && !adminPassword.isBlank()) {
            adminUid = client.authenticate(adminLogin, adminPassword);
            if (adminUid <= 0) {
                logger.warn("Odoo admin authentication failed — user search will be unavailable");
            }
        }

        return new OdooUserStorageProvider(session, model, client, adminUid, adminPassword,
                storePassword, encryptionKey);
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return CONFIG_PROPERTIES;
    }

    @Override
    public void validateConfiguration(KeycloakSession session, RealmModel realm, ComponentModel config)
            throws ComponentValidationException {
        String odooUrl = config.get(CONFIG_ODOO_URL);
        if (odooUrl == null || odooUrl.isBlank()) {
            throw new ComponentValidationException("Odoo URL is required");
        }
        String odooDatabase = config.get(CONFIG_ODOO_DATABASE);
        if (odooDatabase == null || odooDatabase.isBlank()) {
            throw new ComponentValidationException("Odoo database name is required");
        }
        boolean storePassword = Boolean.parseBoolean(config.get(CONFIG_STORE_PASSWORD));
        if (storePassword) {
            String encryptionKey = config.get(CONFIG_ENCRYPTION_KEY);
            if (encryptionKey == null || encryptionKey.isBlank()) {
                throw new ComponentValidationException("Encryption key is required when password storage is enabled");
            }
        }
    }
}
