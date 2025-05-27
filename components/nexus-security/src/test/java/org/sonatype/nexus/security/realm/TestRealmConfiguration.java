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
package org.sonatype.nexus.security.realm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

/**
 * Test implementation of {@link RealmConfiguration} with proper immutability and thread safety for Java 21.
 * 
 * @since 3.0
 */
public class TestRealmConfiguration
    implements RealmConfiguration
{
  private volatile List<String> realmNames;

  /**
   * Creates a new instance with no realm names.
   */
  public TestRealmConfiguration() {
    // Default constructor
  }

  /**
   * Creates a new instance with the specified realm names.
   *
   * @param realmNames the realm names to set, may be null
   */
  public TestRealmConfiguration(@Nullable final List<String> realmNames) {
    setRealmNames(realmNames);
  }

  @Override
  public List<String> getRealmNames() {
    if (realmNames == null) {
      return null;
    }
    // Return a defensive copy to ensure immutability
    return Collections.unmodifiableList(new ArrayList<>(realmNames));
  }

  @Override
  public void setRealmNames(@Nullable final List<String> realmNames) {
    if (realmNames == null) {
      this.realmNames = null;
    } else {
      // Create a defensive copy to ensure immutability
      this.realmNames = new ArrayList<>(realmNames);
    }
  }

  @Override
  public RealmConfiguration copy() {
    // Create a proper defensive copy of the configuration
    return new TestRealmConfiguration(realmNames);
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TestRealmConfiguration that = (TestRealmConfiguration) o;
    return Objects.equals(realmNames, that.realmNames);
  }

  @Override
  public int hashCode() {
    return Objects.hash(realmNames);
  }

  @Override
  public String toString() {
    return "TestRealmConfiguration{" +
        "realmNames=" + realmNames +
        '}';
  }
}