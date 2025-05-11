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
package org.sonatype.nexus.content.maven.internal;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.maven.MavenPath.Coordinates;
import org.sonatype.nexus.repository.maven.MavenPathParser;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.selector.PropertiesResolver;
import org.sonatype.nexus.selector.VariableResolver;
import org.sonatype.nexus.selector.VariableSourceBuilder;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.google.common.collect.ImmutableMap.of;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.maven.MavenPath.SignatureType.GPG;

/**
 * Tests for {@link MavenVariableResolverAdapter}.
 * 
 * Updated for Java 21 compatibility using JUnit Jupiter 5.10.1 and Mockito 4.11.0.
 * This test class demonstrates migration from JUnit 4 to JUnit Jupiter and includes
 * examples of Java 21 features like pattern matching for instanceof.
 */
@ExtendWith(MockitoExtension.class)
class MavenVariableResolverAdapterTest
    extends TestSupport
{
  private static final String ARTIFACT_PATH = "group/artifact/version/artifact-version.jar";

  @Mock
  private MavenPathParser mavenPathParser;

  @Mock
  private VariableSourceBuilder builder;

  @Mock
  private Request request;

  @Mock
  private MavenPath mavenPath;

  @Captor
  private ArgumentCaptor<PropertiesResolver<String>> propertiesResolverCaptor;

  private final ImmutableMap<String, Object> asset = of("name", ARTIFACT_PATH);

  @InjectMocks
  private MavenVariableResolverAdapter mavenVariableResolverAdapter;

  @BeforeEach
  void setup() {
    Coordinates coordinates = new Coordinates(false, "org.mockito", "mockito-core",
        "3.24", 3600L, 100, "3.24", "test", ".jar", GPG);
    mockPathParsing(coordinates);
  }

  @Test
  void addFromRequestShouldAddCoordinatesToVariableSourceBuilder() {
    mavenVariableResolverAdapter.addFromRequest(builder, request);

    verifyCoordinatesSet();
  }

  @Test
  void addFromSourceLookupShouldAddCoordinatesToVariableSourceBuilder() {
    mavenVariableResolverAdapter.addFromSourceLookup(builder, null, asset);

    verifyCoordinatesSet();
  }

  @Test
  void addFromRequestShouldNotAddNullCoordinates() {
    when(mavenPath.getCoordinates()).thenReturn(null);

    mavenVariableResolverAdapter.addFromRequest(builder, request);

    verify(builder, never()).addResolver(any(VariableResolver.class));
  }

  @Test
  void addFromSourceLookupShouldNotAddNullCoordinates() {
    when(mavenPath.getCoordinates()).thenReturn(null);

    mavenVariableResolverAdapter.addFromSourceLookup(builder, null, asset);

    verify(builder, never()).addResolver(any(VariableResolver.class));
  }

  /**
   * Test demonstrating Java 21 pattern matching for instanceof with Coordinates.
   * This test verifies that coordinates are correctly identified and processed using pattern matching.
   */
  @Test
  void patternMatchingWithCoordinates() {
    // Create a test object that could be a Coordinates instance
    Object testObject = new Coordinates(false, "org.junit.jupiter", "junit-jupiter",
        "5.10.1", 3600L, 100, "5.10.1", "api", ".jar", null);
    
    // Use Java 21 pattern matching for instanceof to extract values directly
    if (testObject instanceof Coordinates coords) {
      // With pattern matching, we can directly use the extracted variable
      assertThat(coords.getGroupId(), is("org.junit.jupiter"));
      assertThat(coords.getArtifactId(), is("junit-jupiter"));
      assertThat(coords.getVersion(), is("5.10.1"));
      assertThat(coords.getClassifier(), is("api"));
      assertThat(coords.getExtension(), is(".jar"));
    } else {
      // This should not happen in this test
      throw new AssertionError("testObject should be an instance of Coordinates");
    }
  }
  
  /**
   * Test demonstrating Java 21 switch pattern matching with Coordinates.
   * This test shows how to use pattern matching in switch statements to handle different types.
   */
  @Test
  void switchPatternMatchingWithCoordinates() {
    // Create test objects of different types
    Object coordsObject = new Coordinates(false, "org.mockito", "mockito-junit-jupiter",
        "4.11.0", 3600L, 100, "4.11.0", null, ".jar", null);
    Object stringObject = "Not a coordinates object";
    
    // Test with coordinates object
    String coordsResult = switch (coordsObject) {
      case Coordinates c when c.getGroupId().equals("org.mockito") ->
        "Found Mockito coordinates: " + c.getArtifactId() + "-" + c.getVersion();
      case Coordinates c -> "Found other coordinates: " + c.getArtifactId();
      case String s -> "Found string: " + s;
      default -> "Unknown object type";
    };
    
    assertThat(coordsResult, is("Found Mockito coordinates: mockito-junit-jupiter-4.11.0"));
    
    // Test with string object
    String stringResult = switch (stringObject) {
      case Coordinates c -> "Found coordinates: " + c.getArtifactId();
      case String s -> "Found string: " + s;
      default -> "Unknown object type";
    };
    
    assertThat(stringResult, is("Found string: Not a coordinates object"));
  }

  private void mockPathParsing(final Coordinates coordinates) {
    when(request.getPath()).thenReturn(ARTIFACT_PATH);
    when(mavenPathParser.parsePath(ARTIFACT_PATH)).thenReturn(mavenPath);
    when(mavenPath.getCoordinates()).thenReturn(coordinates);
  }

  private void verifyCoordinatesSet() {
    verify(builder).addResolver(propertiesResolverCaptor.capture());
    PropertiesResolver<String> propertiesResolver = propertiesResolverCaptor.getValue();

    assertTrue(propertiesResolver.resolve("coordinate.groupId").isPresent(),
        "coordinate.groupId should be present");
    assertThat(propertiesResolver.resolve("coordinate.groupId").get(), is("org.mockito"));

    assertTrue(propertiesResolver.resolve("coordinate.artifactId").isPresent(),
        "coordinate.artifactId should be present");
    assertThat(propertiesResolver.resolve("coordinate.artifactId").get(), is("mockito-core"));

    assertTrue(propertiesResolver.resolve("coordinate.version").isPresent(),
        "coordinate.version should be present");
    assertThat(propertiesResolver.resolve("coordinate.version").get(), is("3.24"));

    assertTrue(propertiesResolver.resolve("coordinate.classifier").isPresent(),
        "coordinate.classifier should be present");
    assertThat(propertiesResolver.resolve("coordinate.classifier").get(), is("test"));

    assertTrue(propertiesResolver.resolve("coordinate.extension").isPresent(),
        "coordinate.extension should be present");
    assertThat(propertiesResolver.resolve("coordinate.extension").get(), is(".jar"));
  }
}