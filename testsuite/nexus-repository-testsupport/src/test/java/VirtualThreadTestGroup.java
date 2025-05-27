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
 * JUnit category marker interface for tests that specifically validate Virtual Thread functionality
 * introduced in Java 21.
 * <p>
 * This category enables the Maven build system to selectively execute tests that validate
 * Virtual Thread behavior using the virtual-threads profile. Tests that require Virtual Thread
 * capabilities should be annotated with {@code @Category(VirtualThreadTestGroup.class)} to ensure
 * they are only executed in compatible environments.
 * <p>
 * Example usage:
 * <pre>
 * {@code
 * @Category(VirtualThreadTestGroup.class)
 * @Test
 * void testConcurrentOperationsWithVirtualThreads() {
 *   ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
 *   ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
 *   
 *   // Test implementation using virtual threads
 * }
 * }
 * </pre>
 * <p>
 * Tests can also be categorized with multiple groups if needed:
 * <pre>
 * {@code
 * @Category({Unstable.class, VirtualThreadTestGroup.class})
 * @Test
 * void testConcurrentVirtualThreadOperations() {
 *   // Test that may exhibit non-deterministic behavior when using virtual threads
 * }
 * }
 * </pre>
 *
 * @since 3.60
 */
public interface VirtualThreadTestGroup {
    // Marker interface - no methods required
}