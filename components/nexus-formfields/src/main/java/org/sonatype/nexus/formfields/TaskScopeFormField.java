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
package org.sonatype.nexus.formfields;

/**
 * Task scope field with Java 21 compatibility.
 * 
 * @since 3.60
 */
public class TaskScopeFormField
    extends AbstractFormField<Void>
{
  /**
   * Creates a new task scope field with the specified ID.
   *
   * @param id The field identifier
   */
  public TaskScopeFormField(final String id) {
    super(id, "", "", false);
  }

  @Override
  public String getType() {
    return "taskScope";
  }
  
  /**
   * Returns a string representation of this task scope field.
   * 
   * @return a string representation of this task scope field
   * @since 3.60
   */
  @Override
  public String toString() {
    return STR."TaskScopeFormField{id=\{getId()}, type=\{getType()}}";
  }
}
