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

package org.sonatype.nexus.content.maven.store;

/**
 * Struct to track GAV we need to request metadata rebuild due to deletion.
 * 
 * Implemented as a Java Record for immutability and automatic generation of
 * equals, hashCode, and toString methods. This leverages Java 21 features
 * for more concise and maintainable code.
 *
 * @since 3.30
 * @see java.lang.Record
 */
public record GAV(String group, String name, String baseVersion, int count) {
  // Records automatically generate constructor, equals, hashCode, and toString methods
  // No additional implementation needed as the record provides all required functionality
}
