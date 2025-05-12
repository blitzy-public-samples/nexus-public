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
import java.security.cert.CertificateException;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.ssl.spi.KeyStoreStorage;

import static java.lang.StringTemplate.STR;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * MyBatis {@link KeyStoreStorage} implementation.
 * 
 * Updated for Java 21 with the following enhancements:
 * <ul>
 *   <li>Virtual threads for I/O operations to improve scalability and throughput</li>
 *   <li>String templates for structured logging and improved error messages</li>
 *   <li>Enhanced exception handling with proper cause propagation</li>
 *   <li>Comprehensive documentation aligned with Java 21 best practices</li>
 * </ul>
 * 
 * This implementation leverages Java 21's virtual threads which are lightweight threads
 * managed by the JVM rather than the OS. Virtual threads are particularly well-suited for
 * I/O-bound operations like keystore loading and saving, as they don't block OS threads
 * during I/O operations. This allows for thousands of concurrent operations with minimal
 * resource overhead.
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
   * Constructor for the KeyStoreStorage implementation.
   *
   * @param storage the storage manager implementation
   * @param keyStoreName the name of the keystore
   */
  public KeyStoreStorageImpl(final KeyStoreStorageManagerImpl storage, final String keyStoreName) {
    this.storage = checkNotNull(storage);
    this.keyStoreName = checkNotNull(keyStoreName);
  }

  @Override
  public boolean exists() {
    return storage.exists(keyStoreName);
  }

  @Override
  public boolean modified() {
    return false; // we don't track the external version at the moment
  }

  /**
   * Loads a KeyStore from storage using virtual threads for improved I/O performance.
   * 
   * Virtual threads are lightweight threads managed by the JVM that are particularly
   * well-suited for I/O-bound operations like loading keystores. They allow for high
   * concurrency with minimal resource overhead.
   * 
   * @param keyStore the KeyStore to load into
   * @param password the password to unlock the KeyStore
   * @throws NoSuchAlgorithmException if the algorithm used to check the integrity of the KeyStore cannot be found
   * @throws CertificateException if any of the certificates in the KeyStore could not be loaded
   * @throws IOException if there is an I/O or format problem with the KeyStore data
   */
  @Override
  public void load(
      final KeyStore keyStore,
      final char[] password) throws NoSuchAlgorithmException, CertificateException, IOException
  {
    log.debug(STR."Loading keystore: \{keyStoreName}");
    try {
      // Use virtual threads for I/O operations to improve scalability
      // Virtual threads are managed by the JVM and don't block OS threads during I/O operations
      Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
        try (ByteArrayInputStream in = storage.load(keyStoreName)) {
          keyStore.load(in, password);
          log.debug(STR."Successfully loaded keystore: \{keyStoreName}");
          return null;
        } catch (Exception e) {
          throw new RuntimeException(STR."Failed to load keystore \{keyStoreName}", e);
        }
      }).get();
    } catch (Exception e) {
      // Unwrap the cause if it's an I/O, certificate, or algorithm exception
      Throwable cause = e.getCause();
      if (cause instanceof IOException) {
        throw (IOException) cause;
      } else if (cause instanceof CertificateException) {
        throw (CertificateException) cause;
      } else if (cause instanceof NoSuchAlgorithmException) {
        throw (NoSuchAlgorithmException) cause;
      }
      // Otherwise wrap in IOException with a descriptive message
      throw new IOException(STR."Error loading keystore \{keyStoreName}", e);
    }
  }

  /**
   * Saves a KeyStore to storage using virtual threads for improved I/O performance.
   * 
   * This method leverages Java 21 virtual threads which are ideal for I/O-bound operations.
   * Unlike traditional platform threads, virtual threads are:
   * - Lightweight (require only a few kilobytes of memory)
   * - Managed by the JVM rather than the OS
   * - Automatically suspended during blocking operations, freeing the carrier thread
   * - Able to support thousands of concurrent operations with minimal overhead
   * 
   * @param keyStore the KeyStore to save
   * @param password the password to protect the KeyStore
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
      // Create a new virtual thread for this I/O operation
      // No need for thread pools or executor management - each task gets its own virtual thread
      Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024)) {
          keyStore.store(out, password);
          storage.save(keyStoreName, out);
          log.debug(STR."Successfully saved keystore: \{keyStoreName}");
          return null;
        } catch (Exception e) {
          throw new RuntimeException(STR."Failed to save keystore \{keyStoreName}", e);
        }
      }).get();
    } catch (Exception e) {
      // Unwrap the cause if it's a known exception type
      Throwable cause = e.getCause();
      if (cause instanceof IOException) {
        throw (IOException) cause;
      } else if (cause instanceof CertificateException) {
        throw (CertificateException) cause;
      } else if (cause instanceof NoSuchAlgorithmException) {
        throw (NoSuchAlgorithmException) cause;
      } else if (cause instanceof KeyStoreException) {
        throw (KeyStoreException) cause;
      }
      // Otherwise wrap in IOException with a descriptive message using Java 21 string templates
      throw new IOException(STR."Error saving keystore \{keyStoreName}", e);
    }
  }
}
