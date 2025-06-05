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

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import org.sonatype.nexus.httpclient.config.NonProxyHosts;
import org.sonatype.nexus.validation.constraint.Hostname;
import org.sonatype.nexus.validation.constraint.PortNumber;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * HTTP System Settings exchange object.
 *
 * @since 3.0
 */
public record HttpSettingsXO(
    String userAgentSuffix,
    
    /**
     * Timeout seconds.
     */
    @Min(1L)
    @Max(3600L)
    Integer timeout,
    
    @Min(0L)
    @Max(10L)
    Integer retries,
    
    Boolean httpEnabled,
    
    @Hostname
    String httpHost,
    
    @PortNumber
    Integer httpPort,
    
    Boolean httpAuthEnabled,
    
    String httpAuthUsername,
    
    String httpAuthPassword,
    
    String httpAuthNtlmHost,
    
    String httpAuthNtlmDomain,
    
    Boolean httpsEnabled,
    
    @Hostname
    String httpsHost,
    
    @PortNumber
    Integer httpsPort,
    
    Boolean httpsAuthEnabled,
    
    String httpsAuthUsername,
    
    String httpsAuthPassword,
    
    String httpsAuthNtlmHost,
    
    String httpsAuthNtlmDomain,
    
    @NonProxyHosts
    Set<String> nonProxyHosts
) {
  // Custom implementation of toString() to match the original implementation
  @Override
  public String toString() {
    return "HttpSettingsXO{" +
        "userAgentSuffix='" + userAgentSuffix + '\'' +
        ", timeout=" + timeout +
        ", retries=" + retries +
        ", httpEnabled=" + httpEnabled +
        ", httpHost='" + httpHost + '\'' +
        ", httpPort=" + httpPort +
        ", httpAuthEnabled=" + httpAuthEnabled +
        ", httpAuthUsername='" + httpAuthUsername + '\'' +
        ", httpAuthPassword='" + httpAuthPassword + '\'' +
        ", httpAuthNtlmHost='" + httpAuthNtlmHost + '\'' +
        ", httpAuthNtlmDomain='" + httpAuthNtlmDomain + '\'' +
        ", httpsEnabled=" + httpsEnabled +
        ", httpsHost='" + httpsHost + '\'' +
        ", httpsPort=" + httpsPort +
        ", httpsAuthEnabled=" + httpsAuthEnabled +
        ", httpsAuthUsername='" + httpsAuthUsername + '\'' +
        ", httpsAuthPassword='" + httpsAuthPassword + '\'' +
        ", httpsAuthNtlmHost='" + httpsAuthNtlmHost + '\'' +
        ", httpsAuthNtlmDomain='" + httpsAuthNtlmDomain + '\'' +
        ", nonProxyHosts=" + nonProxyHosts +
        '}';
  }
  
  /**
   * Provides a static method to create a new Builder instance.
   *
   * @return A new Builder for HttpSettingsXO.
   */
  public static Builder builder() {
      return new Builder();
  }

  // --- Builder Class ---
  public static class Builder {
      private String userAgentSuffix;
      private Integer timeout;
      private Integer retries;
      private Boolean httpEnabled;
      private String httpHost;
      private Integer httpPort;
      private Boolean httpAuthEnabled;
      private String httpAuthUsername;
      private String httpAuthPassword;
      private String httpAuthNtlmHost;
      private String httpAuthNtlmDomain;
      private Boolean httpsEnabled;
      private String httpsHost;
      private Integer httpsPort;
      private Boolean httpsAuthEnabled;
      private String httpsAuthUsername;
      private String httpsAuthPassword;
      private String httpsAuthNtlmHost;
      private String httpsAuthNtlmDomain;
      private Set<String> nonProxyHosts;

      // Private constructor to enforce usage of HttpSettingsXO.builder()
      private Builder() {
          // Set sensible defaults if applicable
          this.timeout = 30; // Example default
          this.retries = 3;  // Example default
          this.httpEnabled = true; // Example default
          this.httpsEnabled = true; // Example default
          this.nonProxyHosts = new HashSet<>(); // Initialize to avoid NullPointerException
      }

      public Builder userAgentSuffix(String userAgentSuffix) {
          this.userAgentSuffix = userAgentSuffix;
          return this;
      }

      public Builder timeout(Integer timeout) {
          this.timeout = timeout;
          return this;
      }

      public Builder retries(Integer retries) {
          this.retries = retries;
          return this;
      }

      public Builder httpEnabled(Boolean httpEnabled) {
          this.httpEnabled = httpEnabled;
          return this;
      }

      public Builder httpHost(String httpHost) {
          this.httpHost = httpHost;
          return this;
      }

      public Builder httpPort(Integer httpPort) {
          this.httpPort = httpPort;
          return this;
      }

      public Builder httpAuthEnabled(Boolean httpAuthEnabled) {
          this.httpAuthEnabled = httpAuthEnabled;
          return this;
      }

      public Builder httpAuthUsername(String httpAuthUsername) {
          this.httpAuthUsername = httpAuthUsername;
          return this;
      }

      public Builder httpAuthPassword(String httpAuthPassword) {
          this.httpAuthPassword = httpAuthPassword;
          return this;
      }

      public Builder httpAuthNtlmHost(String httpAuthNtlmHost) {
          this.httpAuthNtlmHost = httpAuthNtlmHost;
          return this;
      }

      public Builder httpAuthNtlmDomain(String httpAuthNtlmDomain) {
          this.httpAuthNtlmDomain = httpAuthNtlmDomain;
          return this;
      }

      public Builder httpsEnabled(Boolean httpsEnabled) {
          this.httpsEnabled = httpsEnabled;
          return this;
      }

      public Builder httpsHost(String httpsHost) {
          this.httpsHost = httpsHost;
          return this;
      }

      public Builder httpsPort(Integer httpsPort) {
          this.httpsPort = httpsPort;
          return this;
      }

      public Builder httpsAuthEnabled(Boolean httpsAuthEnabled) {
          this.httpsAuthEnabled = httpsAuthEnabled;
          return this;
      }

      public Builder httpsAuthUsername(String httpsAuthUsername) {
          this.httpsAuthUsername = httpsAuthUsername;
          return this;
      }

      public Builder httpsAuthPassword(String httpsAuthPassword) {
          this.httpsAuthPassword = httpsAuthPassword;
          return this;
      }

      public Builder httpsAuthNtlmHost(String httpsAuthNtlmHost) {
          this.httpsAuthNtlmHost = httpsAuthNtlmHost;
          return this;
      }

      public Builder httpsAuthNtlmDomain(String httpsAuthNtlmDomain) {
          this.httpsAuthNtlmDomain = httpsAuthNtlmDomain;
          return this;
      }

      public Builder nonProxyHosts(Set<String> nonProxyHosts) {
          // Defensively copy the set to ensure immutability and prevent external modification
          this.nonProxyHosts = (nonProxyHosts != null) ? new LinkedHashSet<>(nonProxyHosts) : new LinkedHashSet<>();
          return this;
      }

      public Builder addNonProxyHost(String host) {
          if (this.nonProxyHosts == null) {
              this.nonProxyHosts = new LinkedHashSet<>();
          }
          this.nonProxyHosts.add(host);
          return this;
      }

      /**
       * Builds the final HttpSettingsXO instance.
       * Note: Validation annotations on the record's fields (like @Min, @Max, @Hostname)
       * are automatically checked when the record constructor is called,
       * assuming a Bean Validation provider is configured.
       *
       * @return A new HttpSettingsXO instance.
       */
      public HttpSettingsXO build() {
          // Ensure nonProxyHosts is never null before passing to the record constructor
          if (this.nonProxyHosts == null) {
              this.nonProxyHosts = Collections.emptySet(); // Or new LinkedHashSet<>() if you prefer mutable
          }

          return new HttpSettingsXO(
              userAgentSuffix,
              timeout,
              retries,
              httpEnabled,
              httpHost,
              httpPort,
              httpAuthEnabled,
              httpAuthUsername,
              httpAuthPassword,
              httpAuthNtlmHost,
              httpAuthNtlmDomain,
              httpsEnabled,
              httpsHost,
              httpsPort,
              httpsAuthEnabled,
              httpsAuthUsername,
              httpsAuthPassword,
              httpsAuthNtlmHost,
              httpsAuthNtlmDomain,
              // Pass an unmodifiable set to maintain true immutability of the record's component
              Collections.unmodifiableSet(nonProxyHosts)
          );
      }
  }
}