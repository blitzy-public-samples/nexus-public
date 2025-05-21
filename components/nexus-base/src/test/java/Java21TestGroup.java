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
 * Marker interface that defines a test category for tests that explicitly validate Java 21 features.
 * 
 * <p>This interface enables selective execution of Java 21-specific tests via JUnit Categories
 * and Maven profiles. Tests that specifically validate Pattern Matching, Record Patterns,
 * String Templates, and other Java 21 language features should be annotated with
 * {@code @Category(Java21TestGroup.class)}.</p>
 *
 * <p>To run only Java 21 feature tests, use the {@code java21-tests} Maven profile:</p>
 * <pre>mvn test -Djava21-tests=true</pre>
 *
 * <p>This category is particularly useful for:</p>
 * <ul>
 *   <li>Tests that validate Pattern Matching for switch expressions</li>
 *   <li>Tests that validate Record Patterns</li>
 *   <li>Tests that validate String Templates</li>
 *   <li>Tests that validate Sequenced Collections compatibility</li>
 *   <li>Other tests that specifically verify Java 21 language features</li>
 * </ul>
 *
 * @since 3.60
 */
public interface Java21TestGroup {
    // Marker interface - no methods required
}