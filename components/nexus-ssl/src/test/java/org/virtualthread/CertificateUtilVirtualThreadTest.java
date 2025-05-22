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
package org.virtualthread;

import java.security.Security;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.ssl.CertificateUtil;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests for {@link CertificateUtil} using Java 21 Virtual Threads to verify certificate operations
 * under the new concurrency model.
 */
@ExtendWith(MockitoExtension.class)
@org.junit.experimental.categories.Category(VirtualThreadTestGroup.class)
public class CertificateUtilVirtualThreadTest
    extends TestSupport
{
  private static final String NL = System.lineSeparator();

  // Need to use platform NL here for compatibility
  private final String CERT_IN_PEM =
      "-----BEGIN CERTIFICATE-----" + NL
          + "MIIByzCCAXUCBgE0OsUqMjANBgkqhkiG9w0BAQUFADBtMRYwFAYDVQQDEw10byBi" + NL
          + "ZSBjaGFuZ2VkMQ8wDQYDVQQLEwZjaGFuZ2UxDzANBgNVBAoTBmNoYW5nZTEPMA0G" + NL
          + "A1UEBxMGY2hhbmdlMQ8wDQYDVQQIEwZjaGFuZ2UxDzANBgNVBAYTBmNoYW5nZTAg" + NL
          + "Fw0xMTEyMTQwNDEyMDdaGA8yMTExMTEyMDA0MTIwN1owbTEWMBQGA1UEAxMNdG8g" + NL
          + "YmUgY2hhbmdlZDEPMA0GA1UECxMGY2hhbmdlMQ8wDQYDVQQKEwZjaGFuZ2UxDzAN" + NL
          + "BgNVBAcTBmNoYW5nZTEPMA0GA1UECBMGY2hhbmdlMQ8wDQYDVQQGEwZjaGFuZ2Uw" + NL
          + "XDANBgkqhkiG9w0BAQEFAANLADBIAkEAtyZDEbRZ9snDlCQbKerKAGGMHXIWF1t2" + NL
          + "6SBEAuC6krlujo5vMQsE/0Qp0jePjf9IKj8dR5RcXDKNi4mITY/Y4wIDAQABMA0G" + NL
          + "CSqGSIb3DQEBBQUAA0EAjX5DHXWkFxVWuvymp/2VUkcs8/PV1URpjpnVRL22GbXU" + NL
          + "UTlNxF8vcC+LMpLCaAk3OLezSwYkpptRFK/x3EWq7g==" + NL
          + "-----END CERTIFICATE-----";

  private static final String SHA_CERT_FINGERPRINT = "64:C4:44:A9:02:F7:F0:02:16:AA:C3:43:0B:BF:ED:44:C8:81:87:CD";

  @BeforeEach
  public void registerBouncyCastle() {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
  }

  /**
   * Tests that a Certificate can be decoded then serialized concurrently using Virtual Threads
   * and end up with the same result.
   */
  @Test
  public void testMarshalPEMFormatWithVirtualThreads() throws Exception {
    int threadCount = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    List<String> results = new ArrayList<>();

    // Create a Virtual Thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            Certificate certificate = CertificateUtil.decodePEMFormattedCertificate(CERT_IN_PEM);
            String serialized = CertificateUtil.serializeCertificateInPEM(certificate).trim();
            synchronized (results) {
              results.add(serialized);
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
            fail("Exception during certificate processing: " + e.getMessage());
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertThat("All tasks should complete within timeout", completed, equalTo(true));
      assertThat("No errors should occur during concurrent processing", errorCount.get(), equalTo(0));

      // Verify all results are correct
      for (String result : results) {
        assertThat(result, equalTo(CERT_IN_PEM));
      }
      
      // Verify we got the expected number of results
      assertEquals(threadCount, results.size(), "Should have processed all certificates");
    }
  }

  /**
   * Tests calculating a fingerprint for a Certificate concurrently using Virtual Threads.
   */
  @Test
  public void testCalculateFingerPrintWithVirtualThreads() throws Exception {
    int threadCount = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    List<String> fingerprints = new ArrayList<>();

    // Create a Virtual Thread executor using the factory approach
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())) {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            Certificate certificate = CertificateUtil.decodePEMFormattedCertificate(CERT_IN_PEM);
            String fingerprint = CertificateUtil.calculateFingerprint(certificate);
            synchronized (fingerprints) {
              fingerprints.add(fingerprint);
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
            fail("Exception during fingerprint calculation: " + e.getMessage());
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertThat("All tasks should complete within timeout", completed, equalTo(true));
      assertThat("No errors should occur during concurrent processing", errorCount.get(), equalTo(0));

      // Verify all fingerprints are correct
      for (String fingerprint : fingerprints) {
        assertThat(fingerprint, equalTo(SHA_CERT_FINGERPRINT));
      }
      
      // Verify we got the expected number of fingerprints
      assertEquals(threadCount, fingerprints.size(), "Should have calculated all fingerprints");
    }
  }

  /**
   * Tests mixed certificate operations (decoding, serialization, and fingerprinting)
   * concurrently using Virtual Threads.
   */
  @Test
  public void testMixedCertificateOperationsWithVirtualThreads() throws Exception {
    int threadCount = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger errorCount = new AtomicInteger(0);

    // Create a Virtual Thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < threadCount; i++) {
        final int taskId = i;
        executor.submit(() -> {
          try {
            Certificate certificate = CertificateUtil.decodePEMFormattedCertificate(CERT_IN_PEM);
            assertNotNull(certificate, "Certificate should be decoded successfully");
            
            // Alternate between serialization and fingerprinting
            if (taskId % 2 == 0) {
              String serialized = CertificateUtil.serializeCertificateInPEM(certificate).trim();
              assertThat(serialized, equalTo(CERT_IN_PEM));
            } else {
              String fingerprint = CertificateUtil.calculateFingerprint(certificate);
              assertThat(fingerprint, equalTo(SHA_CERT_FINGERPRINT));
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
            fail("Exception during certificate operation: " + e.getMessage());
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertThat("All tasks should complete within timeout", completed, equalTo(true));
      assertThat("No errors should occur during concurrent processing", errorCount.get(), equalTo(0));
    }
  }
}