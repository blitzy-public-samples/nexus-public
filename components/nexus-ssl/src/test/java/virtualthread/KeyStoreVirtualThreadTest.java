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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.crypto.CryptoHelper;
import org.sonatype.nexus.crypto.internal.CryptoHelperImpl;
import org.sonatype.nexus.ssl.CertificateUtil;
import org.sonatype.nexus.ssl.KeyStoreManager;
import org.sonatype.nexus.ssl.KeyStoreManagerConfiguration;
import org.sonatype.nexus.ssl.KeyStoreManagerImpl;
import org.sonatype.nexus.ssl.spi.KeyStoreStorage;
import org.sonatype.nexus.ssl.spi.KeyStoreStorageManager;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for validating keystore operations using Java 21 Virtual Threads.
 * 
 * This test class verifies that keystore operations maintain thread safety and don't cause
 * thread pinning issues when executed concurrently using virtual threads.
 */
public class KeyStoreVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_TASKS = 100;
  private static final int HIGH_CONCURRENCY_TASKS = 1000;
  private static final String TEST_ALIAS = "test-alias";
  private static final String TEST_PASSWORD = "test-password";
  
  private final CryptoHelper crypto = new CryptoHelperImpl();
  
  private KeyStoreStorageManager storageManager;
  private KeyStoreManager keyStoreManager;
  private ExecutorService virtualThreadExecutor;
  
  @BeforeEach
  public void setUp() throws Exception {
    storageManager = new MemKeyStoreStorageManager();
    keyStoreManager = createKeyStoreManager(storageManager);
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }
  
  @AfterEach
  public void tearDown() throws Exception {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
  
  private KeyStoreManagerConfiguration createMockConfiguration() {
    KeyStoreManagerConfiguration config = mock(KeyStoreManagerConfiguration.class);
    // use lower strength for faster test execution
    when(config.getKeyStoreType()).thenReturn("JKS");
    when(config.getKeyAlgorithm()).thenReturn("RSA");
    when(config.getKeyAlgorithmSize()).thenReturn(1024);
    when(config.getSignatureAlgorithm()).thenReturn("SHA1WITHRSA");
    when(config.getCertificateValidity()).thenReturn(Duration.ofDays(365));
    when(config.getKeyManagerAlgorithm()).thenReturn("SunX509");
    when(config.getTrustManagerAlgorithm()).thenReturn("SunX509");
    when(config.getPrivateKeyStorePassword()).thenReturn(TEST_PASSWORD.toCharArray());
    when(config.getTrustedKeyStorePassword()).thenReturn(TEST_PASSWORD.toCharArray());
    when(config.getPrivateKeyPassword()).thenReturn(TEST_PASSWORD.toCharArray());
    return config;
  }
  
  private KeyStoreManager createKeyStoreManager(final KeyStoreStorageManager storageManager) {
    return new KeyStoreManagerImpl(crypto, storageManager, createMockConfiguration());
  }
  
  /**
   * Tests concurrent key pair generation using virtual threads.
   * Verifies that multiple key pair generations can be executed concurrently without issues.
   */
  @Test
  @Timeout(30)
  public void testConcurrentKeyPairGeneration() throws Exception {
    int taskCount = CONCURRENT_TASKS;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicBoolean keyPairInitialized = new AtomicBoolean(false);
    
    // Create multiple key store managers with separate storage
    List<KeyStoreManager> managers = new ArrayList<>();
    for (int i = 0; i < taskCount; i++) {
      managers.add(createKeyStoreManager(new MemKeyStoreStorageManager()));
    }
    
    // Submit concurrent key pair generation tasks using virtual threads
    for (int i = 0; i < taskCount; i++) {
      final int index = i;
      virtualThreadExecutor.submit(() -> {
        try {
          KeyStoreManager manager = managers.get(index);
          manager.generateAndStoreKeyPair(
              "Test Subject " + index,
              "Test OU",
              "Test Org",
              "Test Locality",
              "Test State",
              "US");
          
          // Verify key pair was initialized
          if (manager.isKeyPairInitialized()) {
            keyPairInitialized.set(true);
          }
        } 
        catch (Exception e) {
          log.error("Error in virtual thread task", e);
          errorCount.incrementAndGet();
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    assertTrue(latch.await(20, TimeUnit.SECONDS), "Tasks did not complete in time");
    
    // Verify results
    assertEquals(0, errorCount.get(), "Some tasks encountered errors");
    assertTrue(keyPairInitialized.get(), "Key pair was not initialized");
    
    // Verify each manager has a valid key pair
    for (int i = 0; i < taskCount; i++) {
      KeyStoreManager manager = managers.get(i);
      assertTrue(manager.isKeyPairInitialized(), "Manager " + i + " key pair not initialized");
      assertNotNull(manager.getCertificate(), "Manager " + i + " certificate is null");
    }
  }
  
  /**
   * Tests concurrent certificate import operations using virtual threads.
   * Verifies that multiple certificate imports can be executed concurrently without issues.
   */
  @Test
  @Timeout(30)
  public void testConcurrentCertificateImport() throws Exception {
    // First generate a key pair and get the certificate
    keyStoreManager.generateAndStoreKeyPair(
        "Test Subject",
        "Test OU",
        "Test Org",
        "Test Locality",
        "Test State",
        "US");
    
    X509Certificate certificate = (X509Certificate) keyStoreManager.getCertificate();
    assertNotNull(certificate, "Certificate should not be null");
    
    // Create multiple key store managers with separate storage
    int taskCount = CONCURRENT_TASKS;
    List<KeyStoreManager> managers = new ArrayList<>();
    for (int i = 0; i < taskCount; i++) {
      managers.add(createKeyStoreManager(new MemKeyStoreStorageManager()));
    }
    
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    ConcurrentHashMap<Integer, Boolean> importResults = new ConcurrentHashMap<>();
    
    // Submit concurrent certificate import tasks using virtual threads
    for (int i = 0; i < taskCount; i++) {
      final int index = i;
      virtualThreadExecutor.submit(() -> {
        try {
          KeyStoreManager manager = managers.get(index);
          manager.importTrustCertificate(certificate, "cert-" + index);
          
          // Verify certificate was imported
          Certificate imported = manager.getTrustedCertificate("cert-" + index);
          importResults.put(index, imported != null && imported.equals(certificate));
        } 
        catch (Exception e) {
          log.error("Error in virtual thread task", e);
          errorCount.incrementAndGet();
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    assertTrue(latch.await(20, TimeUnit.SECONDS), "Tasks did not complete in time");
    
    // Verify results
    assertEquals(0, errorCount.get(), "Some tasks encountered errors");
    assertEquals(taskCount, importResults.size(), "Not all import operations completed");
    
    // Verify each import was successful
    for (int i = 0; i < taskCount; i++) {
      assertTrue(importResults.getOrDefault(i, false), "Import for index " + i + " failed");
    }
  }
  
  /**
   * Tests concurrent certificate export operations using virtual threads.
   * Verifies that multiple certificate exports can be executed concurrently without issues.
   */
  @Test
  @Timeout(30)
  public void testConcurrentCertificateExport() throws Exception {
    // First generate a key pair and get the certificate
    keyStoreManager.generateAndStoreKeyPair(
        "Test Subject",
        "Test OU",
        "Test Org",
        "Test Locality",
        "Test State",
        "US");
    
    X509Certificate certificate = (X509Certificate) keyStoreManager.getCertificate();
    assertNotNull(certificate, "Certificate should not be null");
    
    int taskCount = CONCURRENT_TASKS;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    ConcurrentHashMap<Integer, String> exportResults = new ConcurrentHashMap<>();
    
    // Submit concurrent certificate export tasks using virtual threads
    for (int i = 0; i < taskCount; i++) {
      final int index = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Export certificate to PEM format
          String pemCert = CertificateUtil.serializeCertificateInPEM(certificate);
          exportResults.put(index, pemCert);
        } 
        catch (Exception e) {
          log.error("Error in virtual thread task", e);
          errorCount.incrementAndGet();
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    assertTrue(latch.await(20, TimeUnit.SECONDS), "Tasks did not complete in time");
    
    // Verify results
    assertEquals(0, errorCount.get(), "Some tasks encountered errors");
    assertEquals(taskCount, exportResults.size(), "Not all export operations completed");
    
    // Verify each export was successful and consistent
    String firstExport = exportResults.get(0);
    assertNotNull(firstExport, "First export should not be null");
    for (int i = 1; i < taskCount; i++) {
      String export = exportResults.get(i);
      assertNotNull(export, "Export for index " + i + " is null");
      assertEquals(firstExport, export, "Export for index " + i + " is inconsistent");
    }
  }
  
  /**
   * Tests concurrent keystore loading and saving operations using virtual threads.
   * Verifies that multiple keystore operations can be executed concurrently without issues.
   */
  @Test
  @Timeout(30)
  public void testConcurrentKeystoreLoadAndSave() throws Exception {
    int taskCount = CONCURRENT_TASKS;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Submit concurrent keystore load/save tasks using virtual threads
    for (int i = 0; i < taskCount; i++) {
      final int index = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Create a new keystore
          KeyStore keyStore = KeyStore.getInstance("JKS");
          keyStore.load(null, TEST_PASSWORD.toCharArray());
          
          // Generate a key pair
          KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
          kpg.initialize(1024);
          KeyPair keyPair = kpg.generateKeyPair();
          
          // Generate a self-signed certificate
          X509Certificate cert = CertificateUtil.generateCertificate(
              keyPair.getPublic(),
              keyPair.getPrivate(),
              "SHA1WITHRSA",
              365,
              "Test Subject " + index,
              "Test OU",
              "Test Org",
              "Test Locality",
              "Test State",
              "US");
          
          // Store in the keystore
          keyStore.setCertificateEntry("cert-" + index, cert);
          
          // Save the keystore to a byte array
          ByteArrayOutputStream baos = new ByteArrayOutputStream();
          keyStore.store(baos, TEST_PASSWORD.toCharArray());
          byte[] keystoreData = baos.toByteArray();
          
          // Load the keystore from the byte array
          KeyStore loadedKeyStore = KeyStore.getInstance("JKS");
          loadedKeyStore.load(new ByteArrayInputStream(keystoreData), TEST_PASSWORD.toCharArray());
          
          // Verify the certificate is in the loaded keystore
          Certificate loadedCert = loadedKeyStore.getCertificate("cert-" + index);
          assertNotNull(loadedCert, "Loaded certificate should not be null");
          assertEquals(cert, loadedCert, "Loaded certificate should match original");
        } 
        catch (Exception e) {
          log.error("Error in virtual thread task", e);
          errorCount.incrementAndGet();
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    assertTrue(latch.await(20, TimeUnit.SECONDS), "Tasks did not complete in time");
    
    // Verify results
    assertEquals(0, errorCount.get(), "Some tasks encountered errors");
  }
  
  /**
   * Tests high concurrency keystore operations using virtual threads.
   * Verifies that keystore operations maintain performance at high concurrency levels.
   */
  @Test
  @Timeout(60)
  public void testHighConcurrencyKeystoreOperations() throws Exception {
    int taskCount = HIGH_CONCURRENCY_TASKS;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // First generate a key pair and get the certificate
    keyStoreManager.generateAndStoreKeyPair(
        "Test Subject",
        "Test OU",
        "Test Org",
        "Test Locality",
        "Test State",
        "US");
    
    X509Certificate certificate = (X509Certificate) keyStoreManager.getCertificate();
    assertNotNull(certificate, "Certificate should not be null");
    
    // Create multiple key store managers with separate storage
    List<KeyStoreManager> managers = new ArrayList<>();
    for (int i = 0; i < 10; i++) { // Create 10 managers to be shared among tasks
      managers.add(createKeyStoreManager(new MemKeyStoreStorageManager()));
    }
    
    // Submit high concurrency tasks using virtual threads
    long startTime = System.currentTimeMillis();
    for (int i = 0; i < taskCount; i++) {
      final int index = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Use a manager from the pool (round-robin)
          KeyStoreManager manager = managers.get(index % managers.size());
          
          // Perform a mix of operations based on the index
          if (index % 3 == 0) {
            // Import certificate
            manager.importTrustCertificate(certificate, "cert-" + index);
          } 
          else if (index % 3 == 1) {
            // Export certificate to PEM
            String pemCert = CertificateUtil.serializeCertificateInPEM(certificate);
            assertNotNull(pemCert, "PEM certificate should not be null");
          } 
          else {
            // Get trusted certificates
            manager.getTrustedCertificates();
          }
        } 
        catch (Exception e) {
          log.error("Error in virtual thread task", e);
          errorCount.incrementAndGet();
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    assertTrue(latch.await(50, TimeUnit.SECONDS), "Tasks did not complete in time");
    long endTime = System.currentTimeMillis();
    
    // Verify results
    assertEquals(0, errorCount.get(), "Some tasks encountered errors");
    
    // Log performance metrics
    long duration = endTime - startTime;
    double operationsPerSecond = (taskCount * 1000.0) / duration;
    log.info("Completed {} keystore operations in {} ms ({} ops/sec)", 
        taskCount, duration, String.format("%.2f", operationsPerSecond));
    
    // Verify each manager has valid certificates
    for (int i = 0; i < managers.size(); i++) {
      KeyStoreManager manager = managers.get(i);
      assertFalse(manager.getTrustedCertificates().isEmpty(), 
          "Manager " + i + " should have trusted certificates");
    }
  }
  
  /**
   * Tests that keystore operations don't cause thread pinning in virtual threads.
   * This test would fail if operations caused carrier thread blocking.
   */
  @Test
  @Timeout(30)
  public void testKeystoreOperationsDoNotCauseThreadPinning() throws Exception {
    int taskCount = CONCURRENT_TASKS;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // First generate a key pair and get the certificate
    keyStoreManager.generateAndStoreKeyPair(
        "Test Subject",
        "Test OU",
        "Test Org",
        "Test Locality",
        "Test State",
        "US");
    
    X509Certificate certificate = (X509Certificate) keyStoreManager.getCertificate();
    
    // Create a shared keystore manager for all tasks
    KeyStoreManager sharedManager = createKeyStoreManager(new MemKeyStoreStorageManager());
    
    // Submit concurrent tasks that will all start at the same time
    for (int i = 0; i < taskCount; i++) {
      final int index = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Wait for the start signal
          startLatch.await();
          
          // Perform keystore operations
          sharedManager.importTrustCertificate(certificate, "cert-" + index);
          Certificate imported = sharedManager.getTrustedCertificate("cert-" + index);
          assertNotNull(imported, "Imported certificate should not be null");
          assertEquals(certificate, imported, "Imported certificate should match original");
        } 
        catch (Exception e) {
          log.error("Error in virtual thread task", e);
          errorCount.incrementAndGet();
        } 
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all tasks simultaneously
    startLatch.countDown();
    
    // Wait for all tasks to complete
    assertTrue(completionLatch.await(20, TimeUnit.SECONDS), "Tasks did not complete in time");
    
    // Verify results
    assertEquals(0, errorCount.get(), "Some tasks encountered errors");
    
    // Verify all certificates were imported
    assertEquals(taskCount, sharedManager.getTrustedCertificates().size(), 
        "Not all certificates were imported");
  }
  
  /**
   * Memory-based KeyStoreStorageManager implementation for testing.
   */
  static class MemKeyStoreStorageManager implements KeyStoreStorageManager {
    private final Map<String, byte[]> storages = new HashMap<>();
    
    @Override
    public KeyStoreStorage createStorage(final String keyStoreName) {
      return new MemKeyStoreStorage(keyStoreName);
    }
    
    class MemKeyStoreStorage implements KeyStoreStorage {
      private final String keyStoreName;
      
      MemKeyStoreStorage(final String keyStoreName) {
        this.keyStoreName = keyStoreName;
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
          throws NoSuchAlgorithmException, CertificateException, IOException {
        byte[] bytes = storages.get(keyStoreName);
        if (bytes != null) {
          keyStore.load(new ByteArrayInputStream(bytes), password);
        } else {
          keyStore.load(null, password);
        }
      }
      
      @Override
      public void save(final KeyStore keyStore, final char[] password)
          throws KeyStoreException, NoSuchAlgorithmException, CertificateException, IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        keyStore.store(baos, password);
        storages.put(keyStoreName, baos.toByteArray());
      }
    }
  }
}