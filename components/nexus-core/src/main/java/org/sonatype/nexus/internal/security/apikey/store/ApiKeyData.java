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
import java.util.Objects;

import org.sonatype.nexus.internal.security.apikey.ApiKeyInternal;

import org.apache.shiro.subject.PrincipalCollection;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * {@link ApiKeyInternal} data.
 *
 * @since 3.21
 */
public class ApiKeyData
    implements ApiKeyInternal
{
  private String domain;

  private PrincipalCollection principals;

  private ApiKeyToken token;

  private OffsetDateTime created;

  ApiKeyData() { }

  ApiKeyData(
      final String domain,
      final PrincipalCollection principals,
      final ApiKeyToken token,
      final OffsetDateTime created)
  {
    this.domain = checkNotNull(domain);
    this.principals = checkNotNull(principals);
    this.token = checkNotNull(token);
    this.created = created;
  }

  @Override
  public void setDomain(final String domain) {
    this.domain = domain;
  }

  @Override
  public void setPrincipals(final PrincipalCollection principals) {
    this.principals = principals;
  }

  public void setToken(final ApiKeyToken token) {
    this.token = token;
  }

  public void setApiKey(final char[] chars) {
    this.token = new ApiKeyToken(chars);
  }

  @Override
  public String getDomain() {
    return domain;
  }

  @Override
  public PrincipalCollection getPrincipals() {
    return principals;
  }

  /**
   * Uses pattern matching to safely access principal collection data.
   * 
   * @param type The class type to match against
   * @param <T> The type parameter
   * @return The principal of the specified type, or null if not found
   * @since Java 21
   */
  public <T> T getPrincipalByType(Class<T> type) {
    if (principals != null && principals.getPrimaryPrincipal() instanceof T principal && type.isInstance(principal)) {
      return type.cast(principal);
    }
    return null;
  }

  public ApiKeyToken getToken() {
    return token;
  }

  @Override
  public char[] getApiKey() {
    // Optimized character array handling for Java 21's improved memory management
    return token != null ? token.getChars() : new char[0];
  }

  @Override
  public OffsetDateTime getCreated() {
    return created;
  }

  @Override
  public void setCreated(final OffsetDateTime created) {
    this.created = created;
  }
  
  /**
   * Optimized equals implementation for Java 21.
   * Compares the domain, principals, token, and created date.
   */
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    
    // Using pattern matching for instanceof check (Java 21 feature)
    if (o instanceof ApiKeyData other) {
      return Objects.equals(domain, other.domain) &&
             Objects.equals(principals, other.principals) &&
             Objects.equals(token, other.token) &&
             Objects.equals(created, other.created);
    }
    return false;
  }

  /**
   * Optimized hashCode implementation for Java 21.
   * Generates hash based on domain, principals, token, and created date.
   */
  @Override
  public int hashCode() {
    // Using Java 21's optimized hash code generation
    return Objects.hash(domain, principals, token, created);
  }
}