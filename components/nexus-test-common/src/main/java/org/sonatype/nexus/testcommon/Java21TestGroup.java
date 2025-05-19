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
package org.sonatype.nexus.testcommon;

/**
 * Marker interface for tests that should be run with Java 21 features.
 * <p>
 * Tests annotated with {@code @Category(Java21TestGroup.class)} will be included
 * when the {@code java21-tests} Maven profile is activated.
 * <p>
 * These tests can be used to validate code that has been optimized for Java 21 features
 * such as Virtual Threads, Record Patterns, Pattern Matching for switch, and String Templates.
 *
 * @since 3.60
 */
public interface Java21TestGroup
{
  // Marker interface
}