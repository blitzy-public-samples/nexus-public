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
package org.sonatype.nexus.internal.node;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CompletableFuture;

import org.sonatype.nexus.ssl.spi.KeyStoreStorage;

import com.google.common.annotations.VisibleForTesting;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Implementation of {@link KeyStoreStorage} backed by local filesystem.
 * Uses Java 21 Virtual Threads for improved I/O performance.
 * 
 * @since 3.1
 */
public class FileKeyStoreStorage
    implements KeyStoreStorage
{
  /**
   * Shared executor service using Virtual Threads for I/O operations.
   * Virtual threads are lightweight and don't need to be pooled, so we create a new one for each task.
   */
  private static final ExecutorService IO_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
  
  private final File keyStoreFile;

  private long lastRead;

  public FileKeyStoreStorage(final File keyStoreFile) {
    this.keyStoreFile = checkNotNull(keyStoreFile);
  }

  @VisibleForTesting
  public File getKeyStoreFile() {
    return keyStoreFile;
  }

  @Override
  public boolean exists() {
    return keyStoreFile.exists();
  }

  @Override
  public boolean modified() {
    return lastRead < keyStoreFile.lastModified();
  }

  /**
   * Loads a KeyStore from the file system using Virtual Threads for improved I/O performance.
   * 
   * @param keyStore the KeyStore to load into
   * @param password the password to unlock the KeyStore
   */
  @Override
  public void load(
      final KeyStore keyStore,
      final char[] password) throws NoSuchAlgorithmException, CertificateException, IOException
  {
    long readStart = System.currentTimeMillis();
    
    // Submit the I/O operation to be executed on a virtual thread
    CompletableFuture<Void> loadTask = CompletableFuture.runAsync(() -> {
      // Using try-with-resources with Java 21's enhanced exception handling
      try (InputStream fis = new FileInputStream(keyStoreFile);
           BufferedInputStream bis = new BufferedInputStream(fis)) {
        try {
          keyStore.load(bis, password);
        } catch (Exception e) {
          // Preserve the original exception type by rethrowing it
          throwAsUnchecked(e);
        }
      } catch (IOException e) {
        throwAsUnchecked(e);
      }
    }, IO_EXECUTOR);
    
    try {
      // Wait for the I/O operation to complete
      loadTask.join();
    } catch (Exception e) {
      // Unwrap and rethrow the original exception
      handleExecutionException(e);
    }
    
    lastRead = readStart;
  }

  /**
   * Saves a KeyStore to the file system using Virtual Threads for improved I/O performance.
   * 
   * @param keyStore the KeyStore to save
   * @param password the password to protect the KeyStore
   */
  @Override
  public void save(
      final KeyStore keyStore,
      final char[] password) throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException
  {
    // Create parent directories if they don't exist
    keyStoreFile.getParentFile().mkdirs(); // NOSONAR
    
    // Submit the I/O operation to be executed on a virtual thread
    CompletableFuture<Void> saveTask = CompletableFuture.runAsync(() -> {
      // Using try-with-resources with Java 21's enhanced exception handling
      try (OutputStream fos = new FileOutputStream(keyStoreFile);
           BufferedOutputStream bos = new BufferedOutputStream(fos)) {
        try {
          keyStore.store(bos, password);
        } catch (Exception e) {
          // Preserve the original exception type by rethrowing it
          throwAsUnchecked(e);
        }
      } catch (IOException e) {
        throwAsUnchecked(e);
      }
    }, IO_EXECUTOR);
    
    try {
      // Wait for the I/O operation to complete
      saveTask.join();
    } catch (Exception e) {
      // Unwrap and rethrow the original exception
      handleExecutionException(e);
    }
    
    lastRead = System.currentTimeMillis();
  }

  /**
   * Helper method to handle exceptions from CompletableFuture execution.
   * Unwraps and rethrows the original exception with its proper type.
   */
  private void handleExecutionException(Exception e) 
      throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException {
    Throwable cause = e.getCause();
    if (cause == null) {
      throw new IOException("Unknown error during keystore operation", e);
    }
    
    if (cause instanceof KeyStoreException keyStoreEx) {
      throw keyStoreEx;
    } else if (cause instanceof NoSuchAlgorithmException noSuchAlgoEx) {
      throw noSuchAlgoEx;
    } else if (cause instanceof CertificateException certEx) {
      throw certEx;
    } else if (cause instanceof IOException ioEx) {
      throw ioEx;
    } else {
      throw new IOException("Failed to perform keystore operation", cause);
    }
  }
  
  /**
   * Helper method to rethrow checked exceptions as unchecked.
   * This preserves the original exception type when rethrowing from lambdas.
   */
  @SuppressWarnings("unchecked")
  private static <E extends Throwable> void throwAsUnchecked(Throwable exception) throws E {
    throw (E) exception;
  }

  @Override
  public String toString() {
    return keyStoreFile.toURI().toString();
  }
}