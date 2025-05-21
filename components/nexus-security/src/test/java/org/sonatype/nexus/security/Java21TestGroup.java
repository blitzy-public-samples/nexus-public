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
 * Marker interface for tests that specifically validate Java 21 features.
 * <p>
 * This interface enables Maven's Surefire and Failsafe plugins to include or exclude
 * Java 21-specific tests using the {@code @Category} annotation and corresponding Maven profiles.
 * <p>
 * Usage:
 * <pre>
 * // Mark a test class or method as Java 21-specific
 * {@literal @}Category(Java21TestGroup.class)
 * {@literal @}Test
 * public void testJava21Feature() {
 *   // Test code that uses Java 21 features
 * }
 * </pre>
 * <p>
 * In Maven, these tests can be selectively executed using the {@code java21-tests} profile:
 * <pre>
 * mvn test -Djava21-tests=true
 * </pre>
 * <p>
 * This categorization ensures that tests validating Java 21-specific features like:
 * <ul>
 *   <li>Virtual Threads</li>
 *   <li>Pattern Matching for switch</li>
 *   <li>Record Patterns</li>
 *   <li>String Templates</li>
 *   <li>Sequenced Collections</li>
 * </ul>
 * can be isolated and executed only in environments that support these features.
 *
 * @since 3.60
 */
public interface Java21TestGroup {
    // Marker interface - no methods required
}