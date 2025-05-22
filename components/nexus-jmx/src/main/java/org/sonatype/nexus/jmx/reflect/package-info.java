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
 * Annotation-based framework to markup components for exposing as JMX MBeans.
 * 
 * This package is fully compatible with Java 21 and leverages its features for improved performance
 * and reliability. JMX operations benefit from Virtual Threads for I/O-bound monitoring activities,
 * resulting in higher throughput and reduced resource consumption. The implementation also supports
 * Java 21's enhanced JMX monitoring capabilities and integrates with Java Flight Recorder for
 * advanced diagnostics.
 *
 * @since 3.0
 * @since 3.60 Java 21 compatibility and Virtual Threads support
 */
package org.sonatype.nexus.jmx.reflect;