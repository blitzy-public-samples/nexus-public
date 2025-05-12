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
 * Marker interface for tests that specifically validate Virtual Thread behavior.
 * <p>
 * Tests marked with this category will be executed when the virtual-threads Maven profile is activated.
 * These tests validate specific Virtual Thread features such as:
 * <ul>
 *   <li>Thread creation and scheduling</li>
 *   <li>Concurrency performance</li>
 *   <li>Thread pinning detection and mitigation</li>
 *   <li>I/O operation optimization</li>
 *   <li>Scalability with large numbers of concurrent operations</li>
 * </ul>
 * </p>
 *
 * @since 3.60
 */
public interface VirtualThreadTestGroup
{
  /**
   * The name of this test group, used with JUnit 5 @Tag annotation.
   */
  String NAME = "virtual-threads";
}