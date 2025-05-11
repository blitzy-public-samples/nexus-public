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

import javax.validation.constraints.NotBlank;

/**
 * Component exchange object.
 * 
 * Refactored as a Java Record for Java 21 compatibility, enabling the use of Record Patterns
 * when this class is used elsewhere in the codebase. This implementation provides immutability
 * and automatically generates accessor methods, equals(), hashCode(), and toString().
 *
 * Note: Accessor methods in records don't use the 'get' prefix (e.g., id() instead of getId()).
 *
 * @since 3.0
 */
public record ComponentXO(
    @NotBlank String id,
    @NotBlank String repositoryName,
    @NotBlank String group,
    @NotBlank String name,
    @NotBlank String version,
    @NotBlank String format,
    @NotBlank String lastBlobUpdated
) {
    // Java Record automatically generates:
    // - Constructor
    // - Accessor methods (without 'get' prefix)
    // - equals() and hashCode() methods based on all components
    // - toString() method
}