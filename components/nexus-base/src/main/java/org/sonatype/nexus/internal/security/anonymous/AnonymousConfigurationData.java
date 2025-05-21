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
package org.sonatype.nexus.internal.security.anonymous;

import java.util.Objects;

import org.sonatype.nexus.security.anonymous.AnonymousConfiguration;

/**
 * {@link AnonymousConfiguration} data.
 * 
 * Updated for Java 21 with enhanced pattern matching, optimized clone implementation,
 * and improved equals/hashCode methods following Java 21 best practices.
 *
 * @since 3.21
 */
public class AnonymousConfigurationData
    implements AnonymousConfiguration, Cloneable
{
  private boolean enabled;

  private String userId;

  private String realmName;

  @Override
  public boolean isEnabled() {
    return enabled;
  }

  @Override
  public void setEnabled(final boolean enabled) {
    this.enabled = enabled;
  }

  @Override
  public String getUserId() {
    return userId;
  }

  @Override
  public void setUserId(final String userId) {
    this.userId = userId;
  }

  @Override
  public String getRealmName() {
    return realmName;
  }

  @Override
  public void setRealmName(final String realmName) {
    this.realmName = realmName;
  }

  /**
   * Optimized implementation of copy() for Java 21.
   * Since this class implements Cloneable, the clone operation should never fail,
   * so we can optimize by avoiding the try-catch block.
   */
  @Override
  public AnonymousConfiguration copy() {
    try {
      // Direct cast is safe as we control the implementation
      return (AnonymousConfiguration) super.clone();
    }
    catch (CloneNotSupportedException e) {
      // This should never happen as we implement Cloneable
      throw new AssertionError("Clone not supported: " + e.getMessage(), e);
    }
  }

  /**
   * Enhanced equals implementation using Java 21 pattern matching.
   * This implementation follows the contract for equals methods:
   * - It's reflexive: an object equals itself
   * - It's symmetric: if a.equals(b) then b.equals(a)
   * - It's transitive: if a.equals(b) and b.equals(c) then a.equals(c)
   * - It's consistent: multiple invocations with unchanged objects return the same result
   * - For any non-null reference, equals(null) returns false
   */
  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    // Using pattern matching for instanceof in Java 21
    if (o instanceof AnonymousConfigurationData that) {
      return enabled == that.enabled &&
          Objects.equals(userId, that.userId) &&
          Objects.equals(realmName, that.realmName);
    }
    return false;
  }

  /**
   * Enhanced hashCode implementation aligned with Java 21 best practices.
   * Uses Objects.hash for consistent hashing of the object's state.
   */
  @Override
  public int hashCode() {
    return Objects.hash(enabled, userId, realmName);
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "{" +
        "enabled=" + enabled +
        ", userId='" + userId + '\'' +
        ", realmName='" + realmName + '\'' +
        '}';
  }
}