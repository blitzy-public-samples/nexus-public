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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Accumulates a renderable description of request-processing activity.
 * 
 * <p>This class is thread-safe and can be safely used in a Virtual Thread environment.
 * The internal list of description items uses a {@link CopyOnWriteArrayList} to ensure
 * thread safety when multiple threads (including Virtual Threads) access or modify the list
 * concurrently.</p>
 *
 * @since 3.0
 */
public class Description
{
  private final Map<String, Object> parameters;

  // Using CopyOnWriteArrayList for thread safety in Virtual Thread environments
  private final List<DescriptionItem> items = new CopyOnWriteArrayList<>();

  /**
   * Creates a new Description with the given parameters.
   * 
   * @param parameters the parameters for this description (should be immutable)
   */
  public Description(final Map<String, Object> parameters) {
    this.parameters = parameters;
  }

  /**
   * Adds a topic to this description.
   * 
   * <p>This method is thread-safe and can be called from multiple threads,
   * including Virtual Threads.</p>
   * 
   * @param name the name of the topic
   * @return this Description for method chaining
   */
  public Description topic(final String name) {
    // Using Java 21 String Template for more efficient string operations
    items.add(new DescriptionItem(name, "topic", STR."{name}"));
    return this;
  }

  /**
   * Adds a table to this description.
   * 
   * <p>This method is thread-safe and can be called from multiple threads,
   * including Virtual Threads.</p>
   * 
   * @param name the name of the table
   * @param values the values for the table
   * @return this Description for method chaining
   */
  public Description addTable(final String name, final Map<String, Object> values) {
    items.add(new DescriptionItem(name, "table", values));
    return this;
  }

  /**
   * Gets the parameters for this description.
   * 
   * @return the parameters (should be treated as immutable)
   */
  public Map<String, Object> getParameters() {
    return parameters;
  }

  /**
   * Gets the items in this description.
   * 
   * <p>The returned list is a thread-safe view of the items. Modifications to the
   * returned list will be reflected in this Description, but should be avoided.</p>
   * 
   * @return the items in this description
   */
  public List<DescriptionItem> getItems() {
    return items;
  }
}
