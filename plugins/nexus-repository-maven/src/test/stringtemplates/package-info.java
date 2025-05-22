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
 * Tests for validating Java 21's String Templates feature in the Maven repository plugin.
 * 
 * <p>
 * These tests validate that String Templates are correctly used for formatting various types of messages
 * in the Maven repository plugin, including log messages, user messages, and error messages.
 * </p>
 * 
 * <p>
 * All tests in this package are categorized with {@link org.sonatype.nexus.repository.maven.category.Java21TestGroup}
 * to ensure they are only executed when Java 21 preview features are enabled.
 * </p>
 * 
 * @since 3.60
 */
package org.sonatype.nexus.repository.maven.stringtemplates;