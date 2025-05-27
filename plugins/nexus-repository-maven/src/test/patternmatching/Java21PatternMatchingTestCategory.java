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
package org.sonatype.nexus.repository.maven.patternmatching;

import org.sonatype.goodies.testsupport.group.Java21TestGroup;

/**
 * JUnit category marker interface for tests that specifically validate Java 21's Pattern Matching for switch features
 * in the Maven repository plugin.
 * <p>
 * Tests annotated with this category will only be executed when the {@code java21-tests} Maven profile is activated,
 * allowing selective execution in CI/CD pipelines.
 * <p>
 * Pattern Matching for switch is a key Java 21 feature that enables more expressive and concise code by allowing
 * switch expressions and statements to test whether a selector expression matches a pattern, rather than just testing
 * for equality against constants.
 * <p>
 * Example usage:
 * <pre>
 * {@code
 * @Category(Java21PatternMatchingTestCategory.class)
 * public class MyPatternMatchingTest {
 *   @Test
 *   public void testPatternMatchingForSwitch() {
 *     // Test code using Java 21 Pattern Matching for switch
 *   }
 * }
 * }
 * </pre>
 *
 * @since 3.60
 */
public interface Java21PatternMatchingTestCategory
    extends Java21TestGroup
{
  // Marker interface
}