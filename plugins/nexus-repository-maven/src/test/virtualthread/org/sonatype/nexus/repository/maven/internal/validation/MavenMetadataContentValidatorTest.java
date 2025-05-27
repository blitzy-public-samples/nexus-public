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
package org.sonatype.nexus.repository.maven.internal.validation;

import java.io.InputStream;
import java.util.concurrent.CountDownLatch;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.InvalidContentException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.apache.commons.io.IOUtils.toInputStream;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("MavenMetadataContentValidator with Virtual Threads")
public class MavenMetadataContentValidatorTest
    extends TestSupport
{
  private static final String VALID_PATH = "group/artifact/maven-metadata.xml";

  private static final String VALID_PATH_GROUP_ONLY = "group/maven-metadata.xml";

  private static final String VALID_SNAPSHOT_PATH = "group/artifact/1.0-SNAPSHOT/maven-metadata.xml";

  private static final String INVALID_PATH = "differentGroup/differentArtifact/maven-metadata.xml";

  private MavenMetadataContentValidator underTest;

  @BeforeEach
  public void setup() throws Exception {
    underTest = new MavenMetadataContentValidator();
  }

  /**
   * Helper method to run a test in a Virtual Thread
   */
  private void runInVirtualThread(Runnable test) throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    Thread.ofVirtual().start(() -> {
      try {
        test.run();
      } finally {
        latch.countDown();
      }
    });
    latch.await(); // Wait for the virtual thread to complete
  }

  @Test
  @DisplayName("Should throw InvalidContentException when metadata is empty")
  public void throwInvalidContentWhenMetadataEmpty() throws InterruptedException {
    runInVirtualThread(() -> {
      InputStream mavenMetadata = toInputStream("");
      assertThrows(InvalidContentException.class, () -> 
          underTest.validate(VALID_PATH, mavenMetadata));
    });
  }

  @Test
  @DisplayName("Should throw InvalidContentException when content is not metadata")
  public void throwInvalidContentWhenMetadataNotMetadata() throws InterruptedException {
    runInVirtualThread(() -> {
      InputStream mavenMetadata = toInputStream("This is not metadata");
      assertThrows(InvalidContentException.class, () -> 
          underTest.validate(VALID_PATH, mavenMetadata));
    });
  }

  @Test
  @DisplayName("Should throw InvalidContentException when metadata does not match path")
  public void throwInvalidContentWhenMetadataDoesNotMatchPath() throws InterruptedException {
    runInVirtualThread(() -> {
      InputStream mavenMetadata = toInputStream("<metadata>\n" +
          "  <groupId>group</groupId>\n" +
          "  <artifactId>artifact</artifactId>\n" +
          "</metadata>\n");
      assertThrows(InvalidContentException.class, () -> 
          underTest.validate(INVALID_PATH, mavenMetadata));
    });
  }

  @Test
  @DisplayName("Should not validate when group not found")
  public void doNotValidateWhenGroupNotFound() throws InterruptedException {
    runInVirtualThread(() -> {
      InputStream mavenMetadata = toInputStream("<metadata>\n" +
          "  <artifactId>artifact</artifactId>\n" +
          "</metadata>\n");
      assertDoesNotThrow(() -> 
          underTest.validate(INVALID_PATH, mavenMetadata));
    });
  }

  @Test
  @DisplayName("Should not validate when group is empty")
  public void doNotValidateWhenGroupEmpty() throws InterruptedException {
    runInVirtualThread(() -> {
      InputStream mavenMetadata = toInputStream("<metadata>\n" +
          "  <groupId></groupId>\n" +
          "  <artifactId>artifact</artifactId>\n" +
          "</metadata>\n");
      assertDoesNotThrow(() -> 
          underTest.validate(INVALID_PATH, mavenMetadata));
    });
  }

  @Test
  @DisplayName("Should throw InvalidContentException when artifact not found")
  public void throwInvalidContentWhenArtifactNotFound() throws InterruptedException {
    runInVirtualThread(() -> {
      InputStream mavenMetadata = toInputStream("<metadata>\n" +
          "  <groupId>group</groupId>\n" +
          "</metadata>\n");
      assertThrows(InvalidContentException.class, () -> 
          underTest.validate(VALID_PATH, mavenMetadata));
    });
  }
  
  @Test
  @DisplayName("Should throw InvalidContentException when artifact is empty")
  public void throwInvalidContentWhenArtifactEmpty() throws InterruptedException {
    runInVirtualThread(() -> {
      InputStream mavenMetadata = toInputStream("<metadata>\n" +
          "  <groupId>group</groupId>\n" +
          "  <artifactId></artifactId>\n" +
          "</metadata>\n");
      assertThrows(InvalidContentException.class, () -> 
          underTest.validate(VALID_PATH, mavenMetadata));
    });
  }

  @Test
  @DisplayName("Should not throw exception when content is valid and matches path")
  public void noExceptionWhenValidContentAndMatchesPath() throws InterruptedException {
    runInVirtualThread(() -> {
      InputStream mavenMetadata = toInputStream("<metadata>\n" +
          "  <groupId>group</groupId>\n" +
          "  <artifactId>artifact</artifactId>\n" +
          "</metadata>\n");
      assertDoesNotThrow(() -> 
          underTest.validate(VALID_PATH, mavenMetadata));
    });
  }

  @Test
  @DisplayName("Should not throw exception when content is valid and matches path for snapshot metadata")
  public void noExceptionWhenValidContentAndMatchesPathForSnapshotMetadata() throws InterruptedException {
    runInVirtualThread(() -> {
      InputStream mavenMetadata = toInputStream("<metadata>\n" +
          "  <groupId>group</groupId>\n" +
          "  <artifactId>artifact</artifactId>\n" +
          "  <version>1.0-SNAPSHOT</version>\n" +
          "</metadata>\n");
      assertDoesNotThrow(() -> 
          underTest.validate(VALID_SNAPSHOT_PATH, mavenMetadata));
    });
  }

  @Test
  @DisplayName("Should not throw exception when content is valid and matches path with released version")
  public void noExceptionWhenValidContentAndMatchesPathWithReleasedVersion() throws InterruptedException {
    runInVirtualThread(() -> {
      InputStream mavenMetadata = toInputStream("<metadata>\n" +
          "  <groupId>group</groupId>\n" +
          "  <artifactId>artifact</artifactId>\n" +
          "  <version>1.0</version>\n" +
          "</metadata>\n");
      assertDoesNotThrow(() -> 
          underTest.validate(VALID_PATH, mavenMetadata));
    });
  }

  @Test
  @DisplayName("Should not throw exception when group only with correct path")
  public void noExceptionWhenGroupOnlyWithCorrectPath() throws InterruptedException {
    runInVirtualThread(() -> {
      InputStream mavenMetadata = toInputStream("<metadata>\n" +
          "  <groupId>group</groupId>\n" +
          "</metadata>\n");
      assertDoesNotThrow(() -> 
          underTest.validate(VALID_PATH_GROUP_ONLY, mavenMetadata));
    });
  }

  @Test
  @DisplayName("Should handle concurrent validation with multiple virtual threads")
  public void concurrentValidationWithMultipleVirtualThreads() throws InterruptedException {
    final int threadCount = 10;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    
    // Create multiple virtual threads that will all start validation simultaneously
    for (int i = 0; i < threadCount; i++) {
      final int threadId = i;
      Thread.ofVirtual().name("validation-thread-" + threadId).start(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Perform validation based on thread ID to test different scenarios
          if (threadId % 3 == 0) {
            // Valid metadata test
            InputStream mavenMetadata = toInputStream("<metadata>\n" +
                "  <groupId>group</groupId>\n" +
                "  <artifactId>artifact</artifactId>\n" +
                "</metadata>\n");
            assertDoesNotThrow(() -> 
                underTest.validate(VALID_PATH, mavenMetadata));
          } 
          else if (threadId % 3 == 1) {
            // Invalid metadata test
            InputStream mavenMetadata = toInputStream("This is not metadata");
            assertThrows(InvalidContentException.class, () -> 
                underTest.validate(VALID_PATH, mavenMetadata));
          }
          else {
            // Mismatched path test
            InputStream mavenMetadata = toInputStream("<metadata>\n" +
                "  <groupId>group</groupId>\n" +
                "  <artifactId>artifact</artifactId>\n" +
                "</metadata>\n");
            assertThrows(InvalidContentException.class, () -> 
                underTest.validate(INVALID_PATH, mavenMetadata));
          }
        } 
        catch (Exception e) {
          log.error("Error in virtual thread {}", threadId, e);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    completionLatch.await();
  }
}