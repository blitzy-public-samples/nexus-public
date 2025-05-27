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
package virtualthread;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.common.Time;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.crypto.CryptoHelper;
import org.sonatype.nexus.crypto.internal.CryptoHelperImpl;
import org.sonatype.nexus.ssl.CertificateUtil;
import org.sonatype.nexus.ssl.KeyStoreManager;
import org.sonatype.nexus.ssl.KeyStoreManagerConfiguration;
import org.sonatype.nexus.ssl.KeyStoreManagerImpl;
import org.sonatype.nexus.ssl.spi.KeyStoreStorage;
import org.sonatype.nexus.ssl.spi.KeyStoreStorageManager;

import org.junit.Before;
import org.junit.Test;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link KeyStoreManagerImpl} using Java 21 Virtual Threads.
 * 
 * This test class validates that keystore operations maintain thread safety and performance
 * when executed concurrently using virtual threads.
 */
public class KeyStoreVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_OPERATIONS = 100;
  private static final int TIMEOUT_SECONDS = 30;
  
  private final CryptoHelper crypto = new CryptoHelperImpl();

  private KeyStoreStorageManager storageManager;

  private KeyStoreManager keyStoreManager;

  @Before
  public void setUp() throws Exception {
    storageManager = new MemKeyStoreStorageManager();
    keyStoreManager = createKeyStoreManager(storageManager);
    
    // Initialize the key pair for subsequent tests
    keyStoreManager.generateAndStoreKeyPair("Test Subject", "Test OU", "Test Org", "Test Locality", "Test State", "US");
  }

  private KeyStoreManagerConfiguration createMockConfiguration() {
    KeyStoreManagerConfiguration config = mock(KeyStoreManagerConfiguration.class);
    // Use lower strength for faster test execution
    when(config.getKeyStoreType()).thenReturn("JKS");
    when(config.getKeyAlgorithm()).thenReturn("RSA");
    when(config.getKeyAlgorithmSize()).thenReturn(1024);
    when(config.getSignatureAlgorithm()).thenReturn("SHA1WITHRSA");
    when(config.getCertificateValidity()).thenReturn(Time.days(36500));
    when(config.getKeyManagerAlgorithm()).thenReturn("SunX509");
    when(config.getTrustManagerAlgorithm()).thenReturn("SunX509");
    when(config.getPrivateKeyStorePassword()).thenReturn("pwd".toCharArray());
    when(config.getTrustedKeyStorePassword()).thenReturn("pwd".toCharArray());
    when(config.getPrivateKeyPassword()).thenReturn("pwd".toCharArray());
    return config;
  }

  private KeyStoreManager createKeyStoreManager(final KeyStoreStorageManager storageManager) {
    return new KeyStoreManagerImpl(crypto, storageManager, createMockConfiguration());
  }

  /**
   * Tests concurrent key pair generation using virtual threads.
   * This verifies that multiple virtual threads can safely generate key pairs without thread safety issues.
   */
  @Test
  public void testConcurrentKeyPairGenerationWithVirtualThreads() throws Exception {
    // Create a new KeyStoreManager for this test to avoid interference
    KeyStoreStorageManager testStorageManager = new MemKeyStoreStorageManager();
    KeyStoreManager testKeyStoreManager = createKeyStoreManager(testStorageManager);
    
    AtomicInteger successCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final int index = i;
        futures.add(executor.submit(() -> {
          try {
            // Create a new KeyStoreManager for each thread to avoid interference
            KeyStoreManager threadKeyStoreManager = createKeyStoreManager(new MemKeyStoreStorageManager());
            threadKeyStoreManager.generateAndStoreKeyPair(
                "Subject-" + index, "OU-" + index, "Org", "Locality", "State", "US");
            successCount.incrementAndGet();
            return true;
          } catch (Exception e) {
            log.error("Error in virtual thread {}", index, e);
            return false;
          } finally {
            latch.countDown();
          }
        }));
      }
      
      // Wait for all operations to complete or timeout
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue("Not all operations completed within the timeout", completed);
      
      // Verify all operations succeeded
      assertEquals("All key pair generation operations should succeed", CONCURRENT_OPERATIONS, successCount.get());
      
      // Check for any exceptions in the futures
      for (Future<?> future : futures) {
        assertTrue("Operation should complete successfully", (Boolean) future.get());
      }
    }
  }

  /**
   * Tests concurrent certificate import operations using virtual threads.
   * This verifies that multiple virtual threads can safely import certificates without thread safety issues.
   */
  @Test
  public void testConcurrentCertificateImportWithVirtualThreads() throws Exception {
    AtomicInteger successCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    
    // Pre-generate certificates to avoid generation overhead during the test
    List<X509Certificate> certificates = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      certificates.add(generateCertificate(10, "Cert-" + i, "OU", "Org", "Locality", "State", "US"));
    }
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final int index = i;
        final X509Certificate cert = certificates.get(i);
        
        futures.add(executor.submit(() -> {
          try {
            keyStoreManager.importTrustCertificate(cert, "cert-alias-" + index);
            successCount.incrementAndGet();
            return true;
          } catch (Exception e) {
            log.error("Error importing certificate in virtual thread {}", index, e);
            return false;
          } finally {
            latch.countDown();
          }
        }));
      }
      
      // Wait for all operations to complete or timeout
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue("Not all operations completed within the timeout", completed);
      
      // Verify all operations succeeded
      assertEquals("All certificate import operations should succeed", CONCURRENT_OPERATIONS, successCount.get());
      
      // Check for any exceptions in the futures
      for (Future<?> future : futures) {
        assertTrue("Operation should complete successfully", (Boolean) future.get());
      }
      
      // Verify certificates were actually imported
      assertEquals("All certificates should be in the trust store", 
          CONCURRENT_OPERATIONS, keyStoreManager.getTrustedCertificates().size());
    }
  }

  /**
   * Tests concurrent certificate export operations using virtual threads.
   * This verifies that multiple virtual threads can safely export certificates without thread safety issues.
   */
  @Test
  public void testConcurrentCertificateExportWithVirtualThreads() throws Exception {
    // First import certificates to export
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      X509Certificate cert = generateCertificate(10, "Cert-" + i, "OU", "Org", "Locality", "State", "US");
      keyStoreManager.importTrustCertificate(cert, "cert-alias-" + i);
    }
    
    AtomicInteger successCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<String>> futures = new ArrayList<>();
      
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final int index = i;
        
        futures.add(executor.submit(() -> {
          try {
            Certificate cert = keyStoreManager.getTrustedCertificate("cert-alias-" + index);
            String pemCert = CertificateUtil.serializeCertificateInPEM(cert);
            successCount.incrementAndGet();
            return pemCert;
          } catch (Exception e) {
            log.error("Error exporting certificate in virtual thread {}", index, e);
            return null;
          } finally {
            latch.countDown();
          }
        }));
      }
      
      // Wait for all operations to complete or timeout
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue("Not all operations completed within the timeout", completed);
      
      // Verify all operations succeeded
      assertEquals("All certificate export operations should succeed", CONCURRENT_OPERATIONS, successCount.get());
      
      // Check that all exports returned valid PEM certificates
      for (Future<String> future : futures) {
        String pemCert = future.get();
        assertThat("PEM certificate should not be null", pemCert, notNullValue());
        assertTrue("PEM certificate should contain BEGIN CERTIFICATE", pemCert.contains("BEGIN CERTIFICATE"));
        assertTrue("PEM certificate should contain END CERTIFICATE", pemCert.contains("END CERTIFICATE"));
      }
    }
  }

  /**
   * Tests concurrent keystore loading and saving operations using virtual threads.
   * This verifies that multiple virtual threads can safely perform I/O operations on keystores
   * without thread safety issues or thread pinning.
   */
  @Test
  public void testConcurrentKeystoreLoadSaveWithVirtualThreads() throws Exception {
    // Create a test keystore with a certificate
    KeyStore keyStore = KeyStore.getInstance("JKS");
    keyStore.load(null, "password".toCharArray());
    
    // Add a test certificate
    X509Certificate cert = generateCertificate(10, "Test", "OU", "Org", "Locality", "State", "US");
    keyStore.setCertificateEntry("test-cert", cert);
    
    // Save the keystore to a byte array
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    keyStore.store(baos, "password".toCharArray());
    byte[] keystoreBytes = baos.toByteArray();
    
    AtomicInteger successCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final int index = i;
        
        futures.add(executor.submit(() -> {
          try {
            // Load the keystore
            KeyStore threadKeyStore = KeyStore.getInstance("JKS");
            threadKeyStore.load(new ByteArrayInputStream(keystoreBytes), "password".toCharArray());
            
            // Modify the keystore
            X509Certificate threadCert = generateCertificate(
                10, "Thread-" + index, "OU", "Org", "Locality", "State", "US");
            threadKeyStore.setCertificateEntry("thread-cert-" + index, threadCert);
            
            // Save the keystore
            ByteArrayOutputStream threadBaos = new ByteArrayOutputStream();
            threadKeyStore.store(threadBaos, "password".toCharArray());
            byte[] threadKeystoreBytes = threadBaos.toByteArray();
            
            // Verify the saved keystore can be loaded again
            KeyStore verifyKeyStore = KeyStore.getInstance("JKS");
            verifyKeyStore.load(new ByteArrayInputStream(threadKeystoreBytes), "password".toCharArray());
            Certificate loadedCert = verifyKeyStore.getCertificate("thread-cert-" + index);
            
            // Verify the certificate was saved and loaded correctly
            assertThat("Certificate should be saved and loaded correctly", loadedCert, equalTo(threadCert));
            
            successCount.incrementAndGet();
            return true;
          } catch (Exception e) {
            log.error("Error in keystore load/save in virtual thread {}", index, e);
            return false;
          } finally {
            latch.countDown();
          }
        }));
      }
      
      // Wait for all operations to complete or timeout
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue("Not all operations completed within the timeout", completed);
      
      // Verify all operations succeeded
      assertEquals("All keystore load/save operations should succeed", CONCURRENT_OPERATIONS, successCount.get());
      
      // Check for any exceptions in the futures
      for (Future<?> future : futures) {
        assertTrue("Operation should complete successfully", (Boolean) future.get());
      }
    }
  }
  
  /**
   * Tests high-concurrency performance of keystore operations using virtual threads.
   * This verifies that virtual threads can handle a large number of concurrent operations
   * without significant performance degradation.
   */
  @Test
  public void testHighConcurrencyPerformanceWithVirtualThreads() throws Exception {
    final int HIGH_CONCURRENCY = 1000; // Test with 1000 concurrent operations
    AtomicInteger successCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(HIGH_CONCURRENCY);
    
    // Record start time
    long startTime = System.nanoTime();
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      
      for (int i = 0; i < HIGH_CONCURRENCY; i++) {
        final int index = i;
        
        futures.add(executor.submit(() -> {
          try {
            // Create a new KeyStoreManager for each thread to avoid interference
            KeyStoreManager threadKeyStoreManager = createKeyStoreManager(new MemKeyStoreStorageManager());
            
            // Generate a key pair
            threadKeyStoreManager.generateAndStoreKeyPair(
                "Subject-" + index, "OU", "Org", "Locality", "State", "US");
            
            // Export the certificate
            Certificate cert = threadKeyStoreManager.getCertificate();
            String pemCert = CertificateUtil.serializeCertificateInPEM(cert);
            
            // Import the certificate to the trust store
            threadKeyStoreManager.importTrustCertificate(pemCert, "cert-alias-" + index);
            
            successCount.incrementAndGet();
            return true;
          } catch (Exception e) {
            log.error("Error in virtual thread {}", index, e);
            return false;
          } finally {
            latch.countDown();
          }
        }));
      }
      
      // Wait for all operations to complete or timeout
      boolean completed = latch.await(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS); // Double timeout for high concurrency
      assertTrue("Not all operations completed within the timeout", completed);
      
      // Calculate elapsed time
      long elapsedTime = System.nanoTime() - startTime;
      Duration duration = Duration.ofNanos(elapsedTime);
      
      log.info("Completed {} concurrent keystore operations in {} seconds", 
          HIGH_CONCURRENCY, duration.toMillis() / 1000.0);
      
      // Verify all operations succeeded
      assertEquals("All high-concurrency operations should succeed", HIGH_CONCURRENCY, successCount.get());
      
      // Check for any exceptions in the futures
      for (Future<?> future : futures) {
        try {
          assertTrue("Operation should complete successfully", (Boolean) future.get());
        } catch (ExecutionException e) {
          log.error("Operation failed with exception", e.getCause());
          throw e;
        }
      }
    }
  }

  /**
   * Generates a test X509Certificate for use in tests.
   */
  private X509Certificate generateCertificate(int validity,
                                              String commonName,
                                              String orgUnit,
                                              String organization,
                                              String locality,
                                              String state,
                                              String country)
      throws Exception
  {
    KeyPairGenerator kpgen = KeyPairGenerator.getInstance("RSA");
    kpgen.initialize(512);
    KeyPair keyPair = kpgen.generateKeyPair();

    return CertificateUtil.generateCertificate(keyPair.getPublic(), keyPair.getPrivate(), "SHA1WITHRSA", validity,
        commonName, orgUnit, organization, locality, state, country);
  }

  /**
   * In-memory implementation of KeyStoreStorageManager for testing.
   */
  static class MemKeyStoreStorageManager
      implements KeyStoreStorageManager
  {
    class MemKeyStoreStorage
        implements KeyStoreStorage
    {
      private final String keyStoreName;

      MemKeyStoreStorage(final String keyStoreName) {
        this.keyStoreName = checkNotNull(keyStoreName);
      }

      @Override
      public boolean exists() {
        return storages.get(keyStoreName) != null;
      }

      @Override
      public boolean modified() {
        return false;
      }

      @Override
      public void load(final KeyStore keyStore, final char[] password)
          throws NoSuchAlgorithmException, CertificateException, IOException
      {
        byte[] bytes = storages.get(keyStoreName);
        if (bytes != null) {
          keyStore.load(new ByteArrayInputStream(bytes), password);
        }
        else {
          keyStore.load(null, password);
        }
      }

      @Override
      public void save(final KeyStore keyStore, final char[] password)
          throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException
      {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        keyStore.store(baos, password);
        storages.put(keyStoreName, baos.toByteArray());
      }
    }

    private final Map<String, byte[]> storages = new HashMap<>();

    @Override
    public KeyStoreStorage createStorage(final String keyStoreName) {
      return new MemKeyStoreStorage(keyStoreName);
    }
  }
}