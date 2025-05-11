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
 * Core UI support.
 * <p>
 * This package provides core UI support for the Nexus Repository Manager web interface.
 * It is compatible with Java 21 and leverages modern language features for improved
 * performance, readability, and maintainability:
 * <ul>
 *   <li>Virtual Threads - For improved concurrency in I/O-bound operations and UI request handling</li>
 *   <li>Pattern Matching - For more concise and type-safe code when handling different UI component types</li>
 *   <li>Record Patterns - For simplified data extraction and transformation of UI models and DTOs</li>
 *   <li>String Templates - For more readable logging and message formatting in UI components</li>
 *   <li>Sequenced Collections - For improved handling of ordered UI elements and data structures</li>
 * </ul>
 * </p>
 *
 * @since 3.0
 */
package org.sonatype.nexus.coreui;