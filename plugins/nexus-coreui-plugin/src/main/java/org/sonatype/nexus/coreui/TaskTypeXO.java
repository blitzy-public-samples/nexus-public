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
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * Task type exchange object.
 *
 * @since 3.0
 */
public record TaskTypeXO(
    @NotBlank String id,
    @NotBlank String name,
    @NotNull Boolean exposed,
    @NotNull Boolean concurrentRun,
    List<FormFieldXO> formFields
)
{
  /**
   * Returns the task type ID.
   * 
   * @return the task type ID
   * @deprecated Use {@link #id()} instead, which is automatically provided by the record
   */
  @Deprecated
  public String getId() {
    return id;
  }

  /**
   * Returns the task type name.
   * 
   * @return the task type name
   * @deprecated Use {@link #name()} instead, which is automatically provided by the record
   */
  @Deprecated
  public String getName() {
    return name;
  }

  /**
   * Returns whether the task type is exposed.
   * 
   * @return whether the task type is exposed
   * @deprecated Use {@link #exposed()} instead, which is automatically provided by the record
   */
  @Deprecated
  public Boolean getExposed() {
    return exposed;
  }

  /**
   * Returns whether the task type allows concurrent runs.
   * 
   * @return whether the task type allows concurrent runs
   * @deprecated Use {@link #concurrentRun()} instead, which is automatically provided by the record
   */
  @Deprecated
  public Boolean getConcurrentRun() {
    return concurrentRun;
  }

  /**
   * Returns the form fields for the task type.
   * 
   * @return the form fields for the task type
   * @deprecated Use {@link #formFields()} instead, which is automatically provided by the record
   */
  @Deprecated
  public List<FormFieldXO> getFormFields() {
    return formFields;
  }
}
