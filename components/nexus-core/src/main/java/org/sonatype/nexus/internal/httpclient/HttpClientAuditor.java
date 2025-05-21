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
package org.sonatype.nexus.internal.httpclient;

import java.util.Arrays;
import java.util.Map;

import javax.inject.Named;
import javax.inject.Singleton;

import static java.lang.StringTemplate.STR;

import org.sonatype.nexus.audit.AuditData;
import org.sonatype.nexus.audit.AuditorSupport;
import org.sonatype.nexus.common.event.EventAware;
import org.sonatype.nexus.httpclient.config.AuthenticationConfiguration;
import org.sonatype.nexus.httpclient.config.ConnectionConfiguration;
import org.sonatype.nexus.httpclient.config.HttpClientConfiguration;
import org.sonatype.nexus.httpclient.config.HttpClientConfigurationChangedEvent;
import org.sonatype.nexus.httpclient.config.NtlmAuthenticationConfiguration;
import org.sonatype.nexus.httpclient.config.ProxyConfiguration;
import org.sonatype.nexus.httpclient.config.ProxyServerConfiguration;
import org.sonatype.nexus.httpclient.config.UsernameAuthenticationConfiguration;

import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;

/**
 * HttpClient auditor.
 * <p>
 * Enhanced with Java 21 features:
 * - Pattern Matching for instanceof to simplify type checking
 * - String Templates for improved message formatting
 * - Switch expressions with pattern matching for cleaner code
 *
 * @since 3.1
 */
@Named
@Singleton
public class HttpClientAuditor
    extends AuditorSupport
    implements EventAware
{
  public static final String DOMAIN = "httpclient";

  @Subscribe
  @AllowConcurrentEvents
  public void on(final HttpClientConfigurationChangedEvent event) {
    if (isRecording()) {
      HttpClientConfiguration configuration = event.getConfiguration();

      AuditData data = new AuditData();
      data.setDomain(DOMAIN);
      data.setType(CHANGED_TYPE);
      data.setContext(SYSTEM_CONTEXT);

      Map<String, Object> attributes = data.getAttributes();

      // Use pattern matching to handle connection configuration
      if (configuration.getConnection() instanceof ConnectionConfiguration connection) {
        attributes.put("connection.timeout", string(connection.getTimeout()));
        attributes.put("connection.retries", string(connection.getRetries()));
        attributes.put("connection.userAgentSuffix", string(connection.getUserAgentSuffix()));
        attributes.put("connection.useTrustStore", string(connection.getUseTrustStore()));
      }

      // Use pattern matching to handle proxy configuration
      if (configuration.getProxy() instanceof ProxyConfiguration proxy) {
        proxy(attributes, "proxy.http", proxy.getHttp());
        proxy(attributes, "proxy.https", proxy.getHttps());
        if (proxy.getNonProxyHosts() != null) {
          attributes.put("proxy.nonProxyHosts", string(Arrays.asList(proxy.getNonProxyHosts())));
        }
      }

      record(data);
    }
  }

  private static String key(final String prefix, final String suffix) {
    return STR."{prefix}.{suffix}";
  }

  private static void proxy(
      final Map<String, Object> attributes,
      final String prefix,
      final ProxyServerConfiguration server)
  {
    if (server == null) {
      return;
    }

    // Use String Templates for more readable attribute keys
    attributes.put(STR."{prefix}.enabled", string(server.isEnabled()));
    attributes.put(STR."{prefix}.host", server.getHost());
    attributes.put(STR."{prefix}.port", string(server.getPort()));

    AuthenticationConfiguration auth = server.getAuthentication();
    if (auth != null) {
      // Use String Templates for more readable attribute keys
      attributes.put(STR."{prefix}.authentication.type", auth.getType());
      switch (auth) {
        case UsernameAuthenticationConfiguration username -> {
          attributes.put(STR."{prefix}.authentication.username", username.getUsername());
          // omit password
        }
        case NtlmAuthenticationConfiguration ntlm -> {
          attributes.put(STR."{prefix}.authentication.username", ntlm.getUsername());
          attributes.put(STR."{prefix}.authentication.host", ntlm.getHost());
          attributes.put(STR."{prefix}.authentication.domain", ntlm.getDomain());
          // omit password
        }
        default -> { /* No additional attributes for other auth types */ }
      }
      attributes.put(STR."{prefix}.authentication.preemptive", auth.isPreemptive());
    }
  }
}