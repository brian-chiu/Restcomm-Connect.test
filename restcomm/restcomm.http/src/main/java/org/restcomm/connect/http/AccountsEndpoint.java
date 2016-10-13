/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2014, Telestax Inc and individual contributors
 * by the @authors tag.
 *
 * This program is free software: you can redistribute it and/or modify
 * under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 *
 */
package org.restcomm.connect.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.jersey.core.util.MultivaluedMapImpl;
import com.thoughtworks.xstream.XStream;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.PostConstruct;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.MediaType;

import static javax.ws.rs.core.MediaType.*;

import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import static javax.ws.rs.core.Response.*;
import static javax.ws.rs.core.Response.Status.*;

import org.apache.commons.configuration.Configuration;
import org.apache.shiro.crypto.hash.Md5Hash;
import org.joda.time.DateTime;
import org.restcomm.connect.commons.configuration.RestcommConfiguration;
import org.restcomm.connect.commons.configuration.sets.RcmlserverConfigurationSet;
import org.restcomm.connect.dao.ClientsDao;
import org.restcomm.connect.dao.DaoManager;
import org.restcomm.connect.dao.entities.Account;
import org.restcomm.connect.dao.entities.AccountList;
import org.restcomm.connect.dao.entities.Client;
import org.restcomm.connect.dao.entities.RestCommResponse;
import org.restcomm.connect.commons.dao.Sid;
import org.restcomm.connect.http.converter.AccountConverter;
import org.restcomm.connect.http.converter.AccountListConverter;
import org.restcomm.connect.http.converter.RestCommResponseConverter;
import org.restcomm.connect.http.exceptions.AuthorizationException;
import org.restcomm.connect.http.exceptions.InsufficientPermission;
import org.restcomm.connect.http.exceptions.AccountAlreadyClosed;
import org.restcomm.connect.commons.util.StringUtils;
import org.restcomm.connect.http.client.RcmlserverApi;

/**
 * @author quintana.thomas@gmail.com (Thomas Quintana)
 */
public class AccountsEndpoint extends SecuredEndpoint {
    protected Configuration configuration;
    protected Gson gson;
    protected XStream xstream;
    protected ClientsDao clientDao;

    public AccountsEndpoint() {
        super();
    }

    // used for testing
    public AccountsEndpoint(ServletContext context, HttpServletRequest request) {
        super(context,request);
    }

    @PostConstruct
    void init() {
        configuration = (Configuration) context.getAttribute(Configuration.class.getName());
        configuration = configuration.subset("runtime-settings");
        super.init(configuration);
        clientDao = ((DaoManager) context.getAttribute(DaoManager.class.getName())).getClientsDao();
        final AccountConverter converter = new AccountConverter(configuration);
        final GsonBuilder builder = new GsonBuilder();
        builder.registerTypeAdapter(Account.class, converter);
        builder.setPrettyPrinting();
        gson = builder.create();
        xstream = new XStream();
        xstream.alias("RestcommResponse", RestCommResponse.class);
        xstream.registerConverter(converter);
        xstream.registerConverter(new AccountListConverter(configuration));
        xstream.registerConverter(new RestCommResponseConverter(configuration));
        // Make sure there is an authenticated account present when this endpoint is used
        checkAuthenticatedAccount();
    }

    private Account createFrom(final Sid accountSid, final MultivaluedMap<String, String> data) {
        validate(data);

        final DateTime now = DateTime.now();
        final String emailAddress = (data.getFirst("EmailAddress")).toLowerCase();

        // Issue 108: https://bitbucket.org/telestax/telscale-restcomm/issue/108/account-sid-could-be-a-hash-of-the
        final Sid sid = Sid.generate(Sid.Type.ACCOUNT, emailAddress);

        String friendlyName = emailAddress;
        if (data.containsKey("FriendlyName")) {
            friendlyName = data.getFirst("FriendlyName");
        }
        final Account.Type type = Account.Type.FULL;
        Account.Status status = Account.Status.ACTIVE;
        if (data.containsKey("Status")) {
            status = Account.Status.getValueOf(data.getFirst("Status").toLowerCase());
        }
        final String password = data.getFirst("Password");
        final String authToken = new Md5Hash(password).toString();
        final String role = data.getFirst("Role");
        String rootUri = configuration.getString("root-uri");
        rootUri = StringUtils.addSuffixIfNotPresent(rootUri, "/");
        final StringBuilder buffer = new StringBuilder();
        buffer.append(rootUri).append(getApiVersion(null)).append("/Accounts/").append(sid.toString());
        final URI uri = URI.create(buffer.toString());
        return new Account(sid, now, now, emailAddress, friendlyName, accountSid, type, status, authToken, role, uri);
    }

    protected Response getAccount(final String accountSid, final MediaType responseType) {
        //First check if the account has the required permissions in general, this way we can fail fast and avoid expensive DAO operations
        Account account = null;
        checkPermission("RestComm:Read:Accounts");
        if (Sid.pattern.matcher(accountSid).matches()) {
            try {
                account = accountsDao.getAccount(new Sid(accountSid));
            } catch (Exception e) {
                return status(NOT_FOUND).build();
            }
        } else {
            try {
                account = accountsDao.getAccount(accountSid);
            } catch (Exception e) {
                return status(NOT_FOUND).build();
            }
        }

        secure(account, "RestComm:Read:Accounts", SecuredType.SECURED_ACCOUNT );

        if (account == null) {
            return status(NOT_FOUND).build();
        } else {
            if (APPLICATION_XML_TYPE == responseType) {
                final RestCommResponse response = new RestCommResponse(account);
                return ok(xstream.toXML(response), APPLICATION_XML).build();
            } else if (APPLICATION_JSON_TYPE == responseType) {
                return ok(gson.toJson(account), APPLICATION_JSON).build();
            } else {
                return null;
            }
        }
    }

    /* // Account removal disabled as per https://github.com/RestComm/Restcomm-Connect/issues/1270
    protected Response deleteAccount(final String operatedSid) {
        //First check if the account has the required permissions in general, this way we can fail fast and avoid expensive DAO operations
        checkPermission("RestComm:Delete:Accounts");
        // what if effectiveAccount is null ?? - no need to check since we checkAuthenticatedAccount() in AccountsEndoint.init()
        final Sid accountSid = userIdentityContext.getEffectiveAccount().getSid();
        final Sid sidToBeRemoved = new Sid(operatedSid);

        Account removedAccount = accountsDao.getAccount(sidToBeRemoved);
        secure(removedAccount, "RestComm:Delete:Accounts", SecuredType.SECURED_ACCOUNT);
        // Prevent removal of Administrator account
        if (operatedSid.equalsIgnoreCase(accountSid.toString()))
            return status(BAD_REQUEST).build();

        if (accountsDao.getAccount(sidToBeRemoved) == null)
            return status(NOT_FOUND).build();

        // the whole tree of sub-accounts has to be removed as well
        List<String> removedAccounts = accountsDao.getSubAccountSidsRecursive(sidToBeRemoved);
        if (removedAccounts != null && !removedAccounts.isEmpty()) {
            int i = removedAccounts.size(); // is is the count of accounts left to process
            while (i > 0) {
                i --;
                String removedSid = removedAccounts.get(i);
                try {
                    removeSingleAccount(removedSid);
                } catch (Exception e) {
                    // if anything bad happens, log the error and continue removing the rest of the accounts.
                    logger.error("Failed removing (child) account '" + removedSid + "'");
                }
            }
        }
        // remove the parent account too
        removeSingleAccount(operatedSid);

        return ok().build();
    }
    */

    /**
     * Removes all dependent resources of an account. Some resources like
     * CDRs are excluded.
     *
     * @param sid
     */
    private void removeAccoundDependencies(Sid sid) {
        DaoManager daoManager = (DaoManager) context.getAttribute(DaoManager.class.getName());
        // remove dependency entities first and dependent entities last. Also, do safer operation first (as a secondary rule)
        daoManager.getAnnouncementsDao().removeAnnouncements(sid);
        daoManager.getNotificationsDao().removeNotifications(sid);
        daoManager.getShortCodesDao().removeShortCodes(sid);
        daoManager.getOutgoingCallerIdsDao().removeOutgoingCallerIds(sid);
        daoManager.getTranscriptionsDao().removeTranscriptions(sid);
        daoManager.getRecordingsDao().removeRecordings(sid);
        daoManager.getApplicationsDao().removeApplications(sid);
        daoManager.getIncomingPhoneNumbersDao().removeIncomingPhoneNumbers(sid);
        daoManager.getClientsDao().removeClients(sid);
    }

    protected Response getAccounts(final MediaType responseType) {
        //First check if the account has the required permissions in general, this way we can fail fast and avoid expensive DAO operations
        checkPermission("RestComm:Read:Accounts");
        final Account account = userIdentityContext.getEffectiveAccount();
        if (account == null) {
            return status(NOT_FOUND).build();
        } else {
            final List<Account> accounts = new ArrayList<Account>();
            accounts.add(account);
            accounts.addAll(accountsDao.getAccounts(account.getSid()));
            if (APPLICATION_XML_TYPE == responseType) {
                final RestCommResponse response = new RestCommResponse(new AccountList(accounts));
                return ok(xstream.toXML(response), APPLICATION_XML).build();
            } else if (APPLICATION_JSON_TYPE == responseType) {
                return ok(gson.toJson(accounts), APPLICATION_JSON).build();
            } else {
                return null;
            }
        }
    }

    protected Response putAccount(final MultivaluedMap<String, String> data, final MediaType responseType) {
        //First check if the account has the required permissions in general, this way we can fail fast and avoid expensive DAO operations
        checkPermission("RestComm:Create:Accounts");
        // what if effectiveAccount is null ?? - no need to check since we checkAuthenticatedAccount() in AccountsEndoint.init()
        final Sid sid = userIdentityContext.getEffectiveAccount().getSid();
        Account account = null;
        try {
            account = createFrom(sid, data);
        } catch (final NullPointerException exception) {
            return status(BAD_REQUEST).entity(exception.getMessage()).build();
        }

        // If Account already exists don't add it again
        /*
            Account creation rules:
            - either be Administrator or have the following permission: RestComm:Create:Accounts
            - only Administrators can choose a role for newly created accounts. Normal users will create accounts with the same role as their own.
         */
        if (accountsDao.getAccount(account.getSid()) == null && !account.getEmailAddress().equalsIgnoreCase("administrator@company.com")) {
            final Account parent = accountsDao.getAccount(sid);
            if (parent.getStatus().equals(Account.Status.ACTIVE) && isSecuredByPermission("RestComm:Create:Accounts")) {
                if (!hasAccountRole(getAdministratorRole()) || !data.containsKey("Role")) {
                    account = account.setRole(parent.getRole());
                }
                accountsDao.addAccount(account);

                // Create default SIP client data
                MultivaluedMap<String, String> clientData = new MultivaluedMapImpl();
                String username = data.getFirst("EmailAddress").split("@")[0];
                clientData.add("Login", username);
                clientData.add("Password", data.getFirst("Password"));
                clientData.add("FriendlyName", account.getFriendlyName());
                clientData.add("AccountSid", account.getSid().toString());
                Client client = clientDao.getClient(clientData.getFirst("Login"));
                if (client == null) {
                    client = createClientFrom(account.getSid(), clientData);
                    clientDao.addClient(client);
                }
            } else {
                throw new InsufficientPermission();
            }
        } else {
            return status(CONFLICT).entity("The email address used for the new account is already in use.").build();
        }

        if (APPLICATION_JSON_TYPE == responseType) {
            return ok(gson.toJson(account), APPLICATION_JSON).build();
        } else if (APPLICATION_XML_TYPE == responseType) {
            final RestCommResponse response = new RestCommResponse(account);
            return ok(xstream.toXML(response), APPLICATION_XML).build();
        } else {
            return null;
        }
    }

    private Client createClientFrom(final Sid accountSid, final MultivaluedMap<String, String> data) {
        final Client.Builder builder = Client.builder();
        final Sid sid = Sid.generate(Sid.Type.CLIENT);

        // TODO: need to encrypt this password because it's same with Account
        // password.
        // Don't implement now. Opened another issue for it.
        // String password = new Md5Hash(data.getFirst("Password")).toString();
        String password = data.getFirst("Password");

        builder.setSid(sid);
        builder.setAccountSid(accountSid);
        builder.setApiVersion(getApiVersion(data));
        builder.setLogin(data.getFirst("Login"));
        builder.setPassword(password);
        builder.setFriendlyName(data.getFirst("FriendlyName"));
        builder.setStatus(Client.ENABLED);
        String rootUri = configuration.getString("root-uri");
        rootUri = StringUtils.addSuffixIfNotPresent(rootUri, "/");
        final StringBuilder buffer = new StringBuilder();
        buffer.append(rootUri).append(getApiVersion(data)).append("/Accounts/").append(accountSid.toString())
                .append("/Clients/").append(sid.toString());
        builder.setUri(URI.create(buffer.toString()));
        return builder.build();
    }

    /**
     * Fills an account entity object with values supplied from an http request
     *
     * @param account
     * @param data
     * @return
     * @throws AccountAlreadyClosed
     */
    private Account prepareAccountForUpdate(final Account account, final MultivaluedMap<String, String> data) throws AccountAlreadyClosed {
        Account result = account;
        boolean isPasswordReset = false;
        Account.Status newStatus = null;
        try {
            // if the account is already CLOSED, no updates are allowed
            if (account.getStatus() == Account.Status.CLOSED) {
                throw new AccountAlreadyClosed();
            }
            if (data.containsKey("Status")) {
                newStatus = Account.Status.getValueOf(data.getFirst("Status").toLowerCase());
                if (newStatus == Account.Status.CLOSED)
                    return account.setStatus(Account.Status.CLOSED);
                // if the status is switched to CLOSED, the rest of the updates are ignored.
            }
            if (data.containsKey("FriendlyName")) {
                result = result.setFriendlyName(data.getFirst("FriendlyName"));
            }
            if (data.containsKey("Password")) {
                // if this is a reset-password operation, we also need to set the account status to active
                if (account.getStatus() == Account.Status.UNINITIALIZED)
                    isPasswordReset = true;

                final String hash = new Md5Hash(data.getFirst("Password")).toString();
                result = result.setAuthToken(hash);
            }
            if (data.containsKey("Auth_Token")) {
                result = result.setAuthToken(data.getFirst("Auth_Token"));
                // if this is a reset-password operation, we also need to set the account status to active
                if (account.getStatus() == Account.Status.UNINITIALIZED)
                    isPasswordReset = true;
            }
            if (newStatus != null) {
                result = result.setStatus(newStatus);
            } else {
                // if this is a password reset operation we need to activate the account (in case there is no explicity Status passed of course)
                if (isPasswordReset)
                    result = result.setStatus(Account.Status.ACTIVE);
            }
            if (data.containsKey("Role")) {
                Account operatingAccount = userIdentityContext.getEffectiveAccount();
                // Only allow role change for administrators. Multitenancy checks will take care of restricting the modification scope to sub-accounts.
                if (userIdentityContext.getEffectiveAccountRoles().contains(getAdministratorRole())) {
                    result = result.setRole(data.getFirst("Role"));
                } else
                    throw new AuthorizationException();
            }
        } catch (AuthorizationException | AccountAlreadyClosed e) {
            // some exceptions should reach outer layers and result in 403
            throw e;
        } catch (Exception e) {
            if (logger.isInfoEnabled()) {
                logger.info("Exception during Account update: "+e.getStackTrace());
            }
        }
        return result;
    }

    protected Response updateAccount(final String identifier, final MultivaluedMap<String, String> data,
            final MediaType responseType) {
        // First check if the account has the required permissions in general, this way we can fail fast and avoid expensive DAO
        // operations
        checkPermission("RestComm:Modify:Accounts");
        Sid sid = null;
        Account account = null;
        try {
            sid = new Sid(identifier);
            account = accountsDao.getAccount(sid);
        } catch (Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("At update account, exception trying to get SID. Seems we have email as identifier");
            }
        }
        if (account == null) {
            if (logger.isDebugEnabled()) {
                logger.debug("At update account, trying to get account using email as identifier");
            }
            account = accountsDao.getAccount(identifier);
        }

        if (account == null) {
            return status(NOT_FOUND).build();
        } else {
            // If the account is CLOSED, no updates are allowed. Return a BAD_REQUEST status code.
            Account modifiedAccount;
            try {
                modifiedAccount = prepareAccountForUpdate(account, data);
            } catch (AccountAlreadyClosed accountAlreadyClosed) {
                return status(BAD_REQUEST).build();
            }

            secure(modifiedAccount, "RestComm:Modify:Accounts", SecuredType.SECURED_ACCOUNT);
            // are we closing the account ?
            if (account.getStatus() != Account.Status.CLOSED && modifiedAccount.getStatus() == Account.Status.CLOSED) {
                closeAccountTree(modifiedAccount);
                accountsDao.updateAccount(modifiedAccount);
            } else {
                // if we're not closing the account, update SIP client of the corresponding Account.
                // Password and FriendlyName fields are synched.
                String email = modifiedAccount.getEmailAddress();
                if (email != null && !email.equals("")) {
                    String username = email.split("@")[0];
                    Client client = clientDao.getClient(username);
                    if (client != null) {
                        // TODO: need to encrypt this password because it's
                        // same with Account password.
                        // Don't implement now. Opened another issue for it.
                        if (data.containsKey("Password")) {
                            // Md5Hash(data.getFirst("Password")).toString();
                            String password = data.getFirst("Password");
                            client = client.setPassword(password);
                        }

                        if (data.containsKey("FriendlyName")) {
                            client = client.setFriendlyName(data.getFirst("FriendlyName"));
                        }

                        clientDao.updateClient(client);
                    }
                }
                accountsDao.updateAccount(modifiedAccount);
            }

            if (APPLICATION_JSON_TYPE == responseType) {
                return ok(gson.toJson(modifiedAccount), APPLICATION_JSON).build();
            } else if (APPLICATION_XML_TYPE == responseType) {
                final RestCommResponse response = new RestCommResponse(modifiedAccount);
                return ok(xstream.toXML(response), APPLICATION_XML).build();
            } else {
                return null;
            }
        }
    }

    /**
     * Removes all resources belonging to an account and sets its status to CLOSED.
     *
     * @param closedAccount
     */
    private void closeSingleAccount(Account closedAccount) {
        removeAccoundDependencies(closedAccount.getSid());
        // finally, set account status to closed.
        closedAccount = closedAccount.setStatus(Account.Status.CLOSED);
        accountsDao.updateAccount(closedAccount);
    }

    /**
     * Closes an account along with all its children (the whole tree). Dependent entities are removed and,
     * if configured, notification are sent to the rcml server (RVD) as well.
     *
     * @param parentAccount
     */
    private void closeAccountTree(Account parentAccount) {
        // close child accounts
        List<String> subAccountsToClose = accountsDao.getSubAccountSidsRecursive(parentAccount.getSid());
        List<String> closedSubAccounts = new ArrayList<String>();
        if (subAccountsToClose != null && !subAccountsToClose.isEmpty()) {
            int i = subAccountsToClose.size(); // is is the count of accounts left to process
            // we iterate backwards to handle child accounts first, parent accounts next
            while (i > 0) {
                i --;
                String removedSid = subAccountsToClose.get(i);
                try {
                    Account subAccount = accountsDao.getAccount(new Sid(removedSid));
                    closeSingleAccount(subAccount);
                    closedSubAccounts.add(subAccount.getSid().toString());
                } catch (Exception e) {
                    // if anything bad happens, log the error and continue removing the rest of the accounts.
                    logger.error("Failed removing (child) account '" + removedSid + "'");
                    closedSubAccounts.add(removedSid.toString()); // we choose to remove remove projects for this account. TODO review this bahavior
                }
            }
        }
        // close parent account too
        closeSingleAccount(parentAccount);
        closedSubAccounts.add(parentAccount.getSid().toString());
        // do we need to also notify the application sever (RVD) ?
        RestcommConfiguration rcommConfiguration = RestcommConfiguration.getInstance();
        RcmlserverConfigurationSet config = rcommConfiguration.getRcmlserver();
        if (config != null && config.getNotify()) {
            // create an RcmlserverApi object only if we will need to notify
            RcmlserverApi api = new RcmlserverApi(rcommConfiguration.getMain(), rcommConfiguration.getRcmlserver());
            Account loggedAccount = userIdentityContext.getEffectiveAccount();
            api.notifyAccountsRemovalAsync(closedSubAccounts, loggedAccount.getSid().toString(), loggedAccount.getAuthToken() );
        }
    }

    private void validate(final MultivaluedMap<String, String> data) throws NullPointerException {
        if (!data.containsKey("EmailAddress")) {
            throw new NullPointerException("Email address can not be null.");
        } else if (!data.containsKey("Password")) {
            throw new NullPointerException("Password can not be null.");
        }
    }

}