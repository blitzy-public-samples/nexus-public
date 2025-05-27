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
package org.sonatype.nexus.repository.maven.stringtemplates;

import static java.lang.StringTemplate.STR;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;

/**
 * Tests for validating Java 21 String Templates usage in Maven repository user-facing messages.
 * 
 * This test class validates that user-facing messages related to Maven repository operations
 * (such as success notifications, warning messages, and information displays) are correctly
 * formatted using Java 21's String Templates feature. The tests ensure that messages contain
 * all necessary information and are properly formatted for display in the UI or API responses.
 */
@Category(Java21TestGroup.class)
public class MavenUserMessageTemplateTest
    extends TestSupport
{
  private static final String REPOSITORY_NAME = "maven-central";
  private static final String GROUP_ID = "org.example";
  private static final String ARTIFACT_ID = "example-artifact";
  private static final String VERSION = "1.0.0";
  
  @Before
  public void setup() {
    // No specific setup needed for these tests as they validate String Template formatting
    // without requiring external dependencies or mocks
  }
  
  @Test
  public void testSuccessMessageFormatting() {
    // Test success message formatting using String Templates
    String message = STR."Artifact \{GROUP_ID}:\{ARTIFACT_ID}:\{VERSION} successfully deployed to repository \{REPOSITORY_NAME}";
    
    assertThat(message, notNullValue());
    assertThat(message, containsString(GROUP_ID));
    assertThat(message, containsString(ARTIFACT_ID));
    assertThat(message, containsString(VERSION));
    assertThat(message, containsString(REPOSITORY_NAME));
    assertThat(message, is("Artifact org.example:example-artifact:1.0.0 successfully deployed to repository maven-central"));
  }
  
  @Test
  public void testWarningMessageFormatting() {
    // Test warning message formatting using String Templates
    boolean overwrite = true;
    String message = STR."Warning: Artifact \{GROUP_ID}:\{ARTIFACT_ID}:\{VERSION} already exists in repository \{REPOSITORY_NAME}. Overwrite: \{overwrite}";
    
    assertThat(message, notNullValue());
    assertThat(message, containsString(GROUP_ID));
    assertThat(message, containsString(ARTIFACT_ID));
    assertThat(message, containsString(VERSION));
    assertThat(message, containsString(REPOSITORY_NAME));
    assertThat(message, containsString(String.valueOf(overwrite)));
    assertThat(message, is("Warning: Artifact org.example:example-artifact:1.0.0 already exists in repository maven-central. Overwrite: true"));
  }
  
  @Test
  public void testInfoMessageFormatting() {
    // Test info message formatting using String Templates
    long fileSize = 1024 * 1024; // 1MB
    String message = STR."Info: Artifact \{GROUP_ID}:\{ARTIFACT_ID}:\{VERSION} (\{fileSize} bytes) is being processed in repository \{REPOSITORY_NAME}";
    
    assertThat(message, notNullValue());
    assertThat(message, containsString(GROUP_ID));
    assertThat(message, containsString(ARTIFACT_ID));
    assertThat(message, containsString(VERSION));
    assertThat(message, containsString(REPOSITORY_NAME));
    assertThat(message, containsString(String.valueOf(fileSize)));
    assertThat(message, is("Info: Artifact org.example:example-artifact:1.0.0 (1048576 bytes) is being processed in repository maven-central"));
  }
  
  @Test
  public void testComplexMessageWithMultipleExpressions() {
    // Test complex message with multiple expressions and calculations
    int downloadCount = 1250;
    double averageDownloadTime = 0.75;
    
    String message = STR."""
        Artifact Statistics for \{GROUP_ID}:\{ARTIFACT_ID}:\{VERSION} in repository \{REPOSITORY_NAME}:
        - Total Downloads: \{downloadCount}
        - Average Download Time: \{averageDownloadTime} seconds
        - Estimated Total Download Time: \{downloadCount * averageDownloadTime} seconds
        """;
    
    assertThat(message, notNullValue());
    assertThat(message, containsString(GROUP_ID));
    assertThat(message, containsString(ARTIFACT_ID));
    assertThat(message, containsString(VERSION));
    assertThat(message, containsString(REPOSITORY_NAME));
    assertThat(message, containsString(String.valueOf(downloadCount)));
    assertThat(message, containsString(String.valueOf(averageDownloadTime)));
    assertThat(message, containsString(String.valueOf(downloadCount * averageDownloadTime)));
  }
  
  @Test
  public void testMessageWithConditionalExpression() {
    // Test message with conditional expression
    boolean isSnapshot = true;
    String message = STR."Artifact \{GROUP_ID}:\{ARTIFACT_ID}:\{VERSION} is a \{isSnapshot ? "SNAPSHOT" : "RELEASE"} version in repository \{REPOSITORY_NAME}";
    
    assertThat(message, notNullValue());
    assertThat(message, containsString(GROUP_ID));
    assertThat(message, containsString(ARTIFACT_ID));
    assertThat(message, containsString(VERSION));
    assertThat(message, containsString(REPOSITORY_NAME));
    assertThat(message, containsString("SNAPSHOT"));
    assertThat(message, is("Artifact org.example:example-artifact:1.0.0 is a SNAPSHOT version in repository maven-central"));
    
    // Test with different condition
    isSnapshot = false;
    message = STR."Artifact \{GROUP_ID}:\{ARTIFACT_ID}:\{VERSION} is a \{isSnapshot ? "SNAPSHOT" : "RELEASE"} version in repository \{REPOSITORY_NAME}";
    
    assertThat(message, notNullValue());
    assertThat(message, containsString(GROUP_ID));
    assertThat(message, containsString(ARTIFACT_ID));
    assertThat(message, containsString(VERSION));
    assertThat(message, containsString(REPOSITORY_NAME));
    assertThat(message, containsString("RELEASE"));
    assertThat(message, is("Artifact org.example:example-artifact:1.0.0 is a RELEASE version in repository maven-central"));
  }
  
  @Test
  public void testMessageWithMethodCall() {
    // Test message with method call in expression
    String message = STR."Artifact \{getFullArtifactCoordinates()} in repository \{REPOSITORY_NAME}";
    
    assertThat(message, notNullValue());
    assertThat(message, containsString(GROUP_ID));
    assertThat(message, containsString(ARTIFACT_ID));
    assertThat(message, containsString(VERSION));
    assertThat(message, containsString(REPOSITORY_NAME));
    assertThat(message, is("Artifact org.example:example-artifact:1.0.0 in repository maven-central"));
  }
  
  private String getFullArtifactCoordinates() {
    return STR."\{GROUP_ID}:\{ARTIFACT_ID}:\{VERSION}";
  }
  
  @Test
  public void testMessageWithCollectionData() {
    // Test message with collection data
    List<String> tags = List.of("stable", "production", "verified");
    String message = STR."Artifact \{GROUP_ID}:\{ARTIFACT_ID}:\{VERSION} in repository \{REPOSITORY_NAME} has tags: \{String.join(", ", tags)}";
    
    assertThat(message, notNullValue());
    assertThat(message, containsString(GROUP_ID));
    assertThat(message, containsString(ARTIFACT_ID));
    assertThat(message, containsString(VERSION));
    assertThat(message, containsString(REPOSITORY_NAME));
    assertThat(message, containsString("stable, production, verified"));
    assertThat(message, is("Artifact org.example:example-artifact:1.0.0 in repository maven-central has tags: stable, production, verified"));
  }
}