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
package org.sonatype.nexus.testsuite.testsupport.raw;

/**
 * Marker interface to group Raw Integration Tests.
 * <p>
 * Tests implementing this marker interface are executed as part of the Raw format test suite.
 * When running on Java 21, these tests validate compatibility with Java 21 features including
 * Virtual Threads for I/O operations, pattern matching, and other language enhancements.
 * <p>
 * For Virtual Thread specific testing of Raw repositories, consider also implementing the
 * {@code VirtualThreadTestGroup} marker interface.
 *
 * @since 3.0
 */
public interface RawTestGroup
{
  /**
   * The name of this test group, used with JUnit 5 @Tag annotation.
   */
  String NAME = "raw-tests";
}