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
package org.sonatype.nexus.internal.email;

import org.sonatype.nexus.common.text.Strings2;
import org.sonatype.nexus.crypto.secrets.Secret;
import org.sonatype.nexus.email.EmailConfiguration;

/**
 * {@link EmailConfiguration} data.
 *
 * @since 3.21
 */
public class EmailConfigurationData
    implements Cloneable, EmailConfiguration
{
  private boolean enabled;

  private String host;

  private int port;

  private String username;

  private Secret password;

  private String fromAddress;

  private String subjectPrefix;

  private boolean startTlsEnabled;

  private boolean startTlsRequired;

  private boolean sslOnConnectEnabled;

  private boolean sslCheckServerIdentityEnabled;

  private boolean nexusTrustStoreEnabled;

  @Override
  public boolean isEnabled() {
    return enabled;
  }

  @Override
  public void setEnabled(final boolean enabled) {
    this.enabled = enabled;
  }

  @Override
  public String getHost() {
    return host;
  }

  @Override
  public void setHost(final String host) {
    this.host = host;
  }

  @Override
  public int getPort() {
    return port;
  }

  @Override
  public void setPort(final int port) {
    this.port = port;
  }

  @Override
  public String getUsername() {
    return username;
  }

  @Override
  public void setUsername(final String username) {
    this.username = username;
  }

  @Override
  public Secret getPassword() {
    return password;
  }

  @Override
  public void setPassword(final Secret password) {
    this.password = password;
  }

  @Override
  public String getFromAddress() {
    return fromAddress;
  }

  @Override
  public void setFromAddress(final String fromAddress) {
    this.fromAddress = fromAddress;
  }

  @Override
  public String getSubjectPrefix() {
    return subjectPrefix;
  }

  @Override
  public void setSubjectPrefix(final String subjectPrefix) {
    this.subjectPrefix = subjectPrefix;
  }

  @Override
  public boolean isStartTlsEnabled() {
    return startTlsEnabled;
  }

  @Override
  public void setStartTlsEnabled(final boolean startTlsEnabled) {
    this.startTlsEnabled = startTlsEnabled;
  }

  @Override
  public boolean isStartTlsRequired() {
    return startTlsRequired;
  }

  @Override
  public void setStartTlsRequired(final boolean startTlsRequired) {
    this.startTlsRequired = startTlsRequired;
  }

  @Override
  public boolean isSslOnConnectEnabled() {
    return sslOnConnectEnabled;
  }

  @Override
  public void setSslOnConnectEnabled(final boolean sslOnConnectEnabled) {
    this.sslOnConnectEnabled = sslOnConnectEnabled;
  }

  @Override
  public boolean isSslCheckServerIdentityEnabled() {
    return sslCheckServerIdentityEnabled;
  }

  @Override
  public void setSslCheckServerIdentityEnabled(final boolean sslCheckServerIdentityEnabled) {
    this.sslCheckServerIdentityEnabled = sslCheckServerIdentityEnabled;
  }

  @Override
  public boolean isNexusTrustStoreEnabled() {
    return nexusTrustStoreEnabled;
  }

  @Override
  public void setNexusTrustStoreEnabled(final boolean nexusTrustStoreEnabled) {
    this.nexusTrustStoreEnabled = nexusTrustStoreEnabled;
  }

  @Override
  public EmailConfigurationData copy() {
    // Using pattern matching to simplify the copy implementation
    if (this instanceof EmailConfigurationData data) {
      try {
        return (EmailConfigurationData) data.clone();
      }
      catch (CloneNotSupportedException e) {
        throw new RuntimeException(e);
      }
    }
    throw new IllegalStateException("Unexpected object type");
  }

  @Override
  public String toString() {
    // Using String Templates for more readable output while maintaining security masking
    return STR."""
        {getClass().getSimpleName()}{
        enabled={enabled},
        host='{host}',
        port={port},
        username='{username}',
        password='{Strings2.MASK}',
        fromAddress='{fromAddress}',
        subjectPrefix='{subjectPrefix}',
        startTlsEnabled={startTlsEnabled},
        startTlsRequired={startTlsRequired},
        sslOnConnectEnabled={sslOnConnectEnabled},
        sslCheckServerIdentityEnabled={sslCheckServerIdentityEnabled},
        nexusTrustStoreEnabled={nexusTrustStoreEnabled}
        }""";
  }
  
  /**
   * Apply pattern matching to extract configuration data.
   * This method demonstrates how to use pattern matching with EmailConfiguration objects.
   *
   * @param config The email configuration to extract data from
   * @return A formatted string with the configuration details
   */
  public static String extractConfigData(EmailConfiguration config) {
    if (config instanceof EmailConfigurationData data) {
      // Extract values using pattern matching and getters
      boolean enabled = data.isEnabled();
      String host = data.getHost();
      int port = data.getPort();
      
      // Using String Templates for formatted output with extracted values
      return STR."Email Configuration: host='{host}', port={port}, enabled={enabled}";
    }
    return "Unknown configuration type";
  }
  
  /**
   * Demonstrates how to use pattern matching with EmailConfiguration objects
   * when processing multiple configuration types.
   *
   * @param config The email configuration to process
   * @return A description of the configuration
   */
  public static String processConfiguration(Object config) {
    return switch (config) {
      case EmailConfigurationData data when data.isEnabled() -> 
          STR."Active email configuration for host: {data.getHost()}";
          
      case EmailConfigurationData data -> 
          STR."Inactive email configuration for host: {data.getHost()}";
          
      case null -> "No configuration provided";
      
      default -> "Unknown configuration type";
    };
  }
}
