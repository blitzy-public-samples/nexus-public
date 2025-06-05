/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.coreui;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.annotation.PreDestroy;
import javax.inject.Named;
import javax.inject.Singleton;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.sonatype.goodies.common.Time;
import org.sonatype.nexus.common.text.Strings2;
import org.sonatype.nexus.crypto.secrets.Secret;
import org.sonatype.nexus.crypto.secrets.SecretsService;
import org.sonatype.nexus.extdirect.DirectComponent;
import org.sonatype.nexus.extdirect.DirectComponentSupport;
import org.sonatype.nexus.httpclient.HttpClientManager;
import org.sonatype.nexus.httpclient.config.AuthenticationConfiguration;
import org.sonatype.nexus.httpclient.config.ConnectionConfiguration;
import org.sonatype.nexus.httpclient.config.HttpClientConfiguration;
import org.sonatype.nexus.httpclient.config.NtlmAuthenticationConfiguration;
import org.sonatype.nexus.httpclient.config.ProxyConfiguration;
import org.sonatype.nexus.httpclient.config.ProxyServerConfiguration;
import org.sonatype.nexus.httpclient.config.UsernameAuthenticationConfiguration;
import org.sonatype.nexus.rapture.PasswordPlaceholder;
import org.sonatype.nexus.security.UserIdHelper;
import org.sonatype.nexus.validation.Validate;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Timed;
import com.softwarementors.extjs.djn.config.annotations.DirectAction;
import com.softwarementors.extjs.djn.config.annotations.DirectMethod;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;

import static com.google.common.base.Preconditions.checkNotNull;

//
// FIXME: overly complex conversion due to lack of nested structure in exchange-object payload
//

/**
 * HTTP System Settings {@link DirectComponent}.
 *
 * @since 3.0
 */
@Named
@Singleton
@DirectAction(action = "coreui_HttpSettings")
public class HttpSettingsComponent
    extends DirectComponentSupport
{
  private final Logger log = LoggerFactory.getLogger(HttpSettingsComponent.class);
  
  private final HttpClientManager httpClientManager;

  private final SecretsService secretsService;
  
  private final ExecutorService virtualThreadExecutor;

  @Inject
  public HttpSettingsComponent(final HttpClientManager httpClientManager, final SecretsService secretsService) {
    this.httpClientManager = checkNotNull(httpClientManager);
    this.secretsService = checkNotNull(secretsService);
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  /**
   * Retrieves HTTP system settings
   * 
   * Uses virtual threads for improved I/O performance with Java 21.
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresPermissions("nexus:settings:read")
  public HttpSettingsXO read() {
    try {
      return virtualThreadExecutor.submit(() -> convert(httpClientManager.getConfiguration())).get();
    } catch (Exception e) {
      log.error("Error retrieving HTTP settings", e);
      throw new RuntimeException("Failed to retrieve HTTP settings", e);
    }
  }

  private HttpSettingsXO convert(final HttpClientConfiguration value) {
    HttpSettingsXO.Builder builder = HttpSettingsXO.builder();
    if (value.getConnection() != null) {
      ConnectionConfiguration connection = value.getConnection();
      builder.userAgentSuffix(connection.getUserAgentSuffix());
      builder.timeout(connection.getTimeout() != null ? connection.getTimeout().toSecondsI() : null);
      builder.retries(connection.getRetries());
    }

    if (value.getProxy() != null) {
      ProxyConfiguration proxy = value.getProxy();
      if (proxy.getHttp() != null) {
        configureHttpProxy(proxy.getHttp(), builder);
      }

      if (proxy.getHttps() != null) {
        configureHttpsProxy(proxy.getHttps(), builder);
      }

      if (proxy.getNonProxyHosts() != null) {
        builder.nonProxyHosts(Set.of(proxy.getNonProxyHosts()));
      }
    }

    // ignore authentication, this is not exposed for global configuration
    return builder.build();
  }

  /**
   * Updates HTTP system settings.
   * 
   * Uses virtual threads for improved I/O performance with Java 21.
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresAuthentication
  @RequiresPermissions("nexus:settings:update")
  @Validate
  public HttpSettingsXO update(@NotNull @Valid final HttpSettingsXO settings) {
    try {
      return virtualThreadExecutor.submit(() -> {
        HttpClientConfiguration previous = httpClientManager.getConfiguration();
        HttpClientConfiguration model = null;
        try {
          model = convert(settings, previous);
        }
        catch (Exception e) {
          removeSecrets(previous, model);
          throw e;
        }
        httpClientManager.setConfiguration(model);
        removeSecrets(previous, model);
        return read();
      }).get();
    } catch (Exception e) {
      log.error("Error updating HTTP settings", e);
      throw new RuntimeException("Failed to update HTTP settings", e);
    }
  }

  private HttpClientConfiguration convert(final HttpSettingsXO value, final HttpClientConfiguration previous) {
    HttpClientConfiguration result = httpClientManager.newConfiguration();

    if (!Strings2.isBlank(value.userAgentSuffix())) {
      ensureConnectionInitialized(result);
      result.getConnection().setUserAgentSuffix(value.userAgentSuffix());
    }

    if (value.timeout() != null) {
      ensureConnectionInitialized(result);
      result.getConnection().setTimeout(Time.seconds(value.timeout()));
    }

    if (value.retries() != null) {
      ensureConnectionInitialized(result);
      result.getConnection().setRetries(value.retries());
    }

    // http proxy
    if (Boolean.TRUE.equals(value.httpEnabled())) {
      ensureProxyInitialized(result);
      ProxyServerConfiguration proxyConfig = new ProxyServerConfiguration();
      proxyConfig.setEnabled(true);
      proxyConfig.setHost(value.httpHost());
      proxyConfig.setPort(value.httpPort());
      proxyConfig
          .setAuthentication(auth(value.httpAuthEnabled(), value.httpAuthUsername(), value.httpAuthPassword(),
              value.httpAuthNtlmHost(), value.httpAuthNtlmDomain(),
              getHttpSecret(previous)));
      result.getProxy().setHttp(proxyConfig);
    }

    // https proxy
    if (Boolean.TRUE.equals(value.httpsEnabled())) {
      ensureProxyInitialized(result);
      ProxyServerConfiguration proxyConfig = new ProxyServerConfiguration();
      proxyConfig.setEnabled(true);
      proxyConfig.setHost(value.httpsHost());
      proxyConfig.setPort(value.httpsPort());
      proxyConfig.setAuthentication(
          auth(value.httpsAuthEnabled(), value.httpsAuthUsername(), value.httpsAuthPassword(),
              value.httpsAuthNtlmHost(), value.httpsAuthNtlmDomain(),
              getHttpsSecret(previous)));
      result.getProxy().setHttps(proxyConfig);
    }

    if (value.nonProxyHosts() != null) {
      ensureProxyInitialized(result);
      result.getProxy().setNonProxyHosts(value.nonProxyHosts().toArray(new String[0]));
    }

    // ignore authentication, this is not exposed for global configuration
    return result;
  }

  @Nullable
  private AuthenticationConfiguration auth(
      final Boolean enabled,
      final String username,
      final String password,
      final String host,
      final String domain,
      final Secret previous)
  {
    if (Boolean.FALSE.equals(enabled)) {
      return null;
    }

    // HACK: non-optimal use of host/domain to determine authentication type
    if (host != null || domain != null) {
      NtlmAuthenticationConfiguration ntlmAuthConfig = new NtlmAuthenticationConfiguration();
      ntlmAuthConfig.setUsername(username);
      ntlmAuthConfig.setPassword(encrypt(password, previous));
      ntlmAuthConfig.setHost(host);
      ntlmAuthConfig.setDomain(domain);
      return ntlmAuthConfig;
    }
    else {
      UsernameAuthenticationConfiguration userAuthConfig = new UsernameAuthenticationConfiguration();
      userAuthConfig.setUsername(username);
      userAuthConfig.setPassword(encrypt(password, previous));
      return userAuthConfig;
    }
  }

  private Secret encrypt(String password, Secret previous) {
    if (Strings2.isBlank(password) || PasswordPlaceholder.is(password)) {
      return previous;
    }
    else {
      return secretsService.encryptMaven(
          AuthenticationConfiguration.AUTHENTICATION_CONFIGURATION,
          password.toCharArray(),
          UserIdHelper.get());
    }
  }

  private void removeSecrets(HttpClientConfiguration previous, HttpClientConfiguration newConfig) {
    if (!Objects.equals(getSecretId(getHttpSecret(previous)), getSecretId(getHttpSecret(newConfig)))) {
      removeSecret(getHttpAuthConfig(previous));
    }
    if (!Objects.equals(getSecretId(getHttpsSecret(previous)), getSecretId(getHttpsSecret(newConfig)))) {
      removeSecret(getHttpsAuthConfig(previous));
    }
  }

  private void removeSecret(AuthenticationConfiguration authConfig) {
    if (authConfig != null) {
      if (NtlmAuthenticationConfiguration.TYPE.equals(authConfig.getType())) {
        NtlmAuthenticationConfiguration ntlmAuth = (NtlmAuthenticationConfiguration) authConfig;
        secretsService.remove(ntlmAuth.getPassword());
      }
      else {
        UsernameAuthenticationConfiguration userNameAuth = (UsernameAuthenticationConfiguration) authConfig;
        secretsService.remove(userNameAuth.getPassword());
      }
    }
  }

  private Secret getHttpSecret(final HttpClientConfiguration configuration) {
    return Optional.ofNullable(configuration)
        .map(HttpClientConfiguration::getProxy)
        .map(ProxyConfiguration::getHttp)
        .map(ProxyServerConfiguration::getAuthentication)
        .map(AuthenticationConfiguration::getSecret)
        .orElse(null);
  }

  private Secret getHttpsSecret(final HttpClientConfiguration configuration) {
    return Optional.ofNullable(configuration)
        .map(HttpClientConfiguration::getProxy)
        .map(ProxyConfiguration::getHttps)
        .map(ProxyServerConfiguration::getAuthentication)
        .map(AuthenticationConfiguration::getSecret)
        .orElse(null);
  }

  private String getSecretId(final Secret secret) {
    return Optional.ofNullable(secret).map(Secret::getId).orElse(null);
  }

  private AuthenticationConfiguration getHttpAuthConfig(final HttpClientConfiguration configuration) {
    return Optional.ofNullable(configuration)
        .map(HttpClientConfiguration::getProxy)
        .map(ProxyConfiguration::getHttp)
        .map(ProxyServerConfiguration::getAuthentication)
        .orElse(null);
  }

  private AuthenticationConfiguration getHttpsAuthConfig(final HttpClientConfiguration configuration) {
    return Optional.ofNullable(configuration)
        .map(HttpClientConfiguration::getProxy)
        .map(ProxyConfiguration::getHttps)
        .map(ProxyServerConfiguration::getAuthentication)
        .orElse(null);
  }

  private void ensureConnectionInitialized(final HttpClientConfiguration configuration) {
    if (configuration.getConnection() == null) {
      configuration.setConnection(new ConnectionConfiguration());
    }
  }

  private void ensureProxyInitialized(final HttpClientConfiguration configuration) {
    if (configuration.getProxy() == null) {
      configuration.setProxy(new ProxyConfiguration());
    }
  }

  private void configureHttpProxy(ProxyServerConfiguration http, HttpSettingsXO.Builder result) {
    result.httpEnabled(http.isEnabled());
    result.httpHost(http.getHost());
    result.httpPort(http.getPort());

    if (http.getAuthentication() instanceof UsernameAuthenticationConfiguration auth) {
      result.httpAuthEnabled(true);
      result.httpAuthUsername(auth.getUsername());
      result.httpAuthPassword(PasswordPlaceholder.get(auth.getPassword()));
    }
    else if (http.getAuthentication() instanceof NtlmAuthenticationConfiguration auth) {
      result.httpAuthEnabled(true);
      result.httpAuthUsername(auth.getUsername());
      result.httpAuthPassword(PasswordPlaceholder.get(auth.getPassword()));
      result.httpAuthNtlmHost(auth.getHost());
      result.httpAuthNtlmDomain(auth.getDomain());
    }
  }

  private void configureHttpsProxy(ProxyServerConfiguration https, HttpSettingsXO.Builder result) {
    result.httpsEnabled(https.isEnabled());
    result.httpsHost(https.getHost());
    result.httpsPort(https.getPort());

    if (https.getAuthentication() instanceof UsernameAuthenticationConfiguration auth) {
      result.httpsAuthEnabled(true);
      result.httpsAuthUsername(auth.getUsername());
      result.httpsAuthPassword(PasswordPlaceholder.get(auth.getPassword()));
    }
    else if (https.getAuthentication() instanceof NtlmAuthenticationConfiguration auth) {
      result.httpsAuthEnabled(true);
      result.httpsAuthUsername(auth.getUsername());
      result.httpsAuthPassword(PasswordPlaceholder.get(auth.getPassword()));
      result.httpsAuthNtlmHost(auth.getHost());
      result.httpsAuthNtlmDomain(auth.getDomain());
    }
  }
  
  /**
   * Cleanup resources when the component is destroyed.
   */
  @PreDestroy
  public void shutdown() {
    if (virtualThreadExecutor != null) {
      log.debug("Shutting down virtual thread executor");
      virtualThreadExecutor.shutdown();
    }
  }
}