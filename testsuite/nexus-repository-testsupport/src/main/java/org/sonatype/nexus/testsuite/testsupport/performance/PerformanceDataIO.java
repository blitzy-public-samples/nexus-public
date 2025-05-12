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
package org.sonatype.nexus.testsuite.testsupport.performance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Loads and saves performance data as JSON.
 * <p>
 * This class is designed to be used with dependency injection in Java 21 environments.
 * It provides thread-safe operations for reading and writing performance data.
 *
 * @since 3.60
 */
@Named
@Singleton
public class PerformanceDataIO
{
  private final ObjectMapper objectMapper;

  /**
   * Creates a new instance with a configured ObjectMapper.
   */
  @Inject
  public PerformanceDataIO() {
    this(createDefaultObjectMapper());
  }

  /**
   * Creates a new instance with the provided ObjectMapper.
   *
   * @param objectMapper the ObjectMapper to use for JSON serialization/deserialization
   */
  public PerformanceDataIO(final ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * Creates a default ObjectMapper configured for performance data serialization.
   *
   * @return a configured ObjectMapper instance
   */
  private static ObjectMapper createDefaultObjectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.configure(SerializationFeature.INDENT_OUTPUT, true);
    return mapper;
  }

  /**
   * Loads performance test data from the specified path if it exists, otherwise returns an empty data set.
   *
   * @param dataPath the path to the JSON data file
   * @return the loaded performance data or a new empty instance if the file doesn't exist
   * @throws IOException if an error occurs during file reading or JSON parsing
   */
  public PerformanceData loadTestData(final Path dataPath) throws IOException {
    if (Files.exists(dataPath)) {
      return objectMapper.readValue(Files.readAllBytes(dataPath), PerformanceData.class);
    }
    return new PerformanceData();
  }

  /**
   * Overwrites the provided data file with JSON output representing the suite results.
   *
   * @param results the performance data to save
   * @param dataPath the path where the JSON data should be written
   * @throws IOException if an error occurs during JSON serialization or file writing
   */
  public void saveTestData(final PerformanceData results, final Path dataPath) throws IOException {
    Files.createDirectories(dataPath.getParent());
    objectMapper.writeValue(dataPath.toFile(), results);
  }

  /**
   * Legacy method for backward compatibility with code that uses File instead of Path.
   * 
   * @param dataFile the file to load data from
   * @return the loaded performance data or a new empty instance if the file doesn't exist
   * @throws IOException if an error occurs during file reading or JSON parsing
   * @deprecated Use {@link #loadTestData(Path)} instead
   */
  @Deprecated
  public PerformanceData loadTestData(final java.io.File dataFile) throws IOException {
    return loadTestData(dataFile.toPath());
  }

  /**
   * Legacy method for backward compatibility with code that uses File instead of Path.
   *
   * @param results the performance data to save
   * @param dataFile the file where the JSON data should be written
   * @throws IOException if an error occurs during JSON serialization or file writing
   * @deprecated Use {@link #saveTestData(PerformanceData, Path)} instead
   */
  @Deprecated
  public void saveTestData(final PerformanceData results, final java.io.File dataFile) throws IOException {
    saveTestData(results, dataFile.toPath());
  }

  /**
   * Static utility method for backward compatibility with existing code.
   * This method creates a temporary PerformanceDataIO instance for one-time use.
   *
   * @param dataFile the file to load data from
   * @return the loaded performance data or a new empty instance if the file doesn't exist
   * @throws IOException if an error occurs during file reading or JSON parsing
   * @deprecated Use dependency injection and instance methods instead
   */
  @Deprecated
  public static PerformanceData loadTestDataStatic(final java.io.File dataFile) throws IOException {
    return new PerformanceDataIO().loadTestData(dataFile);
  }

  /**
   * Static utility method for backward compatibility with existing code.
   * This method creates a temporary PerformanceDataIO instance for one-time use.
   *
   * @param results the performance data to save
   * @param dataFile the file where the JSON data should be written
   * @throws IOException if an error occurs during JSON serialization or file writing
   * @deprecated Use dependency injection and instance methods instead
   */
  @Deprecated
  public static void saveTestDataStatic(final PerformanceData results, final java.io.File dataFile) throws IOException {
    new PerformanceDataIO().saveTestData(results, dataFile);
  }
}