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
 * This interface enables selective execution of virtual thread tests via JUnit Categories
 * and Maven profiles. Tests focused on concurrency capabilities, thread pinning detection,
 * and performance comparisons between platform and virtual threads should be annotated with
 * {@code @Category(VirtualThreadTestGroup.class)}.
 * <p>
 * Example usage:
 * <pre>
 * {@code
 * @Category(VirtualThreadTestGroup.class)
 * public class MyVirtualThreadTest {
 *   @Test
 *   public void testVirtualThreadBehavior() {
 *     // Test code that validates Virtual Thread behavior
 *   }
 * }
 * }
 * </pre>
 * <p>
 * These tests can be selectively executed using the virtual-threads Maven profile:
 * <pre>
 * mvn test -Pvirtual-threads
 * </pre>
 *
 * @since 3.60
 */
public interface VirtualThreadTestGroup {
  // Marker interface - no methods
}