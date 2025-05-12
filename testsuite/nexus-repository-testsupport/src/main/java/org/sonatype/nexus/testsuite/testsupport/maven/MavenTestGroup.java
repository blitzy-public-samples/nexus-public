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
package org.sonatype.nexus.testsuite.testsupport.maven;

/**
 * Marker interface to group Maven Integration Tests.
 * 
 * <p>
 * Compatible with Java 21 runtime environment and testing frameworks:
 * <ul>
 *   <li>JUnit Jupiter 5.10.1</li>
 *   <li>Mockito 4.11.0</li>
 * </ul>
 * 
 * <p>
 * Tests using this marker can be executed in both platform thread and virtual thread modes
 * when the appropriate Maven profile is activated.
 */
public interface MavenTestGroup
{
}