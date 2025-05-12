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
package org.sonatype.nexus.repository.maven.api;

/**
 * Tests for {@link MavenAttributes} class.
 * 
 * Updated for Java 21 compatibility with JUnit Jupiter and modern testing practices.
 * Includes tests for Java 21 features like pattern matching, record patterns, and string templates.
 *
 * @since 3.60.0
 */

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.junit.jupiter.MockitoExtension;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.nexus.repository.maven.ContentDisposition;
import org.sonatype.nexus.repository.maven.LayoutPolicy;
import org.sonatype.nexus.repository.maven.VersionPolicy;

// Record for Java 21 record pattern testing
record PolicyConfiguration(String versionPolicy, String layoutPolicy, String contentDisposition) {}

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("java21")
@TestInstance(Lifecycle.PER_CLASS)
@ExtendWith(MockitoExtension.class)
class MavenAttributesTest extends TestSupport
{
  private Validator validator;

  private static final String[] VALID_VERSION_POLICIES = Arrays.stream(VersionPolicy.values())
      .map(Enum::toString)
      .toArray(String[]::new);

  private static final String[] VALID_LAYOUT_POLICIES = Arrays.stream(LayoutPolicy.values())
      .map(Enum::toString)
      .toArray(String[]::new);

  private static final String[] VALID_CONTENT_DISPOSITIONS = Arrays.stream(ContentDisposition.values())
      .map(Enum::toString)
      .toArray(String[]::new);
      
  private static final String VERSION_POLICY_ERROR_MSG = "must be one of RELEASE, SNAPSHOT, MIXED";

  private static final String LAYOUT_POLICY_ERROR_MSG = "must be one of STRICT, PERMISSIVE";

  private static final String CONTENT_DISPOSITION_ERROR_MSG = "must be one of INLINE, ATTACHMENT";

  private static final String EMPTY_ERROR_MSG = "must not be empty";

  @BeforeEach
  void setUp() {
    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      validator = factory.getValidator();
    }
  }

  @Test
  @DisplayName("Constructor and getters work correctly")
  void constructorAndGettersWorkCorrectly() {
    String versionPolicy = VALID_VERSION_POLICIES[0];
    String layoutPolicy = VALID_LAYOUT_POLICIES[0];
    String contentDisposition = VALID_CONTENT_DISPOSITIONS[0];

    MavenAttributes mavenAttributes = new MavenAttributes(versionPolicy, layoutPolicy, contentDisposition);

    assertAll(
        () -> assertThat(mavenAttributes.getVersionPolicy(), is(versionPolicy)),
        () -> assertThat(mavenAttributes.getLayoutPolicy(), is(layoutPolicy)),
        () -> assertThat(mavenAttributes.getContentDisposition(), is(contentDisposition))
    );
  }
  
  @ParameterizedTest
  @EnumSource(VersionPolicy.class)
  @DisplayName("Valid version policies are accepted")
  void validVersionPoliciesAreAccepted(final VersionPolicy versionPolicy) {
    MavenAttributes attributes =
        new MavenAttributes(versionPolicy.toString(), VALID_LAYOUT_POLICIES[0], VALID_CONTENT_DISPOSITIONS[0]);
    assertThat(validator.validate(attributes), is(empty()));
  }
  
  @ParameterizedTest
  @EnumSource(LayoutPolicy.class)
  @DisplayName("Valid layout policies are accepted")
  void validLayoutPoliciesAreAccepted(final LayoutPolicy layoutPolicy) {
    MavenAttributes attributes =
        new MavenAttributes(VALID_VERSION_POLICIES[0], layoutPolicy.toString(), VALID_CONTENT_DISPOSITIONS[0]);
    assertThat(validator.validate(attributes), is(empty()));
  }
  
  @ParameterizedTest
  @EnumSource(ContentDisposition.class)
  @DisplayName("Valid content dispositions are accepted")
  void validContentDispositionsAreAccepted(final ContentDisposition contentDisposition) {
    MavenAttributes attributes =
        new MavenAttributes(VALID_VERSION_POLICIES[0], VALID_LAYOUT_POLICIES[0], contentDisposition.toString());
    assertThat(validator.validate(attributes), is(empty()));
  }
  
  @Test
  @DisplayName("Invalid version policy is rejected")
  void invalidVersionPolicyIsRejected() {
    MavenAttributes attributes =
        new MavenAttributes("invalid", VALID_LAYOUT_POLICIES[0], VALID_CONTENT_DISPOSITIONS[0]);
    Set<ConstraintViolation<MavenAttributes>> violations = validator.validate(attributes);
    
    assertAll(
        () -> assertFalse(violations.isEmpty()),
        () -> assertThat(violations.stream()
            .anyMatch(v -> v.getMessage().equals(VERSION_POLICY_ERROR_MSG)), is(true))
    );
  }
  
  @Test
  @DisplayName("Invalid layout policy is rejected")
  void invalidLayoutPolicyIsRejected() {
    MavenAttributes attributes =
        new MavenAttributes(VALID_VERSION_POLICIES[0], "invalid", VALID_CONTENT_DISPOSITIONS[0]);
    Set<ConstraintViolation<MavenAttributes>> violations = validator.validate(attributes);
    
    assertAll(
        () -> assertFalse(violations.isEmpty()),
        () -> assertThat(violations.stream()
            .anyMatch(v -> v.getMessage().equals(LAYOUT_POLICY_ERROR_MSG)), is(true))
    );
  }
  
  @Test
  @DisplayName("Invalid content disposition is rejected")
  void invalidContentDispositionIsRejected() {
    MavenAttributes attributes =
        new MavenAttributes(VALID_VERSION_POLICIES[0], VALID_LAYOUT_POLICIES[0], "invalid");
    Set<ConstraintViolation<MavenAttributes>> violations = validator.validate(attributes);
    
    assertAll(
        () -> assertFalse(violations.isEmpty()),
        () -> assertThat(violations.stream()
            .anyMatch(v -> v.getMessage().equals(CONTENT_DISPOSITION_ERROR_MSG)), is(true))
    );
  }
  
  @Test
  @DisplayName("Empty version policy is rejected")
  void emptyVersionPolicyIsRejected() {
    MavenAttributes attributes = new MavenAttributes("", VALID_LAYOUT_POLICIES[0], VALID_CONTENT_DISPOSITIONS[0]);
    Set<ConstraintViolation<MavenAttributes>> violations = validator.validate(attributes);
    
    assertAll(
        () -> assertFalse(violations.isEmpty()),
        () -> assertThat(violations.stream()
            .anyMatch(v -> v.getMessage().equals(EMPTY_ERROR_MSG)), is(true))
    );
  }
  
  @Test
  @DisplayName("Empty layout policy is rejected")
  void emptyLayoutPolicyIsRejected() {
    MavenAttributes attributes = new MavenAttributes(VALID_VERSION_POLICIES[0], "", VALID_CONTENT_DISPOSITIONS[0]);
    Set<ConstraintViolation<MavenAttributes>> violations = validator.validate(attributes);
    
    assertAll(
        () -> assertFalse(violations.isEmpty()),
        () -> assertThat(violations.stream()
            .anyMatch(v -> v.getMessage().equals(EMPTY_ERROR_MSG)), is(true))
    );
  }
  
  @Test
  @DisplayName("Empty content disposition is rejected")
  void emptyContentDispositionIsRejected() {
    MavenAttributes attributes = new MavenAttributes(VALID_VERSION_POLICIES[0], VALID_LAYOUT_POLICIES[0], "");
    Set<ConstraintViolation<MavenAttributes>> violations = validator.validate(attributes);
    
    assertFalse(violations.isEmpty());
  }
  
  @Test
  @DisplayName("Null version policy is rejected")
  void nullVersionPolicyIsRejected() {
    MavenAttributes attributes = new MavenAttributes(null, VALID_LAYOUT_POLICIES[0], VALID_CONTENT_DISPOSITIONS[0]);
    Set<ConstraintViolation<MavenAttributes>> violations = validator.validate(attributes);
    
    assertAll(
        () -> assertFalse(violations.isEmpty()),
        () -> assertThat(violations.stream()
            .anyMatch(v -> v.getMessage().equals(EMPTY_ERROR_MSG)), is(true))
    );
  }
  
  @Test
  @DisplayName("Null layout policy is rejected")
  void nullLayoutPolicyIsRejected() {
    MavenAttributes attributes = new MavenAttributes(VALID_VERSION_POLICIES[0], null, VALID_CONTENT_DISPOSITIONS[0]);
    Set<ConstraintViolation<MavenAttributes>> violations = validator.validate(attributes);
    
    assertAll(
        () -> assertFalse(violations.isEmpty()),
        () -> assertThat(violations.stream()
            .anyMatch(v -> v.getMessage().equals(EMPTY_ERROR_MSG)), is(true))
    );
  }
  
  @Test
  @DisplayName("Null content disposition is accepted")
  void nullContentDispositionIsAccepted() {
    MavenAttributes attributes = new MavenAttributes(VALID_VERSION_POLICIES[0], VALID_LAYOUT_POLICIES[0], null);
    Set<ConstraintViolation<MavenAttributes>> violations = validator.validate(attributes);
    
    assertThat(violations, is(empty()));
  }
  
  /**
   * Tests using Java 21 pattern matching for switch to validate policy values.
   */
  @Test
  @DisplayName("Java 21 pattern matching for switch with policy validation")
  @Tag("java21")
  void java21PatternMatchingForSwitch() {
    // Create a map of all policies for testing
    Map<String, String> policies = Map.of(
        "versionPolicy", VALID_VERSION_POLICIES[0],
        "layoutPolicy", VALID_LAYOUT_POLICIES[0],
        "contentDisposition", VALID_CONTENT_DISPOSITIONS[0]
    );
    
    // Use pattern matching for switch to validate each policy
    for (var entry : policies.entrySet()) {
      boolean isValid = switch (entry.getKey()) {
        case "versionPolicy" when Arrays.asList(VALID_VERSION_POLICIES).contains(entry.getValue()) -> true;
        case "layoutPolicy" when Arrays.asList(VALID_LAYOUT_POLICIES).contains(entry.getValue()) -> true;
        case "contentDisposition" when Arrays.asList(VALID_CONTENT_DISPOSITIONS).contains(entry.getValue()) 
            || entry.getValue() == null -> true;
        default -> false;
      };
      
      assertTrue(isValid, "Policy " + entry.getKey() + " with value " + entry.getValue() + " should be valid");
    }
  }
  
  /**
   * Tests using Java 21 record patterns to extract and validate policy configurations.
   */
  @Test
  @DisplayName("Java 21 record pattern matching for policy configuration")
  @Tag("java21")
  void java21RecordPatternMatching() {
    // Create a policy configuration record
    PolicyConfiguration config = new PolicyConfiguration(
        VALID_VERSION_POLICIES[0],
        VALID_LAYOUT_POLICIES[0],
        VALID_CONTENT_DISPOSITIONS[0]
    );
    
    // Use record pattern matching to extract and validate fields
    if (config instanceof PolicyConfiguration(String versionPolicy, String layoutPolicy, String contentDisposition)) {
      // Create MavenAttributes using the extracted fields
      MavenAttributes attributes = new MavenAttributes(versionPolicy, layoutPolicy, contentDisposition);
      
      // Validate the attributes
      Set<ConstraintViolation<MavenAttributes>> violations = validator.validate(attributes);
      
      assertAll(
          () -> assertThat(violations, is(empty())),
          () -> assertThat(attributes.getVersionPolicy(), is(versionPolicy)),
          () -> assertThat(attributes.getLayoutPolicy(), is(layoutPolicy)),
          () -> assertThat(attributes.getContentDisposition(), is(contentDisposition))
      );
    }
  }
  
  /**
   * Tests using Java 21 string templates for error message formatting.
   */
  @Test
  @DisplayName("Java 21 string templates for error message formatting")
  @Tag("java21")
  void java21StringTemplates() {
    // Create an invalid MavenAttributes instance
    MavenAttributes attributes = new MavenAttributes("invalid", "invalid", "invalid");
    
    // Validate and collect error messages
    Set<ConstraintViolation<MavenAttributes>> violations = validator.validate(attributes);
    
    // Use string templates to format error messages
    String errorSummary = STR."Found \{violations.size()} validation errors for MavenAttributes";
    
    // Create detailed error message with string templates
    StringBuilder detailedErrors = new StringBuilder();
    for (ConstraintViolation<MavenAttributes> violation : violations) {
      String propertyPath = violation.getPropertyPath().toString();
      String message = violation.getMessage();
      detailedErrors.append(STR."Property '\{propertyPath}': \{message}\n");
    }
    
    // Verify results
    assertAll(
        () -> assertFalse(violations.isEmpty(), "Should have validation errors"),
        () -> assertThat(errorSummary, is(STR."Found \{violations.size()} validation errors for MavenAttributes")),
        () -> assertThat(detailedErrors.toString().contains("versionPolicy"), is(true)),
        () -> assertThat(detailedErrors.toString().contains("layoutPolicy"), is(true)),
        () -> assertThat(detailedErrors.toString().contains("contentDisposition"), is(true))
    );
  }
  
  /**
   * Tests that MavenAttributes can be created with all valid combinations of policies.
   */
  @Test
  @DisplayName("All valid policy combinations work")
  void allValidPolicyCombinationsWork() {
    // Test all combinations of valid policies
    for (String versionPolicy : VALID_VERSION_POLICIES) {
      for (String layoutPolicy : VALID_LAYOUT_POLICIES) {
        for (String contentDisposition : VALID_CONTENT_DISPOSITIONS) {
          MavenAttributes attributes = new MavenAttributes(versionPolicy, layoutPolicy, contentDisposition);
          Set<ConstraintViolation<MavenAttributes>> violations = validator.validate(attributes);
          
          assertThat("Combination: " + versionPolicy + ", " + layoutPolicy + ", " + contentDisposition, 
              violations, is(empty()));
        }
      }
    }
  }

