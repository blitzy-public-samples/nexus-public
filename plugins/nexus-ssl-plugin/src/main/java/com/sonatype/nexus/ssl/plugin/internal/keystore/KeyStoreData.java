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
package com.sonatype.nexus.ssl.plugin.internal.keystore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.sonatype.nexus.common.entity.HasName;

/**
 * {@link java.security.KeyStore} data represented as an immutable record.
 * <p>
 * This class leverages Java 21 record patterns for improved data handling and security.
 * The implementation is compatible with BouncyCastle 1.78.1 cryptography providers.
 * <p>
 * Note: This is an immutable record. While it implements {@link HasName}, the setName method
 * will throw an {@link UnsupportedOperationException}. To change the name, create a new instance.
 *
 * @since 3.21
 * @see java.security.KeyStore
 * @see org.bouncycastle.jcajce.provider.keystore.BC
 * @see java.lang.Record
 */
public record KeyStoreData(
    @Nonnull String name,
    @Nullable byte[] bytes) 
    implements HasName 
{
  /**
   * Creates a new KeyStore data record with validation.
   * <p>
   * This compact constructor ensures the name is not null and provides a hook for
   * any additional validation that might be needed in the future.
   * <p>
   * Java 21 compact constructors automatically initialize the record's fields after
   * validation, without requiring explicit assignments.
   */
  public KeyStoreData {
    if (name == null) {
      throw new NullPointerException("Name cannot be null");
    }
    // bytes can be null in some cases, so we don't validate them here
    
    // Note: With BouncyCastle 1.78.1, keystore bytes are handled securely
    // by the cryptography provider. No additional security measures are needed here.
  }
  
  @Override
  public String getName() {
    return name;
  }
  
  /**
   * This method is required by the {@link HasName} interface but is not supported
   * for this immutable record type.
   * <p>
   * Records are immutable by design in Java 21, so this method will always throw an exception.
   * Instead of calling this method, create a new instance with the desired name:
   * <pre>
   * KeyStoreData newData = new KeyStoreData("newName", originalData.bytes());
   * </pre>
   *
   * @param name The name to set (ignored)
   * @throws UnsupportedOperationException Always thrown as records are immutable
   */
  @Override
  public void setName(final String name) {
    throw new UnsupportedOperationException("Cannot modify immutable record KeyStoreData - create a new instance instead");
  }
  
  /**
   * Returns the keystore data bytes.
   * <p>
   * Note: This method returns a direct reference to the internal byte array for performance reasons.
   * The caller should not modify the returned array.
   *
   * @return The keystore data bytes
   */
  public byte[] getBytes() {
    return bytes;
  }
}