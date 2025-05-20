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
package org.sonatype.nexus.repository.rest.internal.api;

import org.sonatype.nexus.repository.Recipe;

/**
 * Data transfer object for repository recipe information.
 * Implemented as a Java Record for improved immutability and conciseness.
 */
public record RecipeXO(String format, String type) {
  
  /**
   * Creates a RecipeXO from a Recipe object.
   *
   * @param recipe the source Recipe object
   * @return a new RecipeXO instance
   */
  public static RecipeXO from(Recipe recipe) {
    return new RecipeXO(recipe.getFormat().getValue(), recipe.getType().getValue());
  }
  
  /**
   * Secondary constructor that creates a RecipeXO from a Recipe object.
   * Maintained for backward compatibility with existing code.
   *
   * @param recipe the source Recipe object
   */
  public RecipeXO(Recipe recipe) {
    this(recipe.getFormat().getValue(), recipe.getType().getValue());
  }
}