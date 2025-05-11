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
package org.sonatype.nexus.coreui;

/**
 * Ext TreeStore load parameters.
 * 
 * Refactored as a Java record for Java 21 compatibility, enabling use with Record Patterns.
 * Records provide immutable data carriers with built-in equals(), hashCode(), and toString() methods.
 *
 * @since 3.6
 */
public record TreeStoreLoadParameters(String node, String repositoryName, String filter) {
  // Record automatically provides accessors, constructor, equals(), hashCode(), and toString()
  // This enables pattern matching with Record Patterns in Java 21
}
