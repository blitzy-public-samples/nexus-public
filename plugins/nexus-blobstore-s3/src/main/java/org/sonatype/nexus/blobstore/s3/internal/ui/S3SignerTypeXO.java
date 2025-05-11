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
package org.sonatype.nexus.blobstore.s3.internal.ui;

/**
 * S3 signer type exchange object implemented as an immutable Java 21 record.
 * 
 * Records provide automatic implementations of accessors, equals(), hashCode(),
 * and toString() methods, making this class more concise and less error-prone.
 * 
 * This implementation maintains backward compatibility with existing code by providing
 * a no-args constructor and fluent 'withX' methods that return new instances with updated values.
 *
 * @since 3.12
 */
public record S3SignerTypeXO(int order, String id, String name)
{
  /**
   * Creates a new instance with default values (order=0, id=null, name=null).
   * 
   * This constructor is provided for backward compatibility with code that expects
   * to be able to create an instance with a no-args constructor and then set values
   * using the fluent 'withX' methods.
   */
  public S3SignerTypeXO() {
    this(0, null, null);
  }
  
  /**
   * Returns a new instance with the specified order value.
   *
   * @param order the order value to set
   * @return a new S3SignerTypeXO instance with the updated order value
   */
  public S3SignerTypeXO withOrder(final int order) {
    return new S3SignerTypeXO(order, this.id, this.name);
  }

  /**
   * Returns a new instance with the specified id value.
   *
   * @param id the id value to set
   * @return a new S3SignerTypeXO instance with the updated id value
   */
  public S3SignerTypeXO withId(final String id) {
    return new S3SignerTypeXO(this.order, id, this.name);
  }

  /**
   * Returns a new instance with the specified name value.
   *
   * @param name the name value to set
   * @return a new S3SignerTypeXO instance with the updated name value
   */
  public S3SignerTypeXO withName(final String name) {
    return new S3SignerTypeXO(this.order, this.id, name);
  }
}
