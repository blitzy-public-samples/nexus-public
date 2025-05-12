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
package org.sonatype.nexus.testsuite.testsupport.apt;

import org.junit.jupiter.api.Tag;

/**
 * Marker interface to group Apt Integration Tests for JUnit Jupiter 5.10.1 and Java 21.
 * <p>
 * This interface enables test discovery and execution in Java 21 environments using JUnit Jupiter's
 * test grouping capabilities. Tests implementing this interface will be grouped together for selective
 * execution and reporting.
 * <p>
 * Usage: Implement this interface in your Apt test classes to ensure they are properly discovered
 * and executed by the JUnit Jupiter test engine in Java 21 environments.
 */
@Tag("apt")
public interface AptTestGroup
{
}