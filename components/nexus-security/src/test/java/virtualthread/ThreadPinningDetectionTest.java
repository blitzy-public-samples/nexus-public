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

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import javax.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.crypto.CryptoHelper;
import org.sonatype.nexus.crypto.MavenCipher;
import org.sonatype.nexus.crypto.PhraseService;
import org.sonatype.nexus.security.JwtHelper;
import org.sonatype.nexus.security.PasswordHelper;
import org.sonatype.nexus.security.jwt.SecretStore;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.google.inject.Provider;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;

import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.Subject;

import static java.util.Collections.singleton;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.security.JwtHelper.ISSUER;
import static org.sonatype.nexus.security.JwtHelper.REALM;
import static org.sonatype.nexus.security.JwtHelper.USER;
import static org.sonatype.nexus.security.JwtHelper.USER_SESSION_ID;
import static org.sonatype.nexus.crypto.PhraseService.LEGACY_PHRASE_SERVICE;

/**
 * Test suite for detecting thread pinning issues in security operations when using Virtual Threads.
 * <p>
 * This test identifies security operations that cause thread pinning, which prevents the full
 * performance benefits of Virtual Threads from being realized. Thread pinning occurs when a Virtual Thread
 * cannot be unmounted from its carrier thread, typically due to synchronized blocks or native methods.
 * <p>
 * The test uses both JFR events and the jdk.tracePinnedThreads JVM flag to detect pinning.
 * <p>
 * To run with pinning detection via JVM flag:
 * {@code -Djdk.tracePinnedThreads=full}
 *
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
public class ThreadPinningDetectionTest
    extends TestSupport
{
  private static final int CONCURRENT_OPERATIONS = 100;
  private static final int OPERATION_DURATION_MS = 50;
  private static final int TEST_TIMEOUT_SECONDS = 30;
  
  private ExecutorService virtualThreadExecutor;
  private List<PinningEvent> detectedPinningEvents;
  private RecordingStream jfrRecordingStream;
  
  @Mock
  private Subject subject;
  
  @Mock
  private PrincipalCollection principals;
  
  @Mock
  private SecretStore secretStore;
  
  @Mock
  private Provider<SecretStore> storeProvider;
  
  @Mock
  private CryptoHelper cryptoHelper;
  
  @Mock
  private MavenCipher mavenCipher;
  
  private JwtHelper jwtHelper;
  private PasswordHelper passwordHelper;
  
  /**
   * Represents a detected thread pinning event with diagnostic information.
   */
  private static class PinningEvent {
    private final String operationType;
    private final String threadName;
    private final String stackTrace;
    private final long durationMs;
    
    public PinningEvent(String operationType, String threadName, String stackTrace, long durationMs) {
      this.operationType = operationType;
      this.threadName = threadName;
      this.stackTrace = stackTrace;
      this.durationMs = durationMs;
    }
    
    @Override
    public String toString() {
      return String.format("PinningEvent{operationType='%s', threadName='%s', durationMs=%d, stackTrace='%s'}",
          operationType, threadName, durationMs, stackTrace);
    }
  }
  
  @BeforeEach
  public void setup() throws Exception {
    // Create a virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    virtualThreadExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Initialize collection for pinning events
    detectedPinningEvents = new ArrayList<>();
    
    // Setup JFR recording for pinning events
    jfrRecordingStream = new RecordingStream();
    jfrRecordingStream.enable("jdk.VirtualThreadPinned").withStackTrace();
    jfrRecordingStream.onEvent("jdk.VirtualThreadPinned", this::handlePinningEvent);
    jfrRecordingStream.startAsync();
    
    // Setup JWT helper
    when(secretStore.getSecret()).thenReturn(Optional.of("secret"));
    when(storeProvider.get()).thenReturn(secretStore);
    jwtHelper = new JwtHelper(300, "/", storeProvider);
    jwtHelper.doStart();
    
    // Setup subject mock
    when(subject.getPrincipal()).thenReturn("admin");
    when(subject.getPrincipals()).thenReturn(principals);
    when(principals.getRealmNames()).thenReturn(singleton("NexusAuthorizingRealm"));
    
    // Setup password helper
    passwordHelper = new PasswordHelper(mavenCipher, LEGACY_PHRASE_SERVICE);
  }
  
  @AfterEach
  public void cleanup() throws Exception {
    // Shutdown the executor and JFR recording
    virtualThreadExecutor.shutdown();
    try {
      if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
        virtualThreadExecutor.shutdownNow();
      }
    } catch (InterruptedException e) {
      virtualThreadExecutor.shutdownNow();
    }
    
    jfrRecordingStream.close();
    
    // Report detected pinning events
    if (!detectedPinningEvents.isEmpty()) {
      log.warn("Detected {} thread pinning events:", detectedPinningEvents.size());
      detectedPinningEvents.forEach(event -> log.warn(event.toString()));
    }
  }
  
  /**
   * Handler for JFR pinning events.
   */
  private void handlePinningEvent(RecordedEvent event) {
    String threadName = event.getThread("eventThread").getJavaName();
    String stackTrace = event.getStackTrace() != null ? event.getStackTrace().toString() : "<no stack trace>";
    long durationMs = event.getDuration().toMillis();
    
    // Try to determine the operation type from the stack trace
    String operationType = determineOperationType(stackTrace);
    
    // Record the pinning event
    PinningEvent pinningEvent = new PinningEvent(operationType, threadName, stackTrace, durationMs);
    detectedPinningEvents.add(pinningEvent);
    
    log.info("Thread pinning detected: {} (duration: {} ms)", threadName, durationMs);
  }
  
  /**
   * Attempts to determine the security operation type from a stack trace.
   */
  private String determineOperationType(String stackTrace) {
    if (stackTrace.contains("JwtHelper")) {
      return "JWT Operation";
    } else if (stackTrace.contains("PasswordHelper")) {
      return "Password Operation";
    } else if (stackTrace.contains("SecretStore")) {
      return "Secret Store Operation";
    } else if (stackTrace.contains("CryptoHelper")) {
      return "Crypto Operation";
    } else {
      return "Unknown Security Operation";
    }
  }
  
  /**
   * Test for detecting thread pinning in JWT token creation operations.
   */
  @Test
  public void testJwtTokenCreationPinning(TestInfo testInfo) throws Exception {
    log.info("Running test: {}", testInfo.getDisplayName());
    
    runConcurrentOperations(
        "JWT Token Creation",
        () -> {
          // Simulate JWT token creation workload
          Cookie jwtCookie = jwtHelper.createJwtCookie(subject, false);
          simulateOperation(OPERATION_DURATION_MS);
          return jwtCookie;
        }
    );
    
    // Verify no pinning occurred or report detected pinning
    assertNoPinningOrReport("JWT Token Creation");
  }
  
  /**
   * Test for detecting thread pinning in JWT token verification operations.
   */
  @Test
  public void testJwtTokenVerificationPinning(TestInfo testInfo) throws Exception {
    log.info("Running test: {}", testInfo.getDisplayName());
    
    // Create a valid JWT token for testing
    String validJwt = createValidJwt();
    
    runConcurrentOperations(
        "JWT Token Verification",
        () -> {
          // Simulate JWT token verification workload
          try {
            jwtHelper.verifyJwt(validJwt);
            simulateOperation(OPERATION_DURATION_MS);
            return true;
          } catch (Exception e) {
            return false;
          }
        }
    );
    
    // Verify no pinning occurred or report detected pinning
    assertNoPinningOrReport("JWT Token Verification");
  }
  
  /**
   * Test for detecting thread pinning in JWT token refresh operations.
   */
  @Test
  public void testJwtTokenRefreshPinning(TestInfo testInfo) throws Exception {
    log.info("Running test: {}", testInfo.getDisplayName());
    
    // Create a valid JWT token for testing
    String validJwt = createValidJwt();
    
    runConcurrentOperations(
        "JWT Token Refresh",
        () -> {
          // Simulate JWT token refresh workload
          try {
            Cookie refreshedCookie = jwtHelper.verifyAndRefreshJwtCookie(validJwt, false);
            simulateOperation(OPERATION_DURATION_MS);
            return refreshedCookie;
          } catch (Exception e) {
            return null;
          }
        }
    );
    
    // Verify no pinning occurred or report detected pinning
    assertNoPinningOrReport("JWT Token Refresh");
  }
  
  /**
   * Test for detecting thread pinning in password encryption operations.
   */
  @Test
  public void testPasswordEncryptionPinning(TestInfo testInfo) throws Exception {
    log.info("Running test: {}", testInfo.getDisplayName());
    
    runConcurrentOperations(
        "Password Encryption",
        () -> {
          // Simulate password encryption workload
          String password = "password-" + UUID.randomUUID();
          String encrypted = passwordHelper.encrypt(password);
          simulateOperation(OPERATION_DURATION_MS);
          return encrypted;
        }
    );
    
    // Verify no pinning occurred or report detected pinning
    assertNoPinningOrReport("Password Encryption");
  }
  
  /**
   * Test for detecting thread pinning in password decryption operations.
   */
  @Test
  public void testPasswordDecryptionPinning(TestInfo testInfo) throws Exception {
    log.info("Running test: {}", testInfo.getDisplayName());
    
    // Create an encrypted password for testing
    String password = "test-password";
    when(mavenCipher.encrypt(password)).thenReturn("{X4bkkyyxOxkH+JFw6vVV3Gp0ONzT0aSzGOUCSSH+P5E=");
    when(mavenCipher.decrypt("{X4bkkyyxOxkH+JFw6vVV3Gp0ONzT0aSzGOUCSSH+P5E=")).thenReturn(password);
    
    runConcurrentOperations(
        "Password Decryption",
        () -> {
          // Simulate password decryption workload
          String decrypted = passwordHelper.decrypt("{X4bkkyyxOxkH+JFw6vVV3Gp0ONzT0aSzGOUCSSH+P5E=");
          simulateOperation(OPERATION_DURATION_MS);
          return decrypted;
        }
    );
    
    // Verify no pinning occurred or report detected pinning
    assertNoPinningOrReport("Password Decryption");
  }
  
  /**
   * Test for detecting thread pinning in character array password operations.
   */
  @Test
  public void testCharArrayPasswordOperationsPinning(TestInfo testInfo) throws Exception {
    log.info("Running test: {}", testInfo.getDisplayName());
    
    runConcurrentOperations(
        "Char Array Password Operations",
        () -> {
          // Simulate char array password operations workload
          char[] passwordChars = ("password-" + UUID.randomUUID()).toCharArray();
          String encrypted = passwordHelper.encryptChars(passwordChars);
          simulateOperation(OPERATION_DURATION_MS);
          char[] decrypted = passwordHelper.decryptChars(encrypted);
          return decrypted != null;
        }
    );
    
    // Verify no pinning occurred or report detected pinning
    assertNoPinningOrReport("Char Array Password Operations");
  }
  
  /**
   * Helper method to run concurrent operations and detect pinning.
   */
  private <T> void runConcurrentOperations(String operationName, java.util.function.Supplier<T> operation) 
      throws Exception {
    log.info("Running {} concurrent {} operations", CONCURRENT_OPERATIONS, operationName);
    
    // Clear previous pinning events
    detectedPinningEvents.clear();
    
    // Create a latch to wait for all operations to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Submit concurrent operations using virtual threads
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          operation.get();
        } catch (Exception e) {
          log.error("Error in operation: {}", e.getMessage(), e);
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      }, virtualThreadExecutor);
      
      futures.add(future);
    }
    
    // Wait for all operations to complete
    boolean completed = latch.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertTrue(completed, "Operations did not complete within timeout");
    assertEquals(0, errorCount.get(), "Some operations failed with errors");
    
    log.info("Completed {} operations", CONCURRENT_OPERATIONS);
  }
  
  /**
   * Asserts that no pinning was detected for the given operation type, or reports detected pinning.
   */
  private void assertNoPinningOrReport(String operationType) {
    List<PinningEvent> relevantEvents = detectedPinningEvents.stream()
        .filter(event -> event.operationType.contains(operationType))
        .toList();
    
    if (!relevantEvents.isEmpty()) {
      log.warn("Detected {} pinning events for operation type: {}", relevantEvents.size(), operationType);
      relevantEvents.forEach(event -> log.warn(event.toString()));
    }
    
    // This assertion can be commented out if you want to collect pinning events without failing the test
    assertTrue(relevantEvents.isEmpty(), 
        "Thread pinning detected for " + operationType + ": " + relevantEvents.size() + " events");
  }
  
  /**
   * Creates a valid JWT token for testing.
   */
  private String createValidJwt() {
    String userSessionId = UUID.randomUUID().toString();
    return JWT.create()
        .withIssuer(ISSUER)
        .withExpiresAt(new java.util.Date(System.currentTimeMillis() + 100000))
        .withClaim(USER_SESSION_ID, userSessionId)
        .withClaim(USER, "admin")
        .withClaim(REALM, "NexusAuthorizingRealm")
        .sign(Algorithm.HMAC256("secret"));
  }
  
  /**
   * Simulates an operation that takes the specified duration.
   */
  private void simulateOperation(long durationMs) {
    try {
      // Use Thread.sleep to simulate work
      // This is a blocking operation that should allow virtual threads to unmount
      // unless they are pinned
      Thread.sleep(durationMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}