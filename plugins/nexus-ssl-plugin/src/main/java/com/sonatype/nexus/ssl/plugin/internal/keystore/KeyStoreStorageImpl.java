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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
import java.security.cert.CertificateException;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.ssl.spi.KeyStoreStorage;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;

/**
 * MyBatis {@link KeyStoreStorage} implementation.
 * Updated for Java 21 with Virtual Threads for I/O operations and enhanced security provider compatibility.
 *
 * @since 3.21
 */
public class KeyStoreStorageImpl
    implements KeyStoreStorage
{
  private static final Logger log = LoggerFactory.getLogger(KeyStoreStorageImpl.class);
  
  private final KeyStoreStorageManagerImpl storage;

  private final String keyStoreName;
  
  /**
   * The executor service for running I/O operations on virtual threads.
   * Using virtual threads improves performance for I/O-bound operations like keystore loading and saving.
   */
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  public KeyStoreStorageImpl(final KeyStoreStorageManagerImpl storage, final String keyStoreName) {
    this.storage = checkNotNull(storage);
    this.keyStoreName = checkNotNull(keyStoreName);
    
    // Log available security providers for diagnostic purposes
    if (log.isDebugEnabled()) {
      logSecurityProviders();
    }
  }
  
  /**
   * Logs the available security providers for diagnostic purposes.
   * This helps identify which providers are available in the Java 21 environment.
   */
  private void logSecurityProviders() {
    log.debug(STR."Available security providers for keystore operations:");
    for (Provider provider : Security.getProviders()) {
      log.debug(STR."  - \{provider.getName()} (\{provider.getVersionStr()}): \{provider.getInfo()}");
    }
  }

  @Override
  public boolean exists() {
    log.debug(STR."Checking if keystore exists: \{keyStoreName}");
    return storage.exists(keyStoreName);
  }

  @Override
  public boolean modified() {
    log.debug(STR."Checking if keystore was modified: \{keyStoreName}");
    return false; // we don't track the external version at the moment
  }

  /**
   * Loads a keystore using virtual threads for improved I/O performance.
   * This implementation uses Java 21's virtual threads to handle the I/O operations
   * without blocking platform threads, resulting in better scalability.
   *
   * @param keyStore the KeyStore instance to load into
   * @param password the password to unlock the keystore
   * @throws NoSuchAlgorithmException if the algorithm used to check the integrity of the keystore cannot be found
   * @throws CertificateException if any of the certificates in the keystore could not be loaded
   * @throws IOException if there is an I/O or format problem with the keystore data
   */
  @Override
  public void load(
      final KeyStore keyStore,
      final char[] password) throws NoSuchAlgorithmException, CertificateException, IOException
  {
    log.debug(STR."Loading keystore: \{keyStoreName}");
    try {
      // Run the keystore loading operation on a virtual thread
      runOnVirtualThread(() -> {
        try (ByteArrayInputStream in = storage.load(keyStoreName)) {
          // Use a buffered stream with an optimal buffer size for better performance
          byte[] data = in.readAllBytes();
          try (ByteArrayInputStream bufferedIn = new ByteArrayInputStream(data)) {
            keyStore.load(bufferedIn, password);
            log.debug(STR."Successfully loaded keystore: \{keyStoreName} (\{data.length} bytes)");
          }
        } catch (Exception e) {
          log.error(STR."Error loading keystore \{keyStoreName}: \{e.getMessage()}", e);
          throw new RuntimeException(e);
        }
        return null;
      });
    } catch (RuntimeException e) {
      if (e.getCause() instanceof NoSuchAlgorithmException) {
        throw (NoSuchAlgorithmException) e.getCause();
      } else if (e.getCause() instanceof CertificateException) {
        throw (CertificateException) e.getCause();
      } else if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      }
      throw e;
    }
  }

  /**
   * Saves a keystore using virtual threads for improved I/O performance.
   * This implementation uses Java 21's virtual threads to handle the I/O operations
   * without blocking platform threads, resulting in better scalability.
   *
   * @param keyStore the KeyStore instance to save
   * @param password the password to protect the keystore
   * @throws KeyStoreException if the keystore has not been initialized
   * @throws NoSuchAlgorithmException if the appropriate data integrity algorithm cannot be found
   * @throws CertificateException if any of the certificates included in the keystore data could not be stored
   * @throws IOException if there was an I/O problem with data
   */
  @Override
  public void save(
      final KeyStore keyStore,
      final char[] password) throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException
  {
    log.debug(STR."Saving keystore: \{keyStoreName}");
    try {
      // Run the keystore saving operation on a virtual thread
      runOnVirtualThread(() -> {
        try {
          // Use a larger initial buffer size for better performance with larger keystores
          // 32KB is a good balance for most keystores without wasting memory
          try (ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024)) {
            keyStore.store(out, password);
            storage.save(keyStoreName, out);
            log.debug(STR."Successfully saved keystore: \{keyStoreName} (\{out.size()} bytes)");
          }
        } catch (Exception e) {
          log.error(STR."Error saving keystore \{keyStoreName}: \{e.getMessage()}", e);
          throw new RuntimeException(e);
        }
        return null;
      });
    } catch (RuntimeException e) {
      if (e.getCause() instanceof KeyStoreException) {
        throw (KeyStoreException) e.getCause();
      } else if (e.getCause() instanceof NoSuchAlgorithmException) {
        throw (NoSuchAlgorithmException) e.getCause();
      } else if (e.getCause() instanceof CertificateException) {
        throw (CertificateException) e.getCause();
      } else if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      }
      throw e;
    }
  }
  /**
   * Executes a task on a virtual thread for improved I/O performance.
   * This method leverages Java 21's Virtual Threads to handle I/O operations more efficiently.
   *
   * @param <T> The return type of the operation
   * @param task The task to execute
   * @return The result of the operation
   * @throws RuntimeException If an error occurs during execution
   */
  private <T> T runOnVirtualThread(Callable<T> task) {
    try {
      return virtualThreadExecutor.submit(task).get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(STR."Virtual thread operation was interrupted: \{e.getMessage()}", e);
    } catch (Exception e) {
      throw new RuntimeException(STR."Error in virtual thread operation: \{e.getMessage()}", e);
    }
  }
}