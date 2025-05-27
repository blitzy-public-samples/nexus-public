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
package org.sonatype.nexus.repository.content;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import javax.annotation.Nullable;

import org.sonatype.nexus.repository.content.fluent.FluentAttributes;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A set of attribute changes to be applied to a content entity.
 *
 * @since 3.29
 */
public class AttributeChangeSet
    implements FluentAttributes<AttributeChangeSet>
{
  // Using LinkedList which implements SequencedCollection in Java 21
  private final List<AttributeChange> changes = new LinkedList<>();

  /**
   * Create a change set with a single attribute change.
   */
  public AttributeChangeSet(final AttributeOperation operation, final String key, final Object value) {
    changes.add(new AttributeChange(operation, key, value));
  }

  /**
   * Create an empty change set.
   */
  public AttributeChangeSet() {
    // do nothing
  }

  @Override
  public AttributeChangeSet attributes(final AttributeOperation change, final String key, final Object value) {
    changes.add(new AttributeChange(change, key, value));

    return this;
  }

  /**
   * Get the list of attribute changes.
   */
  public List<AttributeChange> getChanges() {
    return Collections.unmodifiableList(changes);
  }

  /**
   * Process an attribute change using pattern matching.
   * 
   * @param change the attribute change to process
   * @return a description of the processed change
   */
  public String processChange(AttributeChange change) {
    return switch (change.getOperation()) {
      case SET -> STR."Setting attribute '\{change.getKey()}' to \{change.getValue()}";
      case REMOVE -> STR."Removing attribute '\{change.getKey()}'";
      case APPEND -> STR."Appending \{change.getValue()} to attribute list '\{change.getKey()}'";
      case PREPEND -> STR."Prepending \{change.getValue()} to attribute list '\{change.getKey()}'";
      case OVERLAY -> STR."Overlaying \{change.getValue()} onto attribute map '\{change.getKey()}'";
    };
  }

  /**
   * Represents a single attribute change operation.
   */
  public static class AttributeChange
  {
    private final AttributeOperation operation;

    private final String key;

    private final Object value;

    private AttributeChange(final AttributeOperation operation, final String key, @Nullable final Object value) {
      this.operation = checkNotNull(operation);
      this.key = checkNotNull(key);
      this.value = value;
    }

    public AttributeOperation getOperation() {
      return operation;
    }

    public String getKey() {
      return key;
    }

    @Nullable
    public Object getValue() {
      return value;
    }

    @Override
    public String toString() {
      // Using String Templates for improved logging
      return STR."AttributeChange{operation=\{operation}, key='\{key}', value=\{value}} ";
    }
  }
}