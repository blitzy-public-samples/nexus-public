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
package org.sonatype.nexus.java21;

/**
 * Marker interface for tests that validate Java 21 compatibility.
 * 
 * <p>
 * This category is used to identify tests that specifically validate features
 * introduced or enhanced in Java 21, such as:
 * <ul>
 *   <li>Virtual Threads</li>
 *   <li>Record Patterns</li>
 *   <li>Pattern Matching for switch</li>
 *   <li>String Templates</li>
 * </ul>
 * 
 * <p>
 * Tests annotated with {@code @Category(Java21TestGroup.class)} are guaranteed to be
 * executed in a Java 21 environment and can safely use Java 21 language features.
 */
public interface Java21TestGroup {
  // Marker interface - no methods required
}