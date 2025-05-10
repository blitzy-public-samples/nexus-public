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

/**
 * A term which is expected to be matched precisely.
 */
public class ExactTerm
    extends TermSupport<String>
    implements StringTerm
{
  /**
   * Creates a new ExactTerm with the specified term string.
   * Uses pattern matching for null validation instead of Preconditions.checkNotNull.
   *
   * @param term the term string (must not be null)
   * @throws NullPointerException if term is null
   */
  public ExactTerm(final String term) {
    super(switch(term) {
      case null -> throw new NullPointerException("Term cannot be null");
      case String s -> s;
    });
  }
  
  /**
   * Optimized equality check using pattern matching for instanceof.
   */
  @Override
  public boolean equals(final Object obj) {
    if (this == obj) {
      return true;
    }
    return obj instanceof ExactTerm that && get().equals(that.get());
  }
}