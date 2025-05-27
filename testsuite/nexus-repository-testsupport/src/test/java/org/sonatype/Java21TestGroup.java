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
package org.sonatype;

/**
 * Marker interface used to categorize and selectively execute tests that specifically validate Java 21 features.
 * <p>
 * This interface enables the Maven build system to identify and run Java 21-specific tests through the
 * java21-tests profile, ensuring that tests requiring Java 21 features are only executed in compatible environments.
 * <p>
 * Usage example:
 * <pre>
 * {@code
 * @Category(Java21TestGroup.class)
 * public class VirtualThreadTest {
 *   @Test
 *   public void testVirtualThreadExecution() {
 *     // Test code that validates Java 21 Virtual Threads
 *   }
 * }
 * }
 * </pre>
 * <p>
 * These tests can be selectively executed using the Maven profile:
 * <pre>
 * mvn test -Djava21-tests=true
 * </pre>
 *
 * @since 3.60
 */
public interface Java21TestGroup {
  // Marker interface - intentionally empty
}