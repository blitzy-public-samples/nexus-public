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
package org.sonatype.nexus.repository.httpbridge.internal.describe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SequencedCollection;

/**
 * Accumulates a renderable description of request-processing activity.
 * 
 * This class uses Java 21 features like Sequenced Collections for improved
 * collection management and works with Record Patterns through DescriptionItem records.
 *
 * @since 3.0
 */
public class Description
{
  private final Map<String, Object> parameters;

  // Using ArrayList which implements SequencedCollection in Java 21
  private final List<DescriptionItem> items = new ArrayList<>();

  /**
   * Creates a new Description with the given parameters.
   *
   * @param parameters the parameters for this description
   */
  public Description(final Map<String, Object> parameters) {
    this.parameters = parameters;
  }

  /**
   * Adds a topic item to the description.
   *
   * @param name the name of the topic
   * @return this Description instance for method chaining
   */
  public Description topic(final String name) {
    items.add(new DescriptionItem(name, "topic", name));
    return this;
  }

  /**
   * Adds a table item to the description.
   *
   * @param name the name of the table
   * @param values the values to include in the table
   * @return this Description instance for method chaining
   */
  public Description addTable(final String name, final Map<String, Object> values) {
    items.add(new DescriptionItem(name, "table", values));
    return this;
  }

  /**
   * Gets the parameters for this description.
   *
   * @return the parameters map
   */
  public Map<String, Object> getParameters() {
    return parameters;
  }

  /**
   * Gets the items in this description.
   * The returned list maintains insertion order and can be used with Java 21 Sequenced Collection features.
   *
   * @return the list of description items
   */
  public List<DescriptionItem> getItems() {
    return items;
  }
}