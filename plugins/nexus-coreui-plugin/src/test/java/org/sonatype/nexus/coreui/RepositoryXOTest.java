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
package org.sonatype.nexus.coreui;

import com.google.inject.Guice;

import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.mockito.Mock;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.validation.ValidationModule;
import org.sonatype.nexus.validation.group.Create;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.when;

public class RepositoryXOTest
    extends TestSupport
{
  private Validator validator;
  @Mock
  private RepositoryXO repositoryXO;

  @BeforeEach
  public void setup() {
    validator =
        Guice.createInjector(new ValidationModule(), new TestRepositoryManagerModule())
            .getInstance(Validator.class);
  }

  @Test
  public void nameShouldBeRequired() {
    SequencedMap<String, Map<String, Object>> attributes =
            new LinkedHashMap<>();
    attributes.put("any", Map.of("any", "any"));

    when(repositoryXO.attributes()).thenReturn(attributes);
    when(repositoryXO.online()).thenReturn(true);

    Set<ConstraintViolation<RepositoryXO>> violations = validator.validate(repositoryXO);
    assertThat(violations.size(), is(1));
    assertThat(violations.iterator().next().getPropertyPath().toString(), is("name"));
  }

  @ParameterizedTest
  @MethodSource("invalidAttributes")
  public void attributesShouldBeRequiredAndNonEmpty(Map<String, Map<String, Object>> attributesMap) {
      SequencedMap<String, Map<String, Object>> attributes =
              new LinkedHashMap<>(attributesMap);

    when(repositoryXO.attributes()).thenReturn(attributes);
    when(repositoryXO.online()).thenReturn(true);
    when(repositoryXO.name()).thenReturn("foo");

    Set<ConstraintViolation<RepositoryXO>> violations = validator.validate(repositoryXO);
    assertThat(violations.size(), is(1));
    assertThat(violations.iterator().next().getPropertyPath().toString(), is("attributes"));
  }

  private static Stream<Arguments> invalidAttributes() {
    return Stream.of(
        arguments((Object) null),
        arguments(Collections.emptyMap())
    );
  }

  @ParameterizedTest
  @MethodSource("invalidNames")
  public void nameShouldNotValidate(String name) {
    SequencedMap<String, Map<String, Object>> attributes =
            new LinkedHashMap<>();
    attributes.put("any", Map.of("any", "any"));

    when(repositoryXO.attributes()).thenReturn(attributes);
    when(repositoryXO.online()).thenReturn(true);
    when(repositoryXO.name()).thenReturn(name);
    Set<ConstraintViolation<RepositoryXO>> violations = validator.validate(repositoryXO);
    assertThat(violations.size(), is(1));
    assertThat(violations.iterator().next().getPropertyPath().toString(), is("name"));
  }

  private static Stream<Arguments> invalidNames() {
    List<Object> noValid = new ArrayList<>("#.,* #'\\/?<>| \r\n\t,+@&\u00e5\u00a9\u4e0d\u03b2\u062e".chars()
        .mapToObj(c -> (char) c)
        .collect(Collectors.toList())); // NOSONAR
    noValid.add("_leadingUnderscore");
    noValid.add("..");
    
    return noValid.stream().map(Arguments::of);
  }

  @ParameterizedTest
  @MethodSource("validNames")
  public void nameShouldBeValid(String name) {
    SequencedMap<String, Map<String, Object>> attributes =
            new LinkedHashMap<>();
    attributes.put("any", Map.of("any", "any"));

    when(repositoryXO.attributes()).thenReturn(attributes);
    when(repositoryXO.online()).thenReturn(true);
    when(repositoryXO.name()).thenReturn(name);
    Set<ConstraintViolation<RepositoryXO>> violations = validator.validate(repositoryXO);
    assertThat(violations.isEmpty(), is(true));
  }

  private static Stream<Arguments> validNames() {
    return Stream.of(
        arguments("Foo_1.2-3"),
        arguments("foo."),
        arguments("-0."),
        arguments("a"),
        arguments("1")
    );
  }

  @Test
  public void recipeFieldShouldOnlyBeRequiredOnCreation() {
    SequencedMap<String, Map<String, Object>> attributes =
            new LinkedHashMap<>();
    attributes.put("any", Map.of("any", "any"));

    when(repositoryXO.attributes()).thenReturn(attributes);

    when(repositoryXO.name()).thenReturn("bob");
    Set<ConstraintViolation<RepositoryXO>> violations = validator.validate(repositoryXO, Create.class);
    assertThat(violations.size(), is(1));
    assertThat(violations.iterator().next().getPropertyPath().toString(), is("recipe"));

    when(repositoryXO.recipe()).thenReturn("any");
    violations = validator.validate(repositoryXO, Create.class);
    assertThat(violations.isEmpty(), is(true));
  }

  @ParameterizedTest
  @MethodSource("nonUniqueNames")
  public void nameShouldBeValidatedAsCaseInsensitivelyUniqueOnCreation(String repoName) {
    SequencedMap<String, Map<String, Object>> attributes =
            new LinkedHashMap<>();
    attributes.put("any", Map.of("any", Map.of()));

    when(repositoryXO.attributes()).thenReturn(attributes);
    when(repositoryXO.name()).thenReturn(repoName);
    when(repositoryXO.online()).thenReturn(true);
    when(repositoryXO.recipe()).thenReturn("any");

    Set<ConstraintViolation<RepositoryXO>> violations = validator.validate(repositoryXO, Create.class);
    assertThat(violations.size(), is(1));
    assertThat(violations.iterator().next().getPropertyPath().toString(), is("name"));
    assertThat(violations.iterator().next().getMessage(), is("Name is already used, must be unique (ignoring case)"));
  }

  private static Stream<Arguments> nonUniqueNames() {
    return Stream.of(
        arguments("Foo"),
        arguments("bAr"),
        arguments("baZ")
    );
  }
}