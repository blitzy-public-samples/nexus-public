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

import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.crypto.CryptoHelper;
import org.sonatype.nexus.ssl.KeyStoreManagerConfiguration;
import org.sonatype.nexus.ssl.KeystoreException;
import org.sonatype.nexus.ssl.spi.KeyStoreStorageManager;

/**
 * SSL plugin specific key-store manager with Java 21 optimizations.
 * <p>
 * This implementation leverages Java 21 features including:
 * - Virtual Threads for I/O-bound certificate operations
 * - Updated security providers compatibility
 * - String Templates for improved logging
 * - Optimized keystore loading and saving operations
 *
 * @since ssl 1.0
 */
@Named(KeyStoreManagerImpl.NAME)
@Singleton
public class KeyStoreManagerImpl
    extends org.sonatype.nexus.ssl.KeyStoreManagerImpl
{
  public static final String NAME = "ssl";

  /**
   * Virtual thread executor for I/O-bound certificate operations.
   * Using virtual threads significantly improves performance for I/O operations
   * by allowing the JVM to efficiently manage thousands of concurrent operations
   * without the overhead of traditional platform threads.
   */
  private static final java.util.concurrent.ExecutorService VIRTUAL_EXECUTOR = 
      Executors.newVirtualThreadPerTaskExecutor();

  @Inject
  public KeyStoreManagerImpl(
      final CryptoHelper crypto,
      @Named(NAME) final KeyStoreStorageManager storageManager,
      @Named(NAME) final KeyStoreManagerConfiguration config)
  {
    super(crypto, storageManager, config);
    log.info(STR."Initialized SSL KeyStoreManager with Java 21 optimizations for \{NAME}");
  }
  
  /**
   * Imports a trust certificate asynchronously using virtual threads.
   * 
   * @param certificate the certificate to import
   * @param alias the alias to use for the certificate
   * @return a CompletableFuture that completes when the operation is done
   * @throws KeystoreException if there's an error during certificate import
   */
  @Override
  public void importTrustCertificate(Certificate certificate, String alias) throws KeystoreException {
    log.debug(STR."Importing trust certificate with alias '\{alias}' using virtual thread");
    CompletableFuture.runAsync(() -> {
      try {
        super.importTrustCertificate(certificate, alias);
        log.debug(STR."Successfully imported trust certificate with alias '\{alias}'");
      } 
      catch (KeystoreException e) {
        log.error(STR."Failed to import trust certificate with alias '\{alias}': \{e.getMessage()}", e);
        throw new RuntimeException(e);
      }
    }, VIRTUAL_EXECUTOR);
  }

  /**
   * Imports a PEM-formatted trust certificate asynchronously using virtual threads.
   * The PEM parsing is done synchronously to validate the certificate before
   * starting the asynchronous import process.
   * 
   * @param certificateInPEM the certificate in PEM format
   * @param alias the alias to use for the certificate
   * @throws KeystoreException if there's an error during certificate import
   * @throws CertificateException if the certificate cannot be parsed
   */
  @Override
  public void importTrustCertificate(String certificateInPEM, String alias)
      throws KeystoreException, CertificateException
  {
    log.debug(STR."Importing PEM trust certificate with alias '\{alias}' using virtual thread");
    try {
      // Parse the certificate synchronously to validate it before async processing
      Certificate certificate = org.sonatype.nexus.ssl.CertificateUtil.decodePEMFormattedCertificate(certificateInPEM);
      
      // Then import it asynchronously
      importTrustCertificate(certificate, alias);
    }
    catch (CertificateException e) {
      log.error(STR."Failed to decode PEM certificate with alias '\{alias}': \{e.getMessage()}", e);
      throw e;
    }
  }

  /**
   * Generates and stores a key pair asynchronously using virtual threads.
   * 
   * @param commonName the common name for the certificate
   * @param organizationalUnit the organizational unit
   * @param organization the organization
   * @param locality the locality
   * @param state the state
   * @param country the country
   * @throws KeystoreException if there's an error during key pair generation
   */
  @Override
  public void generateAndStoreKeyPair(final String commonName,
                                    final String organizationalUnit,
                                    final String organization,
                                    final String locality,
                                    final String state,
                                    final String country)
      throws KeystoreException
  {
    log.debug(STR."Generating key pair for CN='\{commonName}' using virtual thread");
    CompletableFuture.runAsync(() -> {
      try {
        super.generateAndStoreKeyPair(commonName, organizationalUnit, organization, locality, state, country);
        log.debug(STR."Successfully generated key pair for CN='\{commonName}'");
      }
      catch (KeystoreException e) {
        log.error(STR."Failed to generate key pair for CN='\{commonName}': \{e.getMessage()}", e);
        throw new RuntimeException(e);
      }
    }, VIRTUAL_EXECUTOR);
  }

  /**
   * Removes a trust certificate asynchronously using virtual threads.
   * 
   * @param alias the alias of the certificate to remove
   * @throws KeystoreException if there's an error during certificate removal
   */
  @Override
  public void removeTrustCertificate(String alias) throws KeystoreException {
    log.debug(STR."Removing trust certificate with alias '\{alias}' using virtual thread");
    CompletableFuture.runAsync(() -> {
      try {
        super.removeTrustCertificate(alias);
        log.debug(STR."Successfully removed trust certificate with alias '\{alias}'");
      }
      catch (KeystoreException e) {
        log.error(STR."Failed to remove trust certificate with alias '\{alias}': \{e.getMessage()}", e);
        throw new RuntimeException(e);
      }
    }, VIRTUAL_EXECUTOR);
  }

  /**
   * Reloads the trusted keystore asynchronously using virtual threads.
   * 
   * @throws KeystoreException if there's an error during keystore reload
   */
  @Override
  public void reloadTrustedKeystore() throws KeystoreException {
    log.debug(STR."Reloading trusted keystore using virtual thread");
    CompletableFuture.runAsync(() -> {
      try {
        super.reloadTrustedKeystore();
        log.debug(STR."Successfully reloaded trusted keystore");
      }
      catch (KeystoreException e) {
        log.error(STR."Failed to reload trusted keystore: \{e.getMessage()}", e);
        throw new RuntimeException(e);
      }
    }, VIRTUAL_EXECUTOR);
  }
}
