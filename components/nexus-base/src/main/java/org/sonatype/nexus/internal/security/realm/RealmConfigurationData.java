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
package org.sonatype.nexus.internal.security.realm;

import java.util.ArrayList;
import java.util.List;

import org.sonatype.nexus.security.realm.RealmConfiguration;

/**
 * {@link RealmConfiguration} data.
 *
 * @since 3.21
 */
public class RealmConfigurationData
    implements RealmConfiguration, Cloneable
{
  private List<String> realmNames = new ArrayList<>();

  @Override
  public List<String> getRealmNames() {
    return realmNames;
  }

  @Override
  public void setRealmNames(final List<String> realmNames) {
    // Using Pattern Matching for null check
    if (realmNames instanceof List<String> list) {
      // Pattern variable 'list' is only in scope if realmNames is not null
      this.realmNames = list;
    } else {
      // This branch handles the null case
      this.realmNames = new ArrayList<>();
    }
  }

  @Override
  public RealmConfiguration copy() {
    try {
      return (RealmConfiguration) clone();
    }
    catch (CloneNotSupportedException e) {
      // Improved exception handling with more descriptive message
      throw new RuntimeException("Failed to clone RealmConfiguration: " + e.getMessage(), e);
    }
  }

  @Override
  public String toString() {
    // Using Java 21 String Templates for more efficient string concatenation
    return STR."\{getClass().getSimpleName()}\{realmNames=\{realmNames}\}";
  }
}