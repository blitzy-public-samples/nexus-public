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
package org.sonatype.nexus.repository.apt.java21;

/**
 * Marker interface to categorize tests that specifically validate Java 21 features in the APT repository plugin.
 * Tests annotated with @Category(Java21TestGroup.class) will be executed only when the java21-tests Maven profile
 * is activated.
 * 
 * This enables selective testing of Java 21 features such as:
 * - Pattern Matching for switch
 * - Record Patterns
 * - String Templates
 */
public interface Java21TestGroup
{
}