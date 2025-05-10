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
 * Marker interface for tests that should be run with virtual threads.
 * <p>
 * Tests marked with this category will be included in virtual thread test suites
 * and may be executed using Java 21 virtual threads to validate thread-related behavior.
 * <p>
 * This category is particularly useful for tests that validate code paths that utilize
 * Java 21's virtual thread capabilities or need to verify correct behavior when running
 * on virtual threads.
 *
 * @since 3.60
 */
public interface VirtualThreadTestGroup
{
  // Marker interface - no methods
}