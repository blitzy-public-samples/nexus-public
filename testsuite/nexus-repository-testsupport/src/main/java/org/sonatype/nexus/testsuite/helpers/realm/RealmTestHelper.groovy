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

/**
 * Helper interface for testing realm-related functionality.
 * <p>
 * Compatible with Java 21 runtime environment and testing frameworks:
 * - JUnit Jupiter 5.10.1
 * - Mockito 4.11.0
 * <p>
 * Implementations should ensure thread safety for compatibility with Virtual Threads
 * when used in concurrent test scenarios.
 *
 * @since 3.0
 */
interface RealmTestHelper
{
  /**
   * Retrieves the list of available realms for testing.
   * <p>
   * This method should be removed when the number of available Realms on the
   * Realm Configuration page are the same for both Orient and New DB.
   * <p>
   * Implementation note: When used in Java 21 Virtual Thread contexts, implementations
   * should avoid thread-pinning operations and ensure thread safety.
   *
   * @return List of realm names available for configuration
   */
  List<String> getAvailableRealms();
}