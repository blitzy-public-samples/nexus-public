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
 * A term which allows tokenization while matching. This allows {@code foo.bar} to potentially match {@code foo-bar}
 * depending on the underlying search implementation. Unlike {@link WildcardTerm} this is not a prefix search so
 * {@code term=foo} will not match {@code foobar}
 */
public class LenientTerm
    extends TermSupport<String>
    implements StringTerm
{
  /**
   * Creates a new LenientTerm with the specified term string.
   * Uses pattern matching for null validation.
   *
   * @param term the term string (must not be null)
   * @throws NullPointerException if term is null
   */
  public LenientTerm(final String term) {
    super(switch (term) {
      case null -> throw new NullPointerException("Term cannot be null");
      case String validTerm -> validTerm;
    });
  }
  
  /**
   * Checks if the provided object is a StringTerm and returns it as such.
   * Demonstrates Java 21 Pattern Matching for instanceof.
   *
   * @param obj the object to check
   * @return the object as a StringTerm if it is one, otherwise null
   */
  public static StringTerm asStringTerm(Object obj) {
    return obj instanceof StringTerm stringTerm ? stringTerm : null;
  }

  /**
   * Extracts the term value from a Term object if it's a StringTerm.
   * Demonstrates Java 21 Pattern Matching for instanceof with nested patterns.
   *
   * @param term the term to extract from
   * @return the string value if it's a StringTerm, otherwise null
   */
  public static String extractTermValue(Term<?> term) {
    return switch (term) {
      case null -> null;
      case StringTerm stringTerm -> stringTerm.getValue();
      default -> null;
    };
  }
}