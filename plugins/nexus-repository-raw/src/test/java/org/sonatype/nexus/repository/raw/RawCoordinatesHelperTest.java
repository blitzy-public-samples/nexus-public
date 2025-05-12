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
package org.sonatype.nexus.repository.raw;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link RawCoordinatesHelper} tests.
 * 
 * Tests the functionality of the RawCoordinatesHelper class, particularly the getGroup method
 * which uses Java 21 pattern matching for switch to handle different path formats.
 */
@DisplayName("RawCoordinatesHelper Tests")
public class RawCoordinatesHelperTest
{
  /**
   * Provides test data for parameterized tests.
   * Each argument consists of a path and the expected group extracted from that path.
   * 
   * @return a stream of arguments for the parameterized test
   */
  static Stream<Arguments> pathData() {
    return Stream.of(
        Arguments.of("/foo/bar", "/foo"),
        Arguments.of("foo/bar", "/foo"),
        Arguments.of("foobar.txt", "/"),
        Arguments.of("/foobar.txt", "/"),
        Arguments.of("/some/long/involved/path.txt", "/some/long/involved"),
        Arguments.of("some/long/involved/path.txt", "/some/long/involved")
    );
  }

  /**
   * Tests the getGroup method with various path formats.
   * This test verifies that the pattern matching in the getGroup method correctly handles
   * different path formats and extracts the appropriate group.
   * 
   * @param path the input path to test
   * @param expectedGroup the expected group that should be extracted from the path
   */
  @ParameterizedTest(name = "group of {0} is {1}")
  @MethodSource("pathData")
  @DisplayName("Should extract correct group from path")
  public void testGetGroup(String path, String expectedGroup) {
    String group = RawCoordinatesHelper.getGroup(path);
    assertEquals(expectedGroup, group, "The extracted group should match the expected value");
  }
}