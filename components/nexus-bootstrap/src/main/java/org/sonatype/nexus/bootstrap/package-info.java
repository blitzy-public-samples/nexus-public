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

/**
 * Application bootstrap.
 *
 * <p>Responsible for system initialization, environment preparation, JVM configuration and validation,
 * logging setup, and container startup and lifecycle management.</p>
 *
 * <p>This module has been updated to support Java 21 with the following enhancements:</p>
 * <ul>
 *   <li>JVM detection and validation for Java 21 compatibility</li>
 *   <li>Updated configuration for Java 21 runtime options</li>
 *   <li>Support for Java 21 features including Virtual Threads, Pattern Matching, and String Templates</li>
 *   <li>Optimized startup sequence leveraging Java 21 performance improvements</li>
 * </ul>
 *
 * @since 3.0
 * @since 3.60.0 Java 21 compatibility
 */
package org.sonatype.nexus.bootstrap;