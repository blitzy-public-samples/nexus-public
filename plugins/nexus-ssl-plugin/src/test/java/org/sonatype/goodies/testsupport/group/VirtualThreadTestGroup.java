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
package org.sonatype.goodies.testsupport.group;

/**
 * Marker interface for tests that specifically validate Java 21 virtual thread functionality.
 * <p>
 * Tests marked with this category will be executed when the virtual-threads Maven profile
 * is activated, allowing selective execution of virtual thread-specific tests.
 * <p>
 * These tests validate features such as:
 * <ul>
 *   <li>Virtual thread creation and execution</li>
 *   <li>Thread pinning detection and mitigation</li>
 *   <li>Performance comparisons between platform and virtual threads</li>
 *   <li>JDBC driver compatibility with virtual threads</li>
 * </ul>
 *
 * @since 3.62
 */
public interface VirtualThreadTestGroup
{
  // Marker interface
}