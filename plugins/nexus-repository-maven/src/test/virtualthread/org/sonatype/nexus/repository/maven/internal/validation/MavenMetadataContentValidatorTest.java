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
import java.util.concurrent.TimeUnit;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.InvalidContentException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.apache.commons.io.IOUtils.toInputStream;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MavenMetadataContentValidator} using Virtual Threads.
 * 
 * @since 3.60
 */
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

  @Test
  public void throwInvalidContentWhenMetadataEmpty() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    final InvalidContentException[] exception = new InvalidContentException[1];
    
    Thread.ofVirtual().start(() -> {
      try {
        InputStream mavenMetadata = toInputStream("");
        underTest.validate(VALID_PATH, mavenMetadata);
      }
      catch (InvalidContentException e) {
        exception[0] = e;
      }
      finally {
        latch.countDown();
      }
    });
    
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    assertThrows(InvalidContentException.class, () -> {
      if (exception[0] != null) {
        throw exception[0];
      }
    });
  }

  @Test
  public void throwInvalidContentWhenMetadataNotMetadata() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    final InvalidContentException[] exception = new InvalidContentException[1];
    
    Thread.ofVirtual().start(() -> {
      try {
        InputStream mavenMetadata = toInputStream("This is not metadata");
        underTest.validate(VALID_PATH, mavenMetadata);
      }
      catch (InvalidContentException e) {
        exception[0] = e;
      }
      finally {
        latch.countDown();
      }
    });
    
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    assertThrows(InvalidContentException.class, () -> {
      if (exception[0] != null) {
        throw exception[0];
      }
    });
  }

  @Test
  public void throwInvalidContentWhenMetadataDoesNotMatchPath() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    final InvalidContentException[] exception = new InvalidContentException[1];
    
    Thread.ofVirtual().start(() -> {
      try {
        InputStream mavenMetadata = toInputStream("<metadata>\n" +
            "  <groupId>group</groupId>\n" +
            "  <artifactId>artifact</artifactId>\n" +
            "</metadata>\n");
        underTest.validate(INVALID_PATH, mavenMetadata);
      }
      catch (InvalidContentException e) {
        exception[0] = e;
      }
      finally {
        latch.countDown();
      }
    });
    
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    assertThrows(InvalidContentException.class, () -> {
      if (exception[0] != null) {
        throw exception[0];
      }
    });
  }

  @Test
  public void doNotValidateWhenGroupNotFound() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    final Exception[] exception = new Exception[1];
    
    Thread.ofVirtual().start(() -> {
      try {
        InputStream mavenMetadata = toInputStream("<metadata>\n" +
            "  <artifactId>artifact</artifactId>\n" +
            "</metadata>\n");
        underTest.validate(INVALID_PATH, mavenMetadata);
      }
      catch (Exception e) {
        exception[0] = e;
      }
      finally {
        latch.countDown();
      }
    });
    
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    assertDoesNotThrow(() -> {
      if (exception[0] != null) {
        throw exception[0];
      }
    });
  }

  @Test
  public void doNotValidateWhenGroupEmpty() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    final Exception[] exception = new Exception[1];
    
    Thread.ofVirtual().start(() -> {
      try {
        InputStream mavenMetadata = toInputStream("<metadata>\n" +
            "  <groupId></groupId>\n" +
            "  <artifactId>artifact</artifactId>\n" +
            "</metadata>\n");
        underTest.validate(INVALID_PATH, mavenMetadata);
      }
      catch (Exception e) {
        exception[0] = e;
      }
      finally {
        latch.countDown();
      }
    });
    
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    assertDoesNotThrow(() -> {
      if (exception[0] != null) {
        throw exception[0];
      }
    });
  }

  @Test
  public void throwInvalidContentWhenArtifactNotFound() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    final InvalidContentException[] exception = new InvalidContentException[1];
    
    Thread.ofVirtual().start(() -> {
      try {
        InputStream mavenMetadata = toInputStream("<metadata>\n" +
            "  <groupId>group</groupId>\n" +
            "</metadata>\n");
        underTest.validate(VALID_PATH, mavenMetadata);
      }
      catch (InvalidContentException e) {
        exception[0] = e;
      }
      finally {
        latch.countDown();
      }
    });
    
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    assertThrows(InvalidContentException.class, () -> {
      if (exception[0] != null) {
        throw exception[0];
      }
    });
  }
  
  @Test
  public void throwInvalidContentWhenArtifactEmpty() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    final InvalidContentException[] exception = new InvalidContentException[1];
    
    Thread.ofVirtual().start(() -> {
      try {
        InputStream mavenMetadata = toInputStream("<metadata>\n" +
            "  <groupId>group</groupId>\n" +
            "  <artifactId></artifactId>\n" +
            "</metadata>\n");
        underTest.validate(VALID_PATH, mavenMetadata);
      }
      catch (InvalidContentException e) {
        exception[0] = e;
      }
      finally {
        latch.countDown();
      }
    });
    
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    assertThrows(InvalidContentException.class, () -> {
      if (exception[0] != null) {
        throw exception[0];
      }
    });
  }

  @Test
  public void noExceptionWhenValidContentAndMatchesPath() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    final Exception[] exception = new Exception[1];
    
    Thread.ofVirtual().start(() -> {
      try {
        InputStream mavenMetadata = toInputStream("<metadata>\n" +
            "  <groupId>group</groupId>\n" +
            "  <artifactId>artifact</artifactId>\n" +
            "</metadata>\n");
        underTest.validate(VALID_PATH, mavenMetadata);
      }
      catch (Exception e) {
        exception[0] = e;
      }
      finally {
        latch.countDown();
      }
    });
    
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    assertDoesNotThrow(() -> {
      if (exception[0] != null) {
        throw exception[0];
      }
    });
  }

  @Test
  public void noExceptionWhenValidContentAndMatchesPathForSnapshotMetadata() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    final Exception[] exception = new Exception[1];
    
    Thread.ofVirtual().start(() -> {
      try {
        InputStream mavenMetadata = toInputStream("<metadata>\n" +
            "  <groupId>group</groupId>\n" +
            "  <artifactId>artifact</artifactId>\n" +
            "  <version>1.0-SNAPSHOT</version>\n" +
            "</metadata>\n");
        underTest.validate(VALID_SNAPSHOT_PATH, mavenMetadata);
      }
      catch (Exception e) {
        exception[0] = e;
      }
      finally {
        latch.countDown();
      }
    });
    
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    assertDoesNotThrow(() -> {
      if (exception[0] != null) {
        throw exception[0];
      }
    });
  }

  @Test
  public void noExceptionWhenValidContentAndMatchesPathWithReleasedVersion() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    final Exception[] exception = new Exception[1];
    
    Thread.ofVirtual().start(() -> {
      try {
        InputStream mavenMetadata = toInputStream("<metadata>\n" +
            "  <groupId>group</groupId>\n" +
            "  <artifactId>artifact</artifactId>\n" +
            "  <version>1.0</version>\n" +
            "</metadata>\n");
        underTest.validate(VALID_PATH, mavenMetadata);
      }
      catch (Exception e) {
        exception[0] = e;
      }
      finally {
        latch.countDown();
      }
    });
    
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    assertDoesNotThrow(() -> {
      if (exception[0] != null) {
        throw exception[0];
      }
    });
  }

  @Test
  public void noExceptionWhenGroupOnlyWithCorrectPath() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    final Exception[] exception = new Exception[1];
    
    Thread.ofVirtual().start(() -> {
      try {
        InputStream mavenMetadata = toInputStream("<metadata>\n" +
            "  <groupId>group</groupId>\n" +
            "</metadata>\n");
        underTest.validate(VALID_PATH_GROUP_ONLY, mavenMetadata);
      }
      catch (Exception e) {
        exception[0] = e;
      }
      finally {
        latch.countDown();
      }
    });
    
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    assertDoesNotThrow(() -> {
      if (exception[0] != null) {
        throw exception[0];
      }
    });
  }
  
  @Test
  public void concurrentValidationWithMultipleVirtualThreads() throws Exception {
    int threadCount = 10;
    CountDownLatch latch = new CountDownLatch(threadCount);
    final Exception[] exceptions = new Exception[threadCount];
    
    // Create valid metadata content
    String validMetadata = "<metadata>\n" +
        "  <groupId>group</groupId>\n" +
        "  <artifactId>artifact</artifactId>\n" +
        "</metadata>\n";
    
    // Start multiple virtual threads to validate the same content concurrently
    for (int i = 0; i < threadCount; i++) {
      final int index = i;
      Thread.ofVirtual().start(() -> {
        try {
          InputStream mavenMetadata = toInputStream(validMetadata);
          underTest.validate(VALID_PATH, mavenMetadata);
        }
        catch (Exception e) {
          exceptions[index] = e;
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertTrue(latch.await(10, TimeUnit.SECONDS));
    
    // Verify no exceptions were thrown in any thread
    for (int i = 0; i < threadCount; i++) {
      final int index = i;
      assertDoesNotThrow(() -> {
        if (exceptions[index] != null) {
          throw exceptions[index];
        }
      }, "Exception in virtual thread " + i);
    }
  }
}