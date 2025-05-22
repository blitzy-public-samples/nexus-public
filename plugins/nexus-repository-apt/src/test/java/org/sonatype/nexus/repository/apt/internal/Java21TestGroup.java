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
package org.sonatype.nexus.repository.apt.internal;

/**
 * Marker interface to categorize tests that specifically validate Java 21 features.
 * 
 * This interface allows the test framework to selectively execute Java 21-specific tests
 * using JUnit's @Category annotation. It supports test categorization for features such as:
 * - Pattern Matching for switch
 * - Record Patterns
 * - Virtual Threads
 * - String Templates
 * 
 * Tests marked with this category will be included or excluded based on Maven profiles
 * configured for Java 21 compatibility testing.
 */
public interface Java21TestGroup
{
}