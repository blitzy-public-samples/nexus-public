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
package org.sonatype.nexus.security;

/**
 * Marker interface for categorizing tests that specifically verify Virtual Thread functionality
 * with security operations.
 * <p>
 * This interface allows Maven's Surefire and Failsafe plugins to include or exclude Virtual Thread tests
 * based on the @Category annotation and virtual-threads Maven profile. Tests that verify functionality
 * specific to Java 21 Virtual Threads should be annotated with @Category(VirtualThreadTestGroup.class).
 * <p>
 * Example usage:
 * <pre>
 * @Category(VirtualThreadTestGroup.class)
 * public class SecurityVirtualThreadTest {
 *   // Test methods that verify security operations with Virtual Threads
 * }
 * </pre>
 * <p>
 * This categorization ensures that Virtual Thread tests are isolated from standard tests to prevent
 * failures in environments where Virtual Threads are not supported or properly configured.
 *
 * @since 3.60
 */
public interface VirtualThreadTestGroup {
  // Marker interface - no methods required
}