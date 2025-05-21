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
package org.sonatype.nexus.virtualthread;

/**
 * Marker interface for tests that specifically validate application behavior with Java 21 Virtual Threads.
 * <p>
 * Tests in this category focus on validating that components work correctly with Virtual Threads,
 * including proper handling of thread-local variables, avoiding thread pinning, and optimizing
 * I/O operations for Virtual Thread execution.
 * <p>
 * Use this category with JUnit's {@code @Category} annotation to mark tests that should be
 * included in Virtual Thread-specific test runs:
 * <pre>
 * {@code
 * @Category(VirtualThreadTestGroup.class)
 * public class MyVirtualThreadTest {
 *   // Test methods...
 * }
 * }
 * </pre>
 * <p>
 * These tests can be selectively executed using the {@code virtual-threads} Maven profile.
 *
 * @since 3.60
 */
public interface VirtualThreadTestGroup {
  // Marker interface - no methods required
}