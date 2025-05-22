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
package org.sonatype.nexus.repository.maven.internal;

import java.io.ByteArrayInputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.io.InputStreamSupplier;
import org.sonatype.nexus.mime.MimeRulesSource;
import org.sonatype.nexus.repository.mime.DefaultContentValidator;

import org.apache.http.entity.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.verification.VerificationMode;

import static java.util.Optional.ofNullable;
import static org.apache.http.entity.ContentType.TEXT_PLAIN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MavenContentValidator} using Java 21 Virtual Threads to verify thread-safety
 * and correct behavior under concurrent access.
 */
@Tag("VirtualThread")
public class MavenContentValidatorVirtualThreadTest
    extends TestSupport
{
  private static final InputStreamSupplier DEFAULT_SUPPLIER = () -> new ByteArrayInputStream("0xDEADBEEF".getBytes());

  // "caff" as a header would normally be detected as 'audio/x-caf'
  private static final InputStreamSupplier AUDIO_CAF_SUPPLIER = () -> new ByteArrayInputStream("caff123456789".getBytes());

  private static final InputStreamSupplier MD5_SUPPLIER = () -> new ByteArrayInputStream("0161dba22520b2c13b50493fd98ed4ce".getBytes());

  private static final InputStreamSupplier SHA1_SUPPLIER = () -> new ByteArrayInputStream("2abe58492ad5e25e18cfca3fad4f0322d47cb893".getBytes());

  private static final InputStreamSupplier SHA256_SUPPLIER = () -> new ByteArrayInputStream("34cbb64305cb610b642162b052e66b4683ae68fd20d34591c21ab68bec106ccb".getBytes());

  private static final InputStreamSupplier SHA512_SUPPLIER = () -> new ByteArrayInputStream("bfe3bcd9fc7180c2439d7c0b3b3036f71a6da1fed2983e3ab23185bf3a6877f6a32dbd6b949d7ef3ab1935699a113f47987082fbaffb2ce9f65f5ad058475c0e".getBytes());

  // Number of virtual threads to use for concurrent testing
  private static final int VIRTUAL_THREAD_COUNT = 100;
  
  // Timeout for concurrent tests in seconds
  private static final int CONCURRENT_TEST_TIMEOUT = 10;

  /**
   * Provides test parameters for parameterized tests.
   */
  static Stream<Arguments> testParameters() {
    return Stream.of(
        Arguments.of(null, true, TEXT_PLAIN, DEFAULT_SUPPLIER, times(1), TEXT_PLAIN, null),
        Arguments.of("file.jar", true, TEXT_PLAIN, DEFAULT_SUPPLIER, times(1), TEXT_PLAIN, null),
        Arguments.of("file.pom", true, TEXT_PLAIN, DEFAULT_SUPPLIER, times(1), TEXT_PLAIN, null),

        Arguments.of("file.md5", false, TEXT_PLAIN, MD5_SUPPLIER, never(), TEXT_PLAIN, null),
        Arguments.of("file.md5", true, TEXT_PLAIN, MD5_SUPPLIER, never(), TEXT_PLAIN, null),
        Arguments.of("file.md5", true, TEXT_PLAIN, DEFAULT_SUPPLIER, never(), TEXT_PLAIN, "Not a Maven2 digest: file.md5"),
        Arguments.of("file.md5", false, null, MD5_SUPPLIER, times(1), TEXT_PLAIN, null),
        Arguments.of("file.md5", false, TEXT_PLAIN, AUDIO_CAF_SUPPLIER, never(), TEXT_PLAIN, null),

        Arguments.of("file.sha1", false, TEXT_PLAIN, SHA1_SUPPLIER, never(), TEXT_PLAIN, null),
        Arguments.of("file.sha1", true, TEXT_PLAIN, SHA1_SUPPLIER, never(), TEXT_PLAIN, null),
        Arguments.of("file.sha1", true, TEXT_PLAIN, DEFAULT_SUPPLIER, never(), TEXT_PLAIN, "Not a Maven2 digest: file.sha1"),
        Arguments.of("file.sha1", false, null, SHA1_SUPPLIER, times(1), TEXT_PLAIN, null),
        Arguments.of("file.sha1", false, TEXT_PLAIN, AUDIO_CAF_SUPPLIER, never(), TEXT_PLAIN, null),

        Arguments.of("file.sha256", false, TEXT_PLAIN, SHA256_SUPPLIER, never(), TEXT_PLAIN, null),
        Arguments.of("file.sha256", true, TEXT_PLAIN, SHA256_SUPPLIER, never(), TEXT_PLAIN, null),
        Arguments.of("file.sha256", true, TEXT_PLAIN, DEFAULT_SUPPLIER, never(), TEXT_PLAIN, "Not a Maven2 digest: file.sha256"),
        Arguments.of("file.sha256", false, null, SHA256_SUPPLIER, times(1), TEXT_PLAIN, null),
        Arguments.of("file.sha256", false, TEXT_PLAIN, AUDIO_CAF_SUPPLIER, never(), TEXT_PLAIN, null),

        Arguments.of("file.sha512", false, TEXT_PLAIN, SHA512_SUPPLIER, never(), TEXT_PLAIN, null),
        Arguments.of("file.sha512", true, TEXT_PLAIN, SHA512_SUPPLIER, never(), TEXT_PLAIN, null),
        Arguments.of("file.sha512", true, TEXT_PLAIN, DEFAULT_SUPPLIER, never(), TEXT_PLAIN, "Not a Maven2 digest: file.sha512"),
        Arguments.of("file.sha512", false, null, SHA512_SUPPLIER, times(1), TEXT_PLAIN, null),
        Arguments.of("file.sha512", false, TEXT_PLAIN, AUDIO_CAF_SUPPLIER, never(), TEXT_PLAIN, null)
    );
  }

  @Mock
  private MimeRulesSource mimeRulesSource;

  @Mock
  private DefaultContentValidator defaultContentValidator;

  private MavenContentValidator underTest;

  @BeforeEach
  public void setUp() {
    underTest = new MavenContentValidator(defaultContentValidator);

    when(defaultContentValidator.determineContentType(any(Boolean.class),
        any(InputStreamSupplier.class),
        any(MimeRulesSource.class),
        any(),
        any()))
        .thenReturn(TEXT_PLAIN.getMimeType());
  }

  /**
   * Tests content type determination with various parameters using JUnit 5 parameterized tests.
   */
  @ParameterizedTest
  @MethodSource("testParameters")
  public void determineContentType(
      @Nullable String contentName,
      boolean isStrictContentValidation,
      @Nullable ContentType declaredContentType,
      InputStreamSupplier contentSupplier,
      VerificationMode defaultContentValidatorInvocation,
      ContentType expectedContentType,
      @Nullable String expectedExceptionMessage) throws Exception
  {
    String declaredMimeType = ofNullable(declaredContentType).map(ContentType::getMimeType).orElse(null);

    when(defaultContentValidator.determineContentType(eq(isStrictContentValidation),
        eq(contentSupplier),
        eq(mimeRulesSource),
        any(),
        eq(declaredMimeType)
    )).thenReturn(TEXT_PLAIN.getMimeType());

    if (expectedExceptionMessage != null) {
      Exception exception = assertThrows(Exception.class, () -> {
        underTest.determineContentType(
            isStrictContentValidation,
            contentSupplier,
            mimeRulesSource,
            contentName,
            declaredMimeType);
      });
      assertEquals(expectedExceptionMessage, exception.getMessage());
    } else {
      String result = underTest.determineContentType(
          isStrictContentValidation,
          contentSupplier,
          mimeRulesSource,
          contentName,
          declaredMimeType);
      assertEquals(expectedContentType.getMimeType(), result);

      String contentNameForDefaultValidator = ("file.pom".equalsIgnoreCase(contentName)) ? "file.pom.xml" : contentName;

      verify(defaultContentValidator, defaultContentValidatorInvocation)
          .determineContentType(
              isStrictContentValidation,
              contentSupplier,
              mimeRulesSource,
              contentNameForDefaultValidator,
              declaredMimeType);
    }
  }

  /**
   * Tests concurrent content validation using Virtual Threads.
   * This test verifies that the MavenContentValidator behaves correctly under concurrent access.
   */
  @Test
  public void testConcurrentContentValidation() throws Exception {
    // Setup a countdown latch to coordinate virtual threads
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
    
    // Create virtual threads to perform concurrent content validation
    for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
      final int threadNum = i;
      Thread.ofVirtual().name("virtual-thread-" + threadNum).start(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Determine content type for a POM file
          String result = underTest.determineContentType(
              true,
              DEFAULT_SUPPLIER,
              mimeRulesSource,
              "file.pom",
              TEXT_PLAIN.getMimeType());
          
          assertEquals(TEXT_PLAIN.getMimeType(), result);
        } catch (Exception e) {
          log.error("Error in virtual thread {}", threadNum, e);
        } finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(CONCURRENT_TEST_TIMEOUT, TimeUnit.SECONDS);
    assertEquals(true, completed, "Not all virtual threads completed in time");
  }

  /**
   * Tests concurrent validation of digest files using Virtual Threads.
   * This test verifies that digest validation works correctly under concurrent access.
   */
  @Test
  public void testConcurrentDigestValidation() throws Exception {
    // Create an executor service with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch completionLatch = new CountDownLatch(VIRTUAL_THREAD_COUNT * 4); // 4 digest types
      
      // Test all digest types concurrently
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        // MD5 digest
        executor.submit(() -> {
          try {
            String result = underTest.determineContentType(
                false,
                MD5_SUPPLIER,
                mimeRulesSource,
                "file.md5",
                TEXT_PLAIN.getMimeType());
            assertEquals(TEXT_PLAIN.getMimeType(), result);
          } finally {
            completionLatch.countDown();
          }
        });
        
        // SHA1 digest
        executor.submit(() -> {
          try {
            String result = underTest.determineContentType(
                false,
                SHA1_SUPPLIER,
                mimeRulesSource,
                "file.sha1",
                TEXT_PLAIN.getMimeType());
            assertEquals(TEXT_PLAIN.getMimeType(), result);
          } finally {
            completionLatch.countDown();
          }
        });
        
        // SHA256 digest
        executor.submit(() -> {
          try {
            String result = underTest.determineContentType(
                false,
                SHA256_SUPPLIER,
                mimeRulesSource,
                "file.sha256",
                TEXT_PLAIN.getMimeType());
            assertEquals(TEXT_PLAIN.getMimeType(), result);
          } finally {
            completionLatch.countDown();
          }
        });
        
        // SHA512 digest
        executor.submit(() -> {
          try {
            String result = underTest.determineContentType(
                false,
                SHA512_SUPPLIER,
                mimeRulesSource,
                "file.sha512",
                TEXT_PLAIN.getMimeType());
            assertEquals(TEXT_PLAIN.getMimeType(), result);
          } finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = completionLatch.await(CONCURRENT_TEST_TIMEOUT, TimeUnit.SECONDS);
      assertEquals(true, completed, "Not all digest validation tasks completed in time");
    }
  }

  /**
   * Tests concurrent validation with invalid digest content using Virtual Threads.
   * This test verifies that error handling works correctly under concurrent access.
   */
  @Test
  public void testConcurrentInvalidDigestValidation() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch completionLatch = new CountDownLatch(VIRTUAL_THREAD_COUNT * 4); // 4 digest types
      
      // Test all digest types with invalid content concurrently
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        // Invalid MD5 digest
        executor.submit(() -> {
          try {
            Exception exception = assertThrows(Exception.class, () -> {
              underTest.determineContentType(
                  true,
                  DEFAULT_SUPPLIER,
                  mimeRulesSource,
                  "file.md5",
                  TEXT_PLAIN.getMimeType());
            });
            assertEquals("Not a Maven2 digest: file.md5", exception.getMessage());
          } finally {
            completionLatch.countDown();
          }
        });
        
        // Invalid SHA1 digest
        executor.submit(() -> {
          try {
            Exception exception = assertThrows(Exception.class, () -> {
              underTest.determineContentType(
                  true,
                  DEFAULT_SUPPLIER,
                  mimeRulesSource,
                  "file.sha1",
                  TEXT_PLAIN.getMimeType());
            });
            assertEquals("Not a Maven2 digest: file.sha1", exception.getMessage());
          } finally {
            completionLatch.countDown();
          }
        });
        
        // Invalid SHA256 digest
        executor.submit(() -> {
          try {
            Exception exception = assertThrows(Exception.class, () -> {
              underTest.determineContentType(
                  true,
                  DEFAULT_SUPPLIER,
                  mimeRulesSource,
                  "file.sha256",
                  TEXT_PLAIN.getMimeType());
            });
            assertEquals("Not a Maven2 digest: file.sha256", exception.getMessage());
          } finally {
            completionLatch.countDown();
          }
        });
        
        // Invalid SHA512 digest
        executor.submit(() -> {
          try {
            Exception exception = assertThrows(Exception.class, () -> {
              underTest.determineContentType(
                  true,
                  DEFAULT_SUPPLIER,
                  mimeRulesSource,
                  "file.sha512",
                  TEXT_PLAIN.getMimeType());
            });
            assertEquals("Not a Maven2 digest: file.sha512", exception.getMessage());
          } finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = completionLatch.await(CONCURRENT_TEST_TIMEOUT, TimeUnit.SECONDS);
      assertEquals(true, completed, "Not all invalid digest validation tasks completed in time");
    }
  }

  /**
   * Tests concurrent POM file renaming using Virtual Threads.
   * This test verifies that POM file renaming works correctly under concurrent access.
   */
  @Test
  public void testConcurrentPomFileRenaming() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch completionLatch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
      
      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        executor.submit(() -> {
          try {
            String result = underTest.determineContentType(
                true,
                DEFAULT_SUPPLIER,
                mimeRulesSource,
                "file.pom",
                TEXT_PLAIN.getMimeType());
            assertEquals(TEXT_PLAIN.getMimeType(), result);
            
            // Verify that the POM file was renamed to .pom.xml for the default validator
            verify(defaultContentValidator, times(1))
                .determineContentType(
                    eq(true),
                    eq(DEFAULT_SUPPLIER),
                    eq(mimeRulesSource),
                    eq("file.pom.xml"),
                    eq(TEXT_PLAIN.getMimeType()));
          } finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = completionLatch.await(CONCURRENT_TEST_TIMEOUT, TimeUnit.SECONDS);
      assertEquals(true, completed, "Not all POM file renaming tasks completed in time");
    }
  }
}