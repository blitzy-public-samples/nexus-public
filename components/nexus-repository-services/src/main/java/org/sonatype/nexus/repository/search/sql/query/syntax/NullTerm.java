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
 * A term with a {@code null} value
 */
public class NullTerm
    extends TermSupport<String>
    implements StringTerm
{
  /**
   * Singleton holder class for thread-safe lazy initialization with
   * improved Java 21 memory model guarantees.
   */
  private static final class Holder {
    // The instance is created when the Holder class is loaded and initialized
    // This happens only when the getInstance method is called for the first time
    private static final NullTerm INSTANCE = new NullTerm();
  }

  /**
   * Returns the singleton instance of NullTerm.
   * Thread-safe with Java 21 memory model guarantees.
   *
   * @return the singleton instance
   */
  public static NullTerm getInstance() {
    return Holder.INSTANCE;
  }

  /**
   * Private constructor to prevent instantiation outside of this class.
   */
  private NullTerm() {
    super(null);
  }

  /**
   * Backward compatibility field for code that directly accesses the INSTANCE field.
   * New code should use getInstance() method instead.
   */
  public static final NullTerm INSTANCE = getInstance();
}