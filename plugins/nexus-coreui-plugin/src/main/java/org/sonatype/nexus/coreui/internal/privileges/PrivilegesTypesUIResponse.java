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
package org.sonatype.nexus.coreui.internal.privileges;

import java.util.List;
import java.util.Map;

import org.sonatype.nexus.formfields.FormField;
import org.sonatype.nexus.security.privilege.PrivilegeDescriptor;

/**
 * Response object for privilege types UI data.
 * 
 * Converted to a Java record for Java 21 compatibility, providing immutability
 * and automatic implementations of equals(), hashCode(), and toString().
 */
public record PrivilegesTypesUIResponse(String id, String name, List<FormField> formFields)
{
  /**
   * Constructs a new instance from a privilege descriptor entry.
   *
   * @param entry the map entry containing the privilege descriptor
   */
  public PrivilegesTypesUIResponse(final Map.Entry<String, PrivilegeDescriptor> entry) {
    this(
        entry.getValue().getType(),
        entry.getValue().getName(),
        entry.getValue().getFormFields()
    );
  }
}
