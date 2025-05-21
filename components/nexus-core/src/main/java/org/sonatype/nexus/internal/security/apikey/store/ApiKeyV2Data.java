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
package org.sonatype.nexus.internal.security.apikey.store;

import java.time.OffsetDateTime;

import javax.annotation.Nullable;

import org.sonatype.nexus.crypto.secrets.Secret;
import org.sonatype.nexus.internal.security.apikey.ApiKeyInternal;

import org.apache.shiro.subject.PrincipalCollection;

import java.util.Arrays;
import java.util.Objects;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * An {@link ApiKeyInternal} data for use with {@link ApiKeyStoreV2Impl}
 * <p>
 * This implementation has been updated to use Java 21 pattern matching features for improved
 * type safety and security handling.
 */
public class ApiKeyV2Data
    implements ApiKeyInternal
{
  private PrincipalCollection principals;

  private String username;

  private String domain;

  private String accessKey;

  private Secret secret;

  private OffsetDateTime created;

  ApiKeyV2Data() { }

  ApiKeyV2Data(
      final String domain,
      final PrincipalCollection principals,
      final String accessKey,
      final Secret secret,
      @Nullable final OffsetDateTime created)
  {
    this.domain = checkNotNull(domain);
    this.principals = checkNotNull(principals);
    // Use pattern matching to extract the primary principal
    this.username = switch (principals.getPrimaryPrincipal()) {
      case String s -> s;
      case null -> throw new IllegalArgumentException("Primary principal cannot be null");
      case Object o -> o.toString();
    };
    this.accessKey = checkNotNull(accessKey);
    this.secret = checkNotNull(secret);
    this.created = created;
  }

  public String getAccessKey() {
    return accessKey;
  }

  @Override
  public char[] getApiKey() {
    // Optimize secret handling with Java 21's improved type-checking capabilities
    if (accessKey == null || secret == null) {
      throw new IllegalStateException("Access key and secret must be set");
    }
    
    int keyLength = accessKey.length();
    // Use pattern matching to safely handle the secret
    char[] secretPart = switch (secret) {
      case null -> throw new IllegalStateException("Secret cannot be null");
      case Secret s -> s.decrypt();
    };
    
    // Create and populate the token with improved security handling
    char[] token = new char[keyLength + secretPart.length];
    try {
      System.arraycopy(accessKey.toCharArray(), 0, token, 0, keyLength);
      System.arraycopy(secretPart, 0, token, keyLength, secretPart.length);
      return token;
    } finally {
      // Ensure secretPart is cleared from memory after use for security
      if (secretPart.length > 0) {
        java.util.Arrays.fill(secretPart, '\0');
      }
    }
  }

  @Override
  public OffsetDateTime getCreated() {
    return created;
  }

  @Override
  public String getDomain() {
    return domain;
  }

  @Override
  public PrincipalCollection getPrincipals() {
    return principals;
  }

  public Secret getSecret() {
    return secret;
  }

  public String getUsername() {
    return username;
  }

  public void setAccessKey(final String accessKey) {
    // Use pattern matching to validate the access key
    this.accessKey = switch (accessKey) {
      case null -> throw new IllegalArgumentException("Access key cannot be null");
      case String s when s.isEmpty() -> throw new IllegalArgumentException("Access key cannot be empty");
      case String s -> s;
    };
  }

  @Override
  public void setCreated(final OffsetDateTime created) {
    this.created = created;
  }

  @Override
  public void setDomain(final String domain) {
    // Use pattern matching with guarded patterns to validate domain
    this.domain = switch (domain) {
      case null -> throw new IllegalArgumentException("Domain cannot be null");
      case String s when s.isEmpty() -> throw new IllegalArgumentException("Domain cannot be empty");
      case String s -> s;
    };
  }

  @Override
  public void setPrincipals(final PrincipalCollection principals) {
    this.principals = checkNotNull(principals);
    // Use pattern matching to extract the primary principal with improved type safety
    Object primaryPrincipal = principals.getPrimaryPrincipal();
    this.username = switch (primaryPrincipal) {
      case String s -> s;
      case null -> throw new IllegalArgumentException("Primary principal cannot be null");
      case Object o when o.getClass().isRecord() -> extractUsernameFromRecord(o);
      case Object o -> o.toString();
    };
  }
  
  /**
   * Extracts a username from a record-type principal using record patterns
   * 
   * @param recordPrincipal the principal that is a record
   * @return the extracted username
   */
  private String extractUsernameFromRecord(Object recordPrincipal) {
    // Use reflection to check if the record has a 'username' or 'name' component
    try {
      var recordClass = recordPrincipal.getClass();
      // Try to find username or name component using pattern matching
      for (var component : recordClass.getRecordComponents()) {
        if ("username".equals(component.getName()) || "name".equals(component.getName())) {
          var accessor = component.getAccessor();
          var value = accessor.invoke(recordPrincipal);
          if (value instanceof String s) {
            return s;
          }
        }
      }
    } catch (Exception e) {
      // Fall back to toString if any reflection error occurs
    }
    return recordPrincipal.toString();
  }

  public void setSecret(final Secret secret) {
    // Use pattern matching to validate the secret before setting it
    this.secret = switch (secret) {
      case null -> throw new IllegalArgumentException("Secret cannot be null");
      case Secret s -> s;
    };
  }
}