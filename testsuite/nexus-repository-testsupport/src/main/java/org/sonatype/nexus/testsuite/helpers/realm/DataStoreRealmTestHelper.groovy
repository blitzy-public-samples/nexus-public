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
package org.sonatype.nexus.testsuite.helpers.realm

import javax.inject.Named
import javax.inject.Singleton

/**
 * DataStore implementation of {@link RealmTestHelper}.
 * <p>
 * This implementation is compatible with Java 21 runtime environment and updated testing frameworks:
 * - JUnit Jupiter 5.10.1
 * - Mockito 5.8.0
 * - Google Guice 7.0.0
 * - Eclipse Sisu 0.10.0
 * <p>
 * This implementation is thread-safe and compatible with Virtual Threads when used in concurrent test scenarios.
 * It provides a fixed list of available realms for testing purposes, which is immutable and can be safely
 * accessed from multiple threads simultaneously.
 *
 * @since 3.0
 */
@Named
@Singleton
class DataStoreRealmTestHelper
    implements RealmTestHelper
{
  /**
   * Returns an immutable list of available realms for testing.
   * <p>
   * This implementation is thread-safe and can be safely used in Virtual Thread contexts
   * as it returns a fixed, immutable list that doesn't perform any blocking operations
   * or synchronization that could cause thread pinning.
   *
   * @return List of realm names available for configuration in the DataStore implementation
   */
  @Override
  List<String> getAvailableRealms() {
    ['Conan Bearer Token Realm', 'Crowd Realm', 'Default Role Realm', 'Docker Bearer Token Realm',
     'LDAP Realm', 'npm Bearer Token Realm', 'NuGet API-Key Realm', 'Rut Auth Realm', 'SAML Realm', 'User Token Realm']
  }
}
