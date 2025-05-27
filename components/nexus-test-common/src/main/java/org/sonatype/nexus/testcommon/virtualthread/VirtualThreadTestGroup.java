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
package org.sonatype.nexus.testcommon.virtualthread;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Tag;

/**
 * Annotation for tests that should be executed in a Virtual Thread context.
 * <p>
 * This annotation serves as a marker for tests that specifically validate behavior
 * when running in Java 21 Virtual Threads. It can be used to categorize and selectively
 * run tests that verify Virtual Thread compatibility.
 * <p>
 * Example usage:
 * <pre>
 * {@code
 * @Test
 * @VirtualThreadTestGroup
 * void testMethodInVirtualThread() {
 *   // Test code that should run in a Virtual Thread
 * }
 * }
 * </pre>
 *
 * @since 3.60
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Tag("virtual-thread")
public @interface VirtualThreadTestGroup {
}