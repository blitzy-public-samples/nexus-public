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
package org.sonatype.goodies.testsupport.group;

/**
 * Marker interface for tests that specifically validate Java 21 Virtual Thread functionality.
 * 
 * <p>
 * Tests marked with this category will be executed when the 'virtual-threads' Maven profile is activated
 * with -Dvirtual-threads=true. These tests validate that code paths utilizing Java 21's virtual thread
 * capabilities function correctly.
 * </p>
 * 
 * <p>
 * This category is used to identify tests that:
 * <ul>
 *   <li>Verify operations work correctly when executed in virtual threads</li>
 *   <li>Validate that thread pinning issues are avoided</li>
 *   <li>Test concurrent operations using virtual threads</li>
 *   <li>Compare performance characteristics between platform and virtual threads</li>
 * </ul>
 * </p>
 */
public interface VirtualThreadTestGroup
{
  // marker interface
}