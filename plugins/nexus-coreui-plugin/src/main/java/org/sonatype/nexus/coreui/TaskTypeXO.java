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
) {
  /**
   * Returns the id of this task type.
   * 
   * @return the id
   * @deprecated Use {@link #id()} instead, as provided by the record pattern.
   */
  @Deprecated
  public String getId() {
    return id;
  }

  /**
   * Returns the name of this task type.
   * 
   * @return the name
   * @deprecated Use {@link #name()} instead, as provided by the record pattern.
   */
  @Deprecated
  public String getName() {
    return name;
  }

  /**
   * Returns whether this task type is exposed.
   * 
   * @return whether exposed
   * @deprecated Use {@link #exposed()} instead, as provided by the record pattern.
   */
  @Deprecated
  public Boolean getExposed() {
    return exposed;
  }

  /**
   * Returns whether this task type supports concurrent runs.
   * 
   * @return whether concurrent run is supported
   * @deprecated Use {@link #concurrentRun()} instead, as provided by the record pattern.
   */
  @Deprecated
  public Boolean getConcurrentRun() {
    return concurrentRun;
  }

  /**
   * Returns the form fields for this task type.
   * 
   * @return the form fields
   * @deprecated Use {@link #formFields()} instead, as provided by the record pattern.
   */
  @Deprecated
  public List<FormFieldXO> getFormFields() {
    return formFields;
  }
}
