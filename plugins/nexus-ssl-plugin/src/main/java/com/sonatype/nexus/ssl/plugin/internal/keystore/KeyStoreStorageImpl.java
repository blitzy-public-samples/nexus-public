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
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.ssl.spi.KeyStoreStorage;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;

/**
 * MyBatis {@link KeyStoreStorage} implementation.
 *
 * @since 3.21
 */
public class KeyStoreStorageImpl
    implements KeyStoreStorage
{
  private static final Logger log = LoggerFactory.getLogger(KeyStoreStorageImpl.class);
  
  private static final int DEFAULT_BUFFER_SIZE = 16 * 1024;
  
  private final KeyStoreStorageManagerImpl storage;

  private final String keyStoreName;

  public KeyStoreStorageImpl(final KeyStoreStorageManagerImpl storage, final String keyStoreName) {
    this.storage = checkNotNull(storage);
    this.keyStoreName = checkNotNull(keyStoreName);
    
    // Log available security providers for debugging purposes
    if (log.isDebugEnabled()) {
      StringBuilder providers = new StringBuilder();
      for (Provider provider : Security.getProviders()) {
        providers.append(STR."\n  - \{provider.getName()} (\{provider.getVersionStr()}) - \{provider.getInfo()}");
      }
      log.debug(STR."Available security providers for keystore operations:\{providers}");
    }
  }

  @Override
  public boolean exists() {
    boolean exists = storage.exists(keyStoreName);
    log.debug(STR."Checking if keystore '\{keyStoreName}' exists: \{exists}");
    return exists;
  }

  @Override
  public boolean modified() {
    return false; // we don't track the external version at the moment
  }

  @Override
  public void load(
      final KeyStore keyStore,
      final char[] password) throws NoSuchAlgorithmException, CertificateException, IOException
  {
    log.debug(STR."Loading keystore '\{keyStoreName}'");
    
    try {
      // Use virtual thread for I/O operation to avoid blocking platform threads
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try (ByteArrayInputStream in = storage.load(keyStoreName)) {
          try {
            keyStore.load(in, password);
            log.debug(STR."Successfully loaded keystore '\{keyStoreName}'");
          } catch (NoSuchAlgorithmException | CertificateException | IOException e) {
            log.error(STR."Failed to load keystore '\{keyStoreName}': \{e.getMessage()}");
            throw new RuntimeException(e);
          }
        } catch (IOException e) {
          log.error(STR."I/O error while loading keystore '\{keyStoreName}': \{e.getMessage()}");
          throw new RuntimeException(e);
        }
      }, Thread.ofVirtual().factory());
      
      future.join(); // Wait for completion and propagate any exceptions
    } catch (RuntimeException e) {
      // Unwrap the original exception
      Throwable cause = e.getCause();
      if (cause instanceof NoSuchAlgorithmException) {
        throw (NoSuchAlgorithmException) cause;
      } else if (cause instanceof CertificateException) {
        throw (CertificateException) cause;
      } else if (cause instanceof IOException) {
        throw (IOException) cause;
      }
      throw e;
    }
  }

  @Override
  public void save(
      final KeyStore keyStore,
      final char[] password) throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException
  {
    log.debug(STR."Saving keystore '\{keyStoreName}'");
    
    try {
      // Use virtual thread for I/O operation to avoid blocking platform threads
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(DEFAULT_BUFFER_SIZE)) {
          try {
            keyStore.store(out, password);
            storage.save(keyStoreName, out);
            log.debug(STR."Successfully saved keystore '\{keyStoreName}' (\{out.size()} bytes)");
          } catch (KeyStoreException | NoSuchAlgorithmException | CertificateException | IOException e) {
            log.error(STR."Failed to save keystore '\{keyStoreName}': \{e.getMessage()}");
            throw new RuntimeException(e);
          }
        } catch (IOException e) {
          log.error(STR."I/O error while saving keystore '\{keyStoreName}': \{e.getMessage()}");
          throw new RuntimeException(e);
        }
      }, Thread.ofVirtual().factory());
      
      future.join(); // Wait for completion and propagate any exceptions
    } catch (RuntimeException e) {
      // Unwrap the original exception
      Throwable cause = e.getCause();
      if (cause instanceof KeyStoreException) {
        throw (KeyStoreException) cause;
      } else if (cause instanceof NoSuchAlgorithmException) {
        throw (NoSuchAlgorithmException) cause;
      } else if (cause instanceof CertificateException) {
        throw (CertificateException) cause;
      } else if (cause instanceof IOException) {
        throw (IOException) cause;
      }
      throw e;
    }
  }
}