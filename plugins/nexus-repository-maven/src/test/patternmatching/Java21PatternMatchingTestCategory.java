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

import org.sonatype.nexus.common.testgroup.Java21TestGroup;

/**
 * JUnit category marker interface for tests that validate Java 21's Pattern Matching for switch features
 * in the Maven repository plugin.
 * <p>
 * Tests annotated with {@code @Category(Java21PatternMatchingTestCategory.class)} will only be executed
 * when the {@code java21-tests} Maven profile is activated, allowing selective execution in CI/CD pipelines.
 * <p>
 * This category is specifically for tests that verify the correct functioning of Pattern Matching for switch,
 * which is a key Java 21 feature being implemented in the Maven repository plugin. Pattern Matching for switch
 * enables more concise and type-safe code when working with complex object hierarchies and polymorphic types.
 *
 * @since 3.60
 */
public interface Java21PatternMatchingTestCategory
    extends Java21TestGroup
{
  // Marker interface - no methods required
}