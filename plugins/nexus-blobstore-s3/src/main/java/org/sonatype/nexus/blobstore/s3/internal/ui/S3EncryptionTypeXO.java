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
 * S3 Encryption exchange object implemented as an immutable Java 21 record.
 * <p>
 * This record provides accessor methods for each component and "with" methods
 * that return new instances with modified values to maintain compatibility
 * with the fluent API style of the original class.
 *
 * @since 3.19
 */
public record S3EncryptionTypeXO(int order, String id, String name) {
  /**
   * Returns a new instance with the specified order value.
   *
   * @param order the new order value
   * @return a new instance with the updated order
   */
  public S3EncryptionTypeXO withOrder(final int order) {
    return new S3EncryptionTypeXO(order, this.id, this.name);
  }

  /**
   * Returns a new instance with the specified id value.
   *
   * @param id the new id value
   * @return a new instance with the updated id
   */
  public S3EncryptionTypeXO withId(final String id) {
    return new S3EncryptionTypeXO(this.order, id, this.name);
  }

  /**
   * Returns a new instance with the specified name value.
   *
   * @param name the new name value
   * @return a new instance with the updated name
   */
  public S3EncryptionTypeXO withName(final String name) {
    return new S3EncryptionTypeXO(this.order, this.id, name);
  }
}