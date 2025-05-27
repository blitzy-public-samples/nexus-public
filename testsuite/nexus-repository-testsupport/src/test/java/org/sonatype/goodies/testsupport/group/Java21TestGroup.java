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

import org.junit.experimental.categories.Category;
import org.sonatype.goodies.testsupport.group.TestGroup;

/**
 * Marker interface for tests that specifically validate Java 21 features.
 * 
 * <p>
 * This category enables the Maven build system to selectively execute tests that validate
 * Java 21-specific features using the java21-tests profile. Tests in this category will only
 * be executed when the java21-tests profile is activated, ensuring that tests requiring
 * Java 21 features are only run in compatible environments.
 * </p>
 * 
 * <p>
 * Usage example:
 * </p>
 * <pre>
 * {@code
 * @Category(Java21TestGroup.class)
 * public class RecordPatternMatchingTest {
 *   // Tests that validate Java 21 record pattern matching
 * }
 * }
 * </pre>
 * 
 * <p>
 * To run tests in this category, use the Maven java21-tests profile:
 * </p>
 * <pre>
 * {@code
 * mvn test -Djava21-tests=true
 * }
 * </pre>
 *
 * @since 3.60
 */
public interface Java21TestGroup extends TestGroup {
    // Marker interface - no methods required
}