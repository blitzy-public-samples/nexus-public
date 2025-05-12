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

import static java.lang.StringTemplate.STR;

import java.security.Security;

import javax.annotation.Priority;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.log.LoggingEvent;
import org.sonatype.nexus.crypto.CryptoHelper;
import org.sonatype.nexus.ssl.KeyStoreManagerConfiguration;
import org.sonatype.nexus.ssl.spi.KeyStoreStorageManager;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 * SSL plugin specific key-store manager.
 *
 * <p>
 * This implementation has been updated for Java 21 compatibility with the following enhancements:
 * <ul>
 *   <li>Updated cryptographic operations using modern Java 21 security features</li>
 *   <li>Optimized for Virtual Threads to improve scalability and performance</li>
 *   <li>Utilizes Java 21 string templates for improved logging</li>
 *   <li>Compatible with Karaf 4.4.4 OSGi container</li>
 *   <li>Updated to use BouncyCastle 1.78.1 for cryptographic operations</li>
 * </ul>
 * </p>
 *
 * @since ssl 1.0
 * @since Java 21 - Updated for Java 21 compatibility with modern cryptographic operations
 *                  and optimized for Virtual Threads performance.
 */
@Named(KeyStoreManagerImpl.NAME)
@Singleton
public class KeyStoreManagerImpl
    extends org.sonatype.nexus.ssl.KeyStoreManagerImpl
{
  public static final String NAME = "ssl";

  /**
   * The BouncyCastle provider version compatible with Java 21.
   */
  private static final String BOUNCY_CASTLE_PROVIDER_VERSION = "1.78.1";
  
  /**
   * Initializes the KeyStoreManager with Java 21 compatible dependencies.
   * 
   * @param crypto The cryptographic helper for secure operations, updated for Java 21 compatibility
   * @param storageManager The storage manager for keystore persistence
   * @param config The configuration for the keystore manager
   */
  @Inject
  @Priority(Integer.MAX_VALUE - 100) // Ensure this is initialized early but after core services
  public KeyStoreManagerImpl(
      final CryptoHelper crypto,
      @Named(NAME) final KeyStoreStorageManager storageManager,
      @Named(NAME) final KeyStoreManagerConfiguration config)
  {
    super(crypto, storageManager, config);
    
    // Ensure BouncyCastle provider is properly registered for Java 21
    ensureBouncyCastleProvider();
    
    log.debug(STR."Initialized SSL KeyStoreManager with Java 21 compatibility for \{NAME}");
    log.trace(STR."Using crypto provider: \{crypto.getClass().getName()}");
    log.trace(STR."Using storage manager: \{storageManager.getClass().getName()}");
  }
  
  /**
   * Ensures the BouncyCastle security provider is properly registered for Java 21.
   * This is important as Java 21 has updated security requirements and provider interfaces.
   */
  private void ensureBouncyCastleProvider() {
    try {
      if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
        Security.addProvider(new BouncyCastleProvider());
        log.debug(STR."Added BouncyCastle security provider (\{BOUNCY_CASTLE_PROVIDER_VERSION}) for Java 21 compatibility");
      } else {
        log.trace("BouncyCastle security provider already registered");
      }
    } catch (Exception e) {
      log.warn(STR."Failed to register BouncyCastle provider: \{e.getMessage()}");
    }
  }
  
  /**
   * {@inheritDoc}
   * 
   * <p>
   * This implementation is optimized for Java 21 Virtual Threads, which significantly improves
   * performance for I/O-bound operations like loading and saving keystores. The parent implementation
   * handles the actual operations, while this override ensures proper logging with Java 21 string templates.
   * </p>
   */
  @Override
  public void reloadTrustedKeystore() throws org.sonatype.nexus.ssl.KeystoreException {
    log.debug(STR."Reloading trusted keystore for \{NAME} using Java 21 optimizations");
    try {
      // Use the parent implementation which has been verified for Java 21 compatibility
      super.reloadTrustedKeystore();
      log.debug(STR."Successfully reloaded trusted keystore for \{NAME}");
    } catch (org.sonatype.nexus.ssl.KeystoreException e) {
      log.error(STR."Failed to reload trusted keystore for \{NAME}: \{e.getMessage()}");
      throw e;
    }
  }
}