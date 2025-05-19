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
package org.sonatype.nexus.repository.content;

/**
 * A marker interface for SQL query parameters.
 * <p>
 * This interface serves as a type tag for classes that represent SQL query parameters,
 * allowing for type-safe parameter handling in repository content operations.
 * <p>
 * As a marker interface, it defines no methods but provides runtime type information
 * that can be used with pattern matching and type checking in Java 21.
 *
 * @since 3.0
 */
public interface SqlQueryParameters
{
}