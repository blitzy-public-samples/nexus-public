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
import java.util.SequencedCollection;

import javax.annotation.Nullable;

/**
 * Test implementation of {@link RealmConfiguration} that is thread-safe and immutable.
 * 
 * @since 3.0
 */
public class TestRealmConfiguration
    implements RealmConfiguration
{
  private final SequencedCollection<String> realmNames;

  /**
   * Creates a new instance with no realm names.
   */
  public TestRealmConfiguration() {
    this.realmNames = Collections.unmodifiableSequencedCollection(new ArrayList<>());
  }

  /**
   * Creates a new instance with the given realm names.
   *
   * @param realmNames the realm names to use, or null for an empty list
   */
  public TestRealmConfiguration(@Nullable final List<String> realmNames) {
    if (realmNames == null) {
      this.realmNames = Collections.unmodifiableSequencedCollection(new ArrayList<>());
    } else {
      this.realmNames = Collections.unmodifiableSequencedCollection(new ArrayList<>(realmNames));
    }
  }

  @Override
  public List<String> getRealmNames() {
    return Collections.unmodifiableList(new ArrayList<>(realmNames));
  }

  @Override
  public void setRealmNames(@Nullable final List<String> realmNames) {
    throw new UnsupportedOperationException("TestRealmConfiguration is immutable");
  }

  @Override
  public RealmConfiguration copy() {
    return new TestRealmConfiguration(new ArrayList<>(realmNames));
  }
}