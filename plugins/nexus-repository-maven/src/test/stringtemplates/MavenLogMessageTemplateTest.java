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

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.log.LoggingMessage;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.maven.MavenPath.Coordinates;
import org.sonatype.nexus.testsuite.testsupport.Java21TestGroup;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for validating Java 21 String Templates usage in Maven repository log messages.
 * 
 * @since 3.60
 */
@Category(Java21TestGroup.class)
public class MavenLogMessageTemplateTest
    extends TestSupport
{
  private static final String REPOSITORY_NAME = "maven-central";
  private static final String GROUP_ID = "org.sonatype.nexus";
  private static final String ARTIFACT_ID = "nexus-repository-maven";
  private static final String VERSION = "3.60.0-SNAPSHOT";
  private static final String EXTENSION = "jar";
  private static final String CLASSIFIER = "tests";
  
  private MavenPath mavenPath;
  private Coordinates coordinates;
  
  @Before
  public void setup() {
    coordinates = mock(Coordinates.class);
    when(coordinates.getGroupId()).thenReturn(GROUP_ID);
    when(coordinates.getArtifactId()).thenReturn(ARTIFACT_ID);
    when(coordinates.getVersion()).thenReturn(VERSION);
    when(coordinates.getBaseVersion()).thenReturn(VERSION);
    when(coordinates.getExtension()).thenReturn(EXTENSION);
    when(coordinates.getClassifier()).thenReturn(CLASSIFIER);
    
    mavenPath = mock(MavenPath.class);
    when(mavenPath.getPath()).thenReturn(
        String.format("%s/%s/%s/%s-%s-%s.%s", 
            GROUP_ID.replace('.', '/'), 
            ARTIFACT_ID, 
            VERSION, 
            ARTIFACT_ID, 
            VERSION, 
            CLASSIFIER, 
            EXTENSION));
    when(mavenPath.getCoordinates()).thenReturn(coordinates);
  }
  
  @Test
  public void testArtifactUploadLogMessage() {
    // Test the String Template format for artifact upload log messages
    String logMessage = STR."Artifact \{ARTIFACT_ID} version \{VERSION} uploaded to repository \{REPOSITORY_NAME}";
    
    assertThat(logMessage, notNullValue());
    assertThat(logMessage, containsString(ARTIFACT_ID));
    assertThat(logMessage, containsString(VERSION));
    assertThat(logMessage, containsString(REPOSITORY_NAME));
    assertThat(logMessage, is("Artifact nexus-repository-maven version 3.60.0-SNAPSHOT uploaded to repository maven-central"));
  }
  
  @Test
  public void testArtifactDownloadLogMessage() {
    // Test the String Template format for artifact download log messages
    String logMessage = STR."Artifact \{coordinates.getGroupId()}:\{coordinates.getArtifactId()}:\{coordinates.getVersion()} downloaded from repository \{REPOSITORY_NAME}";
    
    assertThat(logMessage, notNullValue());
    assertThat(logMessage, containsString(GROUP_ID));
    assertThat(logMessage, containsString(ARTIFACT_ID));
    assertThat(logMessage, containsString(VERSION));
    assertThat(logMessage, containsString(REPOSITORY_NAME));
    assertThat(logMessage, is("Artifact org.sonatype.nexus:nexus-repository-maven:3.60.0-SNAPSHOT downloaded from repository maven-central"));
  }
  
  @Test
  public void testMetadataUpdateLogMessage() {
    // Test the String Template format for metadata update log messages
    String groupMetadataPath = GROUP_ID.replace('.', '/') + "/maven-metadata.xml";
    String logMessage = STR."Updated metadata at \{groupMetadataPath} in repository \{REPOSITORY_NAME}";
    
    assertThat(logMessage, notNullValue());
    assertThat(logMessage, containsString(groupMetadataPath));
    assertThat(logMessage, containsString(REPOSITORY_NAME));
    assertThat(logMessage, is("Updated metadata at org/sonatype/nexus/maven-metadata.xml in repository maven-central"));
  }
  
  @Test
  public void testArtifactDeleteLogMessage() {
    // Test the String Template format for artifact deletion log messages
    String logMessage = STR."Deleted artifact \{coordinates.getGroupId()}:\{coordinates.getArtifactId()}:\{coordinates.getVersion()} from repository \{REPOSITORY_NAME}";
    
    assertThat(logMessage, notNullValue());
    assertThat(logMessage, containsString(GROUP_ID));
    assertThat(logMessage, containsString(ARTIFACT_ID));
    assertThat(logMessage, containsString(VERSION));
    assertThat(logMessage, containsString(REPOSITORY_NAME));
    assertThat(logMessage, is("Deleted artifact org.sonatype.nexus:nexus-repository-maven:3.60.0-SNAPSHOT from repository maven-central"));
  }
  
  @Test
  public void testRepositoryRebuildLogMessage() {
    // Test the String Template format for repository rebuild log messages
    int artifactCount = 1250;
    long duration = 45678; // milliseconds
    String logMessage = STR."Repository \{REPOSITORY_NAME} rebuild completed: processed \{artifactCount} artifacts in \{duration}ms";
    
    assertThat(logMessage, notNullValue());
    assertThat(logMessage, containsString(REPOSITORY_NAME));
    assertThat(logMessage, containsString(String.valueOf(artifactCount)));
    assertThat(logMessage, containsString(String.valueOf(duration)));
    assertThat(logMessage, is("Repository maven-central rebuild completed: processed 1250 artifacts in 45678ms"));
  }
  
  @Test
  public void testComplexPathLogMessage() {
    // Test the String Template format for complex path log messages
    String logMessage = STR."Processing Maven artifact at path \{mavenPath.getPath()} with coordinates \{coordinates.getGroupId()}:\{coordinates.getArtifactId()}:\{coordinates.getVersion()}:\{coordinates.getClassifier()}:\{coordinates.getExtension()}";
    
    assertThat(logMessage, notNullValue());
    assertThat(logMessage, containsString(mavenPath.getPath()));
    assertThat(logMessage, containsString(GROUP_ID));
    assertThat(logMessage, containsString(ARTIFACT_ID));
    assertThat(logMessage, containsString(VERSION));
    assertThat(logMessage, containsString(CLASSIFIER));
    assertThat(logMessage, containsString(EXTENSION));
    assertThat(logMessage, is("Processing Maven artifact at path org/sonatype/nexus/nexus-repository-maven/3.60.0-SNAPSHOT/nexus-repository-maven-3.60.0-SNAPSHOT-tests.jar with coordinates org.sonatype.nexus:nexus-repository-maven:3.60.0-SNAPSHOT:tests:jar"));
  }
  
  @Test
  public void testMultilineLogMessage() {
    // Test the String Template format for multiline log messages
    String logMessage = STR."""
        Repository: \{REPOSITORY_NAME}
        Artifact: \{coordinates.getGroupId()}:\{coordinates.getArtifactId()}:\{coordinates.getVersion()}
        Path: \{mavenPath.getPath()}
        Operation: DOWNLOAD
        Timestamp: \{System.currentTimeMillis()}
        """;
    
    assertThat(logMessage, notNullValue());
    assertThat(logMessage, containsString(REPOSITORY_NAME));
    assertThat(logMessage, containsString(GROUP_ID));
    assertThat(logMessage, containsString(ARTIFACT_ID));
    assertThat(logMessage, containsString(VERSION));
    assertThat(logMessage, containsString(mavenPath.getPath()));
    assertThat(logMessage, containsString("DOWNLOAD"));
    assertThat(logMessage, containsString(String.valueOf(System.currentTimeMillis()).substring(0, 5)));
  }
  
  @Test
  public void testLogMessageWithConditionalExpression() {
    // Test the String Template format with conditional expressions
    boolean isSnapshot = VERSION.endsWith("-SNAPSHOT");
    String logMessage = STR."Artifact \{ARTIFACT_ID} is a \{isSnapshot ? "SNAPSHOT" : "RELEASE"} version";
    
    assertThat(logMessage, notNullValue());
    assertThat(logMessage, containsString(ARTIFACT_ID));
    assertThat(logMessage, containsString("SNAPSHOT"));
    assertThat(logMessage, is("Artifact nexus-repository-maven is a SNAPSHOT version"));
  }
  
  @Test
  public void testLogMessageWithMethodCall() {
    // Test the String Template format with method calls
    String logMessage = STR."Artifact path: \{mavenPath.getPath().toUpperCase()}";
    
    assertThat(logMessage, notNullValue());
    assertThat(logMessage, containsString(mavenPath.getPath().toUpperCase()));
    assertThat(logMessage, is("Artifact path: ORG/SONATYPE/NEXUS/NEXUS-REPOSITORY-MAVEN/3.60.0-SNAPSHOT/NEXUS-REPOSITORY-MAVEN-3.60.0-SNAPSHOT-TESTS.JAR"));
  }
  
  @Test
  public void testLogMessageWithArithmeticExpression() {
    // Test the String Template format with arithmetic expressions
    int downloadCount = 42;
    int totalCount = 100;
    String logMessage = STR."Download statistics: \{downloadCount} of \{totalCount} (\{downloadCount * 100 / totalCount}%)";
    
    assertThat(logMessage, notNullValue());
    assertThat(logMessage, containsString(String.valueOf(downloadCount)));
    assertThat(logMessage, containsString(String.valueOf(totalCount)));
    assertThat(logMessage, containsString("42"));
    assertThat(logMessage, is("Download statistics: 42 of 100 (42%)"));
  }
}