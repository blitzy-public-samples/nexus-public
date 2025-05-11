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

import java.util.List;
import javax.validation.constraints.NotEmpty;

/**
 * Privilege Type exchange object.
 *
 * @since 3.0
 */
public record PrivilegeTypeXO(
    @NotEmpty String id,
    @NotEmpty String name,
    List<FormFieldXO> formFields
) {
  /**
   * Creates a new instance with validation annotations applied at the record component level.
   * 
   * @param id The privilege type identifier
   * @param name The privilege type name
   * @param formFields The list of form fields associated with this privilege type
   */
  public PrivilegeTypeXO {
    // Records perform implicit null checks, but we can add additional validation if needed
  }
  
  /**
   * Factory method to create a PrivilegeTypeXO from individual components.
   * This provides backward compatibility with code that used the setter pattern.
   *
   * @param id The privilege type identifier
   * @param name The privilege type name
   * @param formFields The list of form fields
   * @return A new PrivilegeTypeXO instance
   */
  public static PrivilegeTypeXO create(String id, String name, List<FormFieldXO> formFields) {
    return new PrivilegeTypeXO(id, name, formFields);
  }
}