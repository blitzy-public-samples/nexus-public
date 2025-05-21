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

/**
 * Marker interface that defines a test category for tests that specifically validate
 * application behavior with Java 21 Virtual Threads.
 * <p>
 * Tests annotated with {@code @Category(VirtualThreadTestGroup.class)} will be executed
 * when the {@code virtual-threads} Maven profile is activated with {@code -Dvirtual-threads=true}.
 * <p>
 * This test group is focused on validating:
 * <ul>
 *   <li>Concurrency capabilities with high thread counts</li>
 *   <li>Thread pinning detection and mitigation</li>
 *   <li>Performance comparisons between platform and virtual threads</li>
 *   <li>I/O operations optimized for virtual threads</li>
 * </ul>
 * <p>
 * Example usage with JUnit 4:
 * <pre>
 * {@code
 * @Category(VirtualThreadTestGroup.class)
 * @Test
 * public void testConcurrentOperationsWithVirtualThreads() {
 *   // Test implementation using virtual threads
 * }
 * }
 * </pre>
 * <p>
 * Example usage with JUnit Jupiter (JUnit 5):
 * <pre>
 * {@code
 * @Tag("VirtualThreadTestGroup")
 * @Test
 * void testConcurrentOperationsWithVirtualThreads() {
 *   // Test implementation using virtual threads
 * }
 * }
 * </pre>
 */
public interface VirtualThreadTestGroup {
    // Marker interface - no methods required
}