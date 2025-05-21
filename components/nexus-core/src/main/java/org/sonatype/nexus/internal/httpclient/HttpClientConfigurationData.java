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

import javax.annotation.Nullable;
import javax.validation.Valid;

import org.sonatype.nexus.httpclient.config.AuthenticationConfiguration;
import org.sonatype.nexus.httpclient.config.ConnectionConfiguration;
import org.sonatype.nexus.httpclient.config.HttpClientConfiguration;
import org.sonatype.nexus.httpclient.config.ProxyConfiguration;

import org.apache.http.client.AuthenticationStrategy;
import org.apache.http.client.RedirectStrategy;

/**
 * {@link HttpClientConfiguration} data.
 *
 * @since 3.21
 */
public class HttpClientConfigurationData
    implements HttpClientConfiguration, Cloneable
{
  @Valid
  @Nullable
  private ConnectionConfiguration connection;

  @Valid
  @Nullable
  private ProxyConfiguration proxy;

  @Valid
  @Nullable
  private RedirectStrategy redirectStrategy;

  @Valid
  @Nullable
  private AuthenticationStrategy authenticationStrategy;

  @Valid
  @Nullable
  private Boolean disableContentCompression;

  /**
   * @see AuthenticationConfigurationDeserializer
   */
  @Valid
  @Nullable
  private AuthenticationConfiguration authentication;

  @Valid
  @Nullable
  private Boolean shouldNormalizeUri;

  @Override
  @Nullable
  public ConnectionConfiguration getConnection() {
    return connection;
  }

  @Override
  public void setConnection(@Nullable final ConnectionConfiguration connection) {
    this.connection = connection;
  }

  @Override
  @Nullable
  public ProxyConfiguration getProxy() {
    return proxy;
  }

  @Override
  public void setProxy(@Nullable final ProxyConfiguration proxy) {
    this.proxy = proxy;
  }

  @Override
  @Nullable
  public AuthenticationConfiguration getAuthentication() {
    return authentication;
  }

  @Override
  public void setAuthentication(@Nullable final AuthenticationConfiguration authentication) {
    this.authentication = authentication;
  }

  @Override
  @Nullable
  public RedirectStrategy getRedirectStrategy() {
    return redirectStrategy;
  }

  @Override
  public void setRedirectStrategy(@Nullable final RedirectStrategy redirectStrategy) {
    this.redirectStrategy = redirectStrategy;
  }

  @Nullable
  @Override
  public AuthenticationStrategy getAuthenticationStrategy() {
    return authenticationStrategy;
  }

  @Override
  public void setAuthenticationStrategy(
      @Nullable final AuthenticationStrategy authenticationStrategy)
  {
    this.authenticationStrategy = authenticationStrategy;
  }

  @Override
  public Boolean getNormalizeUri() {
    return shouldNormalizeUri;
  }

  @Override
  public void setNormalizeUri(final Boolean normalizeUri) {
    this.shouldNormalizeUri = normalizeUri;
  }

  @Override
  public Boolean getDisableContentCompression() {
    return disableContentCompression;
  }

  @Override
  public void setDisableContentCompression(final Boolean disableContentCompression) {
    this.disableContentCompression = disableContentCompression;
  }

  /**
   * Validates the configuration data using pattern matching to ensure all required components are properly configured.
   * 
   * @return true if the configuration is valid, false otherwise
   * @since 21.0
   */
  public boolean isValid() {
    // Using pattern matching to check configuration validity
    return switch(this) {
      // Case when proxy is configured but connection is missing
      case HttpClientConfigurationData config when config.proxy != null && config.connection == null -> false;
      // Case when authentication is configured but connection is missing
      case HttpClientConfigurationData config when config.authentication != null && config.connection == null -> false;
      // Default case - configuration is valid
      default -> true;
    };
  }

  /**
   * Returns a specific configuration component using pattern matching for type safety.
   * 
   * @param <T> the type of configuration component to retrieve
   * @param componentClass the class of the component to retrieve
   * @return the requested component or null if not available
   * @since 21.0
   */
  @SuppressWarnings("unchecked")
  public <T> T getConfigComponent(Class<T> componentClass) {
    return switch(componentClass.getSimpleName()) {
      case "ConnectionConfiguration" when connection != null -> (T) connection;
      case "ProxyConfiguration" when proxy != null -> (T) proxy;
      case "AuthenticationConfiguration" when authentication != null -> (T) authentication;
      case "RedirectStrategy" when redirectStrategy != null -> (T) redirectStrategy;
      case "AuthenticationStrategy" when authenticationStrategy != null -> (T) authenticationStrategy;
      default -> null;
    };
  }

  @Override
  public HttpClientConfigurationData copy() {
    try {
      HttpClientConfigurationData copy = (HttpClientConfigurationData) clone();
      
      // Using pattern matching for more efficient data handling
      // Only copy non-null components
      switch(this) {
        case HttpClientConfigurationData data when data.connection != null -> 
          copy.connection = data.connection.copy();
        case HttpClientConfigurationData _ -> { /* connection is null, no action needed */ }
      }
      
      switch(this) {
        case HttpClientConfigurationData data when data.proxy != null -> 
          copy.proxy = data.proxy.copy();
        case HttpClientConfigurationData _ -> { /* proxy is null, no action needed */ }
      }
      
      switch(this) {
        case HttpClientConfigurationData data when data.authentication != null -> 
          copy.authentication = data.authentication.copy();
        case HttpClientConfigurationData _ -> { /* authentication is null, no action needed */ }
      }
      
      // For strategies, we don't need to copy as they are singleton instances
      copy.redirectStrategy = this.redirectStrategy;
      copy.authenticationStrategy = this.authenticationStrategy;
      copy.disableContentCompression = this.disableContentCompression;
      copy.shouldNormalizeUri = this.shouldNormalizeUri;
      
      return copy;
    }
    catch (CloneNotSupportedException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public String toString() {
    // Using pattern matching for more efficient string representation
    return switch(this) {
      case HttpClientConfigurationData data -> {
        StringBuilder sb = new StringBuilder(getClass().getSimpleName())
            .append("{");
        
        if (data.connection != null) {
          sb.append("connection=").append(data.connection);
        }
        
        if (data.proxy != null) {
          if (data.connection != null) sb.append(", ");
          sb.append("proxy=").append(data.proxy);
        }
        
        if (data.authentication != null) {
          if (data.connection != null || data.proxy != null) sb.append(", ");
          sb.append("authentication=").append(data.authentication);
        }
        
        sb.append('}');
        yield sb.toString();
      }
    };
  }
}