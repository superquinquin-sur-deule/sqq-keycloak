package com.sqq.keycloak.odoo;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.keycloak.component.ComponentModel;
import org.keycloak.component.ComponentValidationException;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;
import org.keycloak.storage.UserStorageProviderFactory;
import org.keycloak.storage.UserStorageProviderModel;
import org.keycloak.storage.user.ImportSynchronization;
import org.keycloak.storage.user.SynchronizationResult;
import org.jboss.logging.Logger;

public class OdooUserStorageProviderFactory
        implements UserStorageProviderFactory<OdooUserStorageProvider>, ImportSynchronization {

    private static final Logger logger = Logger.getLogger(OdooUserStorageProviderFactory.class);

    public static final String PROVIDER_ID = "odoo-user-storage";

    public static final String CONFIG_ODOO_URL = "odoo-url";
    public static final String CONFIG_ODOO_DATABASE = "odoo-database";
    public static final String CONFIG_ADMIN_LOGIN = "admin-login";
    public static final String CONFIG_ADMIN_PASSWORD = "admin-password";

    private static final int SYNC_BATCH_SIZE = 100;

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
                    .helpText("Odoo service account login for user lookup and sync")
                    .add()
                .property()
                    .name(CONFIG_ADMIN_PASSWORD)
                    .type(ProviderConfigProperty.PASSWORD)
                    .label("Admin Password")
                    .helpText("Odoo service account password")
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

        logger.debugf("Creating OdooUserStorageProvider: url=%s db=%s adminLogin=%s",
                odooUrl, odooDatabase, adminLogin);

        OdooJsonRpcClient client = new OdooJsonRpcClient(odooUrl, odooDatabase);

        int adminUid = resolveAdminUid(client, adminLogin, adminPassword);

        return new OdooUserStorageProvider(session, model, client, adminUid, adminPassword);
    }

    private static int resolveAdminUid(OdooJsonRpcClient client, String adminLogin, String adminPassword) {
        if (adminLogin == null || adminLogin.isBlank()
                || adminPassword == null || adminPassword.isBlank()) {
            logger.warn("Odoo admin credentials not configured — user search will be unavailable");
            return -1;
        }
        int uid = client.authenticate(adminLogin, adminPassword);
        if (uid <= 0) {
            logger.warnf("Odoo admin authentication failed (login=%s)", adminLogin);
            return -1;
        }
        logger.debugf("Odoo admin authenticated: login=%s uid=%d", adminLogin, uid);
        return uid;
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
    }

    @Override
    public SynchronizationResult sync(KeycloakSessionFactory sessionFactory, String realmId,
                                       UserStorageProviderModel model) {
        logger.infof("Odoo sync starting: realmId=%s componentId=%s", realmId, model.getId());
        long syncStart = System.currentTimeMillis();
        SynchronizationResult result = new SynchronizationResult();

        String odooUrl = model.get(CONFIG_ODOO_URL);
        String odooDatabase = model.get(CONFIG_ODOO_DATABASE);
        String adminLogin = model.get(CONFIG_ADMIN_LOGIN);
        String adminPassword = model.get(CONFIG_ADMIN_PASSWORD);

        OdooJsonRpcClient client = new OdooJsonRpcClient(odooUrl, odooDatabase);
        int adminUid = resolveAdminUid(client, adminLogin, adminPassword);
        if (adminUid <= 0) {
            logger.error("Odoo sync aborted: admin not authenticated");
            return result;
        }

        Set<Integer> seenPartnerIds = new HashSet<>();
        int offset = 0;

        try {
            while (true) {
                List<OdooUserInfo> batch = client.listMemberPartners(offset, SYNC_BATCH_SIZE, adminUid, adminPassword);
                if (batch.isEmpty()) {
                    logger.debugf("Odoo sync: empty batch at offset=%d, ending pagination", offset);
                    break;
                }
                logger.debugf("Odoo sync: processing batch offset=%d size=%d", offset, batch.size());
                List<Integer> batchUids = new ArrayList<>();
                for (OdooUserInfo info : batch) {
                    seenPartnerIds.add(info.getPartnerId());
                    if (info.getUid() > 0) {
                        batchUids.add(info.getUid());
                    }
                }
                Map<Integer, List<String>> rolesByUid = client.fetchRolesForUsers(batchUids, adminUid, adminPassword);
                for (OdooUserInfo info : batch) {
                    info.setRoles(rolesByUid.getOrDefault(info.getUid(), List.of()));
                }
                KeycloakModelUtils.runJobInTransaction(sessionFactory, session -> {
                    RealmModel realm = session.realms().getRealm(realmId);
                    session.getContext().setRealm(realm);
                    for (OdooUserInfo info : batch) {
                        upsertUser(session, realm, model, info, result);
                    }
                });
                if (batch.size() < SYNC_BATCH_SIZE) {
                    break;
                }
                offset += SYNC_BATCH_SIZE;
            }
        } catch (RuntimeException e) {
            logger.errorf(e, "Odoo sync aborted: fetch/upsert failed at offset=%d — skipping deactivation pass", offset);
            return result;
        }
        logger.debugf("Odoo sync: fetched %d partners, beginning deactivation pass", seenPartnerIds.size());

        KeycloakModelUtils.runJobInTransaction(sessionFactory, session -> {
            RealmModel realm = session.realms().getRealm(realmId);
            session.getContext().setRealm(realm);
            List<UserModel> federated = session.users()
                    .searchForUserStream(realm, Map.of(), null, null)
                    .filter(u -> model.getId().equals(u.getFederationLink()))
                    .toList();
            for (UserModel user : federated) {
                String pidStr = user.getFirstAttribute(OdooUserStorageProvider.ATTR_ODOO_PARTNER_ID);
                Integer pid = parseIntOrNull(pidStr);
                if (pid == null || !seenPartnerIds.contains(pid)) {
                    if (user.isEnabled()) {
                        user.setEnabled(false);
                        user.setSingleAttribute(OdooUserStorageProvider.ATTR_ODOO_IS_MEMBER, "false");
                        result.increaseUpdated();
                        logger.infof("Disabled Keycloak user '%s' (partner_id=%s no longer a member)",
                                user.getUsername(), pidStr);
                    }
                }
            }
        });

        logger.infof("Odoo sync done: added=%d, updated=%d, failed=%d, elapsedMs=%d",
                result.getAdded(), result.getUpdated(), result.getFailed(),
                System.currentTimeMillis() - syncStart);
        return result;
    }

    @Override
    public SynchronizationResult syncSince(Date lastSync, KeycloakSessionFactory sessionFactory,
                                            String realmId, UserStorageProviderModel model) {
        return sync(sessionFactory, realmId, model);
    }

    private static void upsertUser(KeycloakSession session, RealmModel realm, ComponentModel model,
                                    OdooUserInfo info, SynchronizationResult result) {
        if (info.getBarcodeBase() == null || info.getBarcodeBase().isBlank()) {
            logger.warnf("Sync: skipping partnerId=%d (email=%s) — missing barcode_base",
                    info.getPartnerId(), info.getEmail());
            result.increaseFailed();
            return;
        }
        UserModel existing = OdooUserStorageProvider.findByPartnerId(session, realm, model, info.getPartnerId());

        if (existing == null) {
            UserModel created = session.users().addUser(realm, info.getBarcodeBase());
            created.setFederationLink(model.getId());
            OdooUserStorageProvider.applyAttributes(realm, created, info);
            result.increaseAdded();
            logger.debugf("Sync: added partnerId=%d username=%s", info.getPartnerId(), info.getBarcodeBase());
        } else {
            if (!info.getBarcodeBase().equals(existing.getUsername())) {
                existing.setUsername(info.getBarcodeBase());
            }
            OdooUserStorageProvider.applyAttributes(realm, existing, info);
            result.increaseUpdated();
        }
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null) return null;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}