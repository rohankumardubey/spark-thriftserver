/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.service.auth;

import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.authorize.ProxyUsers;
import org.apache.spark.sql.SQLContext;
import org.apache.spark.sql.internal.SQLConf;
import org.apache.spark.sql.service.ReflectionUtils;
import org.apache.spark.sql.service.auth.shims.HadoopShims.KerberosNameShim;
import org.apache.spark.sql.service.auth.shims.ShimLoader;
import org.apache.spark.sql.service.auth.thrift.HadoopThriftAuthBridge;
import org.apache.spark.sql.service.auth.thrift.HadoopThriftAuthBridge.Server.ServerMode;
import org.apache.spark.sql.service.auth.thrift.SparkDelegationTokenManager;
import org.apache.spark.sql.service.cli.ServiceSQLException;
import org.apache.spark.sql.service.cli.thrift.ThriftCLIService;
import org.apache.spark.sql.service.internal.ServiceConf;
import org.apache.thrift.TProcessorFactory;
import org.apache.thrift.transport.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLServerSocket;
import javax.security.auth.login.LoginException;
import javax.security.sasl.Sasl;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.*;

/**
 * This class helps in some aspects of authentication. It creates the proper Thrift classes for the
 * given configuration as well as helps with authenticating requests.
 */
public class SparkAuthFactory {
  private static final Logger LOG = LoggerFactory.getLogger(SparkAuthFactory.class);

  public enum AuthTypes {
    NOSASL("NOSASL"),
    NONE("NONE"),
    LDAP("LDAP"),
    KERBEROS("KERBEROS"),
    CUSTOM("CUSTOM"),
    PAM("PAM");

    private final String authType;

    AuthTypes(String authType) {
      this.authType = authType;
    }

    public String getAuthName() {
      return authType;
    }

  }

  private HadoopThriftAuthBridge.Server saslServer;
  private String authTypeStr;
  private final String transportMode;
  private final SQLConf conf;
  private SQLContext sqlContext;
  private SparkDelegationTokenManager delegationTokenManager = null;

  public static final String SS2_PROXY_USER = "spark.sql.thriftserver.proxy.user";
  public static final String SS2_CLIENT_TOKEN = "sparkserverClientToken";

  private static Field keytabFile = null;
  private static Method getKeytab = null;
  static {
    Class<?> clz = UserGroupInformation.class;
    try {
      keytabFile = clz.getDeclaredField("keytabFile");
      keytabFile.setAccessible(true);
    } catch (NoSuchFieldException nfe) {
      LOG.debug("Cannot find private field \"keytabFile\" in class: " +
        UserGroupInformation.class.getCanonicalName(), nfe);
      keytabFile = null;
    }

    try {
      getKeytab = clz.getDeclaredMethod("getKeytab");
      getKeytab.setAccessible(true);
    } catch(NoSuchMethodException nme) {
      LOG.debug("Cannot find private method \"getKeytab\" in class:" +
        UserGroupInformation.class.getCanonicalName(), nme);
      getKeytab = null;
    }
  }

  public SparkAuthFactory(SQLContext sqlContext) throws TTransportException, IOException {
    this.conf = sqlContext.conf();
    this.sqlContext = sqlContext;
    transportMode = conf.getConf(ServiceConf.THRIFTSERVER_TRANSPORT_MODE());
    authTypeStr = conf.getConf(ServiceConf.THRIFTSERVER_AUTHENTICATION());

    // In http mode we use NOSASL as the default auth type
    if ("http".equalsIgnoreCase(transportMode)) {
      if (authTypeStr == null) {
        authTypeStr = AuthTypes.NOSASL.getAuthName();
      }
    } else {
      if (authTypeStr == null) {
        authTypeStr = AuthTypes.NONE.getAuthName();
      }
      if (authTypeStr.equalsIgnoreCase(AuthTypes.KERBEROS.getAuthName())) {
        String principal = conf.getConf(ServiceConf.THRIFTSERVER_KERBEROS_PRINCIPAL());
        String keytab = conf.getConf(ServiceConf.THRIFTSERVER_KERBEROS_KEYTAB());
        if (needUgiLogin(UserGroupInformation.getCurrentUser(),
          SecurityUtil.getServerPrincipal(principal, "0.0.0.0"), keytab)) {
          saslServer = ShimLoader.getHadoopThriftAuthBridge().createServer(keytab, principal);
        } else {
          // Using the default constructor to avoid unnecessary UGI login.
          saslServer = new HadoopThriftAuthBridge.Server();
        }

        // start delegation token manager
        delegationTokenManager = new SparkDelegationTokenManager();
          delegationTokenManager.startDelegationTokenSecretManager(
                  sqlContext.sparkContext().hadoopConfiguration(), ServerMode.HIVESERVER2);
          ReflectionUtils.setSuperField(saslServer, "secretManager", delegationTokenManager);
      }
    }
  }

  public Map<String, String> getSaslProperties() {
    Map<String, String> saslProps = new HashMap<String, String>();
    SaslQOP saslQOP = SaslQOP.fromString(conf.getConf(ServiceConf.THRIFTSERVER_THRIFT_SASL_QOP()));
    saslProps.put(Sasl.QOP, saslQOP.toString());
    saslProps.put(Sasl.SERVER_AUTH, "true");
    return saslProps;
  }

  public TTransportFactory getAuthTransFactory() throws LoginException {
    TTransportFactory transportFactory;
    if (authTypeStr.equalsIgnoreCase(AuthTypes.KERBEROS.getAuthName())) {
      try {
        transportFactory = saslServer.createTransportFactory(getSaslProperties());
      } catch (TTransportException e) {
        throw new LoginException(e.getMessage());
      }
    } else if (authTypeStr.equalsIgnoreCase(AuthTypes.NONE.getAuthName())) {
      transportFactory = PlainSaslHelper.getPlainTransportFactory(authTypeStr);
    } else if (authTypeStr.equalsIgnoreCase(AuthTypes.LDAP.getAuthName())) {
      transportFactory = PlainSaslHelper.getPlainTransportFactory(authTypeStr);
    } else if (authTypeStr.equalsIgnoreCase(AuthTypes.PAM.getAuthName())) {
      transportFactory = PlainSaslHelper.getPlainTransportFactory(authTypeStr);
    } else if (authTypeStr.equalsIgnoreCase(AuthTypes.NOSASL.getAuthName())) {
      transportFactory = new TTransportFactory();
    } else if (authTypeStr.equalsIgnoreCase(AuthTypes.CUSTOM.getAuthName())) {
      transportFactory = PlainSaslHelper.getPlainTransportFactory(authTypeStr);
    } else {
      throw new LoginException("Unsupported authentication type " + authTypeStr);
    }
    return transportFactory;
  }

  /**
   * Returns the thrift processor factory for SparkThriftServer running in binary mode
   * @param service
   * @return
   * @throws LoginException
   */
  public TProcessorFactory getAuthProcFactory(ThriftCLIService service) throws LoginException {
    if (authTypeStr.equalsIgnoreCase(AuthTypes.KERBEROS.getAuthName())) {
      return KerberosSaslHelper.getKerberosProcessorFactory(saslServer, service);
    } else {
      return PlainSaslHelper.getPlainProcessorFactory(service);
    }
  }

  public String getRemoteUser() {
    return saslServer == null ? null : saslServer.getRemoteUser();
  }

  public String getIpAddress() {
    if (saslServer == null || saslServer.getRemoteAddress() == null) {
      return null;
    } else {
      return saslServer.getRemoteAddress().getHostAddress();
    }
  }

  // Perform kerberos login using the hadoop shim API if the configuration is available
  public static void loginFromKeytab(SQLConf sqlConf) throws IOException {
    String principal = sqlConf.getConf(ServiceConf.THRIFTSERVER_KERBEROS_PRINCIPAL());
    String keyTabFile = sqlConf.getConf(ServiceConf.THRIFTSERVER_KERBEROS_KEYTAB());
    if (principal.isEmpty() || keyTabFile.isEmpty()) {
      throw new IOException("SparkThriftServer Kerberos principal or keytab " +
          "is not correctly configured");
    } else {
      UserGroupInformation.loginUserFromKeytab(
          SecurityUtil.getServerPrincipal(principal, "0.0.0.0"), keyTabFile);
    }
  }

  // Perform SPNEGO login using the hadoop shim API if the configuration is available
  public static UserGroupInformation loginFromSpnegoKeytabAndReturnUGI(SQLConf sqlConf)
    throws IOException {
    String principal = sqlConf.getConf(ServiceConf.THRIFTSERVER_SPNEGO_PRINCIPAL());
    String keyTabFile = sqlConf.getConf(ServiceConf.THRIFTSERVER_SPNEGO_KEYTAB());
    if (principal.isEmpty() || keyTabFile.isEmpty()) {
      throw new IOException("SparkThriftServer SPNEGO" +
          " principal or keytab is not correctly configured");
    } else {
      return UserGroupInformation.loginUserFromKeytabAndReturnUGI(
          SecurityUtil.getServerPrincipal(principal, "0.0.0.0"), keyTabFile);
    }
  }

  public static TTransport getSocketTransport(String host, int port, int loginTimeout) {
    return new TSocket(host, port, loginTimeout);
  }

  public static TTransport getSSLSocket(String host, int port, int loginTimeout)
    throws TTransportException {
    return TSSLTransportFactory.getClientSocket(host, port, loginTimeout);
  }

  public static TTransport getSSLSocket(String host, int port, int loginTimeout,
    String trustStorePath, String trustStorePassWord) throws TTransportException {
    TSSLTransportFactory.TSSLTransportParameters params =
      new TSSLTransportFactory.TSSLTransportParameters();
    params.setTrustStore(trustStorePath, trustStorePassWord);
    params.requireClientAuth(true);
    return TSSLTransportFactory.getClientSocket(host, port, loginTimeout, params);
  }

  public static TServerSocket getServerSocket(String hiveHost, int portNum)
    throws TTransportException {
    InetSocketAddress serverAddress;
    if (hiveHost == null || hiveHost.isEmpty()) {
      // Wildcard bind
      serverAddress = new InetSocketAddress(portNum);
    } else {
      serverAddress = new InetSocketAddress(hiveHost, portNum);
    }
    return new TServerSocket(serverAddress);
  }

  public static TServerSocket getServerSSLSocket(String hiveHost, int portNum, String keyStorePath,
      String keyStorePassWord, List<String> sslVersionBlacklist) throws TTransportException,
      UnknownHostException {
    TSSLTransportFactory.TSSLTransportParameters params =
        new TSSLTransportFactory.TSSLTransportParameters();
    params.setKeyStore(keyStorePath, keyStorePassWord);
    InetSocketAddress serverAddress;
    if (hiveHost == null || hiveHost.isEmpty()) {
      // Wildcard bind
      serverAddress = new InetSocketAddress(portNum);
    } else {
      serverAddress = new InetSocketAddress(hiveHost, portNum);
    }
    TServerSocket thriftServerSocket =
        TSSLTransportFactory.getServerSocket(portNum, 0, serverAddress.getAddress(), params);
    if (thriftServerSocket.getServerSocket() instanceof SSLServerSocket) {
      List<String> sslVersionBlacklistLocal = new ArrayList<String>();
      for (String sslVersion : sslVersionBlacklist) {
        sslVersionBlacklistLocal.add(sslVersion.trim().toLowerCase(Locale.ROOT));
      }
      SSLServerSocket sslServerSocket = (SSLServerSocket) thriftServerSocket.getServerSocket();
      List<String> enabledProtocols = new ArrayList<String>();
      for (String protocol : sslServerSocket.getEnabledProtocols()) {
        if (sslVersionBlacklistLocal.contains(protocol.toLowerCase(Locale.ROOT))) {
          LOG.debug("Disabling SSL Protocol: " + protocol);
        } else {
          enabledProtocols.add(protocol);
        }
      }
      sslServerSocket.setEnabledProtocols(enabledProtocols.toArray(new String[0]));
      LOG.info("SSL Server Socket Enabled Protocols: "
          + Arrays.toString(sslServerSocket.getEnabledProtocols()));
    }
    return thriftServerSocket;
  }

  // retrieve delegation token for the given user
  public String getDelegationToken(String owner, String renewer, String remoteAddr)
      throws ServiceSQLException {
    if (delegationTokenManager == null) {
      throw new ServiceSQLException(
          "Delegation token only supported over kerberos authentication", "08S01");
    }

    try {
      String tokenStr = delegationTokenManager.getDelegationTokenWithService(owner, renewer,
              SS2_CLIENT_TOKEN, remoteAddr);
      if (tokenStr == null || tokenStr.isEmpty()) {
        throw new ServiceSQLException(
            "Received empty retrieving delegation token for user " + owner, "08S01");
      }
      return tokenStr;
    } catch (IOException e) {
      throw new ServiceSQLException(
          "Error retrieving delegation token for user " + owner, "08S01", e);
    } catch (InterruptedException e) {
      throw new ServiceSQLException("delegation token retrieval interrupted", "08S01", e);
    }
  }

  // cancel given delegation token
  public void cancelDelegationToken(String delegationToken) throws ServiceSQLException {
    if (delegationTokenManager == null) {
      throw new ServiceSQLException(
          "Delegation token only supported over kerberos authentication", "08S01");
    }
    try {
      delegationTokenManager.cancelDelegationToken(delegationToken);
    } catch (IOException e) {
      throw new ServiceSQLException(
          "Error canceling delegation token " + delegationToken, "08S01", e);
    }
  }

  public void renewDelegationToken(String delegationToken) throws ServiceSQLException {
    if (delegationTokenManager == null) {
      throw new ServiceSQLException(
          "Delegation token only supported over kerberos authentication", "08S01");
    }
    try {
      delegationTokenManager.renewDelegationToken(delegationToken);
    } catch (IOException e) {
      throw new ServiceSQLException(
          "Error renewing delegation token " + delegationToken, "08S01", e);
    }
  }

  public String verifyDelegationToken(String delegationToken) throws ServiceSQLException {
    if (delegationTokenManager == null) {
      throw new ServiceSQLException(
          "Delegation token only supported over kerberos authentication", "08S01");
    }
    try {
      return delegationTokenManager.verifyDelegationToken(delegationToken);
    } catch (IOException e) {
      String msg =  "Error verifying delegation token " + delegationToken;
      LOG.error(msg, e);
      throw new ServiceSQLException(msg, "08S01", e);
    }
  }

  public String getUserFromToken(String delegationToken) throws ServiceSQLException {
    if (delegationTokenManager == null) {
      throw new ServiceSQLException(
          "Delegation token only supported over kerberos authentication", "08S01");
    }
    try {
      return delegationTokenManager.getUserFromToken(delegationToken);
    } catch (IOException e) {
      throw new ServiceSQLException(
          "Error extracting user from delegation token " + delegationToken, "08S01", e);
    }
  }

  public static void verifyProxyAccess(String realUser, String proxyUser, String ipAddress,
    org.apache.hadoop.conf.Configuration conf) throws ServiceSQLException {
    try {
      UserGroupInformation sessionUgi;
      if (UserGroupInformation.isSecurityEnabled()) {
        KerberosNameShim kerbName = ShimLoader.getHadoopShims().getKerberosNameShim(realUser);
        sessionUgi = UserGroupInformation.createProxyUser(
            kerbName.getServiceName(), UserGroupInformation.getLoginUser());
      } else {
        sessionUgi = UserGroupInformation.createRemoteUser(realUser);
      }
      if (!proxyUser.equalsIgnoreCase(realUser)) {
        ProxyUsers.refreshSuperUserGroupsConfiguration(conf);
        ProxyUsers.authorize(UserGroupInformation.createProxyUser(proxyUser, sessionUgi),
            ipAddress, null);
      }
    } catch (IOException e) {
      throw new ServiceSQLException(
        "Failed to validate proxy privilege of " + realUser + " for " + proxyUser, "08S01", e);
    }
  }

  public static boolean needUgiLogin(UserGroupInformation ugi, String principal, String keytab) {
    return null == ugi || !ugi.hasKerberosCredentials() || !ugi.getUserName().equals(principal) ||
      !Objects.equals(keytab, getKeytabFromUgi());
  }

  private static String getKeytabFromUgi() {
    synchronized (UserGroupInformation.class) {
      try {
        if (keytabFile != null) {
          return (String) keytabFile.get(null);
        } else if (getKeytab != null) {
          return (String) getKeytab.invoke(UserGroupInformation.getCurrentUser());
        } else {
          return null;
        }
      } catch (Exception e) {
        LOG.debug("Fail to get keytabFile path via reflection", e);
        return null;
      }
    }
  }
}