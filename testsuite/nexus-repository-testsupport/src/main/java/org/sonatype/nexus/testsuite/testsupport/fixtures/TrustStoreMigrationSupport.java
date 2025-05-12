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
package org.sonatype.nexus.testsuite.testsupport.fixtures;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.migrationsupport.rules.EnableRuleMigrationSupport;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to enable support for JUnit 4 TrustStoreRule in JUnit 5 tests.
 * <p>
 * This is a convenience annotation that combines {@link EnableRuleMigrationSupport} with
 * specific support for TrustStoreRule.
 * <p>
 * Usage example:
 * <pre>
 * {@code
 * @TrustStoreMigrationSupport
 * class MyTest {
 *   @Rule
 *   public TrustStoreRule trustStoreRule = new TrustStoreRule(trustStoreProvider);
 *   
 *   @Test
 *   void testWithTrustStore() {
 *     // Test code
 *   }
 * }
 * }
 * </pre>
 * <p>
 * Note: This is provided for backward compatibility. For new tests, prefer using
 * {@link TrustStoreExtension} with {@link org.junit.jupiter.api.extension.RegisterExtension}.
 *
 * @since 3.60
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@EnableRuleMigrationSupport
public @interface TrustStoreMigrationSupport {
}