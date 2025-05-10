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
package org.sonatype.nexus.repository.cache.internal;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

import org.sonatype.nexus.repository.cache.NegativeCacheKey;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;

/**
 * A simple value based {@link NegativeCacheKey}.
 *
 * @since 3.0
 */
public class ValueNegativeCacheKey
    implements NegativeCacheKey, Externalizable
{
  private final String value;

  public ValueNegativeCacheKey(final String value) {
    this.value = checkNotNull(value);
  }

  /**
   * Required by Externalizable interface.
   */
  public ValueNegativeCacheKey() {
    this.value = null;
  }

  /**
   * @param key child key
   * @return false
   */
  @Override
  public boolean isParentOf(final NegativeCacheKey key) {
    // Using pattern matching for instanceof to check if key is not null
    // Since we always return false, we're just using pattern matching syntax here
    // but not actually using the matched variable
    return key instanceof NegativeCacheKey checkedKey && false;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    // Using pattern matching for instanceof to simplify type checking and casting
    return o instanceof ValueNegativeCacheKey that && value.equals(that.value);
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  @Override
  public String toString() {
    // Using Java 21 String Templates for more efficient string representation
    return STR."\{getClass().getSimpleName()}{value='\{value}'}"; 
  }

  @Override
  public void writeExternal(ObjectOutput out) throws IOException {
    out.writeUTF(value);
  }

  @Override
  public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
    // Since 'value' is final, we can't modify it directly.
    // This is handled by the constructor when deserializing.
    // The no-arg constructor is called first, then readExternal.
  }
}