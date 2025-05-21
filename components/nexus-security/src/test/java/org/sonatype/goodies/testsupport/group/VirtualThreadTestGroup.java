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
 * Marker interface for tests that specifically validate Virtual Thread functionality.
 * 
 * Tests marked with this category will only be executed when the virtual-threads profile is activated.
 * These tests validate functionality that depends on Java 21 Virtual Threads, including:
 * - Thread pinning detection and prevention
 * - Concurrent operations using Virtual Threads
 * - Performance comparisons between platform and virtual threads
 * - I/O operations with Virtual Threads
 */
public interface VirtualThreadTestGroup
{
  // Marker interface
}