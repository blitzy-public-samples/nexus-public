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

/**
 * Marker interface for tests that should be run with Java 21 Virtual Threads.
 * <p>
 * Tests annotated with {@code @Category(VirtualThreadTestGroup.class)} will be included
 * when the {@code virtual-threads} Maven profile is activated with {@code -Dvirtual-threads=true}.
 * <p>
 * These tests can be used to validate code that has been optimized for Virtual Threads
 * and to compare performance between platform threads and virtual threads.
 *
 * @since 3.60
 */
public interface VirtualThreadTestGroup
{
  // Marker interface
}