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
package org.sonatype.nexus.repository.search.sql.query.syntax;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.SequencedCollection;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import static java.lang.StringTemplate.STR;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A collection of {@link StringTerm} which is to the right of the operand in an {@link SqlPredicate}.
 */
public class TermCollection
    implements Term
{
  private final Collection<StringTerm> terms;

  /*
   * To keep things simple we only accept StringTerm to avoid nested TermCollections
   */
  private TermCollection(final Collection<StringTerm> terms) {
    this.terms = ImmutableList.copyOf(checkNotNull(terms));
  }

  public Collection<StringTerm> get() {
    // Using Pattern Matching for instanceof to check if the collection is a SequencedCollection
    // This allows for more elegant decision logic and direct access to the sequenced collection
    if (terms instanceof SequencedCollection<StringTerm> sequenced) {
      // For SequencedCollection, we can use its specific features if needed in the future
      return Collections.unmodifiableCollection(sequenced);
    }
    // Fallback to standard unmodifiable collection
    return Collections.unmodifiableCollection(terms);
  }

  @Override
  public int hashCode() {
    return Objects.hash(terms);
  }

  @Override
  public boolean equals(final Object obj) {
    if (this == obj)
      return true;
    if (obj == null)
      return false;
    if (getClass() != obj.getClass())
      return false;
    TermCollection other = (TermCollection) obj;
    return Objects.equals(terms, other.terms);
  }

  @Override
  public String toString() {
    return STR."TermCollection [terms=\{terms}]";
  }

  /**
   * Helper method to create a Term with the provided collection. If the provided collection has only one element that
   * element will be returned instead of creating an instance.
   */
  public static Term create(final Collection<StringTerm> terms) {
    // We use a private constructor to avoid creating
    return switch (terms.size()) {
      case 1 -> Iterables.getOnlyElement(terms);
      default -> new TermCollection(terms);
    };
  }

  /**
   * Helper method to create a Term with the provided collection. If the provided collection has only one element that
   * element will be returned instead of creating an instance.
   */
  public static Term create(final StringTerm... terms) {
    // We use a private constructor to avoid creating
    return switch (terms.length) {
      case 1 -> terms[0];
      default -> new TermCollection(Arrays.asList(terms));
    };
  }
}