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
package org.sonatype.nexus.common.thread;

/**
 * Marker interface for tests that require Java 21 features.
 * <p>
 * This interface is used with JUnit 4's @Category annotation to mark tests that
 * specifically validate behavior when running with Java 21 features.
 * <p>
 * Example usage:
 * <pre>
 * {@code
 * @Test
 * @Category(Java21TestGroup.class)
 * public void testMethodWithJava21Features() {
 *   // Test code that requires Java 21 features
 * }
 * }
 * </pre>
 *
 * @since 3.60
 */
public interface Java21TestGroup {
}