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

import java.io.File;
import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Loads and saves performance data as JSON.
 * 
 * <p>This class is compatible with Java 21 and supports serialization/deserialization
 * of record types used in the performance data model.</p>
 */
public class PerformanceDataIO
{
  private PerformanceDataIO() {
    // empty
  }

  /**
   * Loads performance test data from the specified file if it exists, otherwise returns an empty data set.
   * 
   * @param datafile the file to load data from
   * @return the loaded performance data or a new empty instance if the file doesn't exist
   * @throws IOException if an error occurs while reading the file
   */
  public static PerformanceData loadTestData(final File datafile) throws IOException {
    final ObjectMapper mapper = createObjectMapper();
    if (datafile.exists()) {
      return mapper.readValue(datafile, PerformanceData.class);
    }
    return new PerformanceData();
  }

  /**
   * Overwrites the provided datafile with json output representing the suite results.
   * 
   * @param results the performance data to save
   * @param datafile the file to save data to
   * @throws IOException if an error occurs while writing the file
   */
  public static void saveTestData(final PerformanceData results, final File datafile) throws IOException {
    final ObjectMapper mapper = createObjectMapper();
    mapper.writeValue(datafile, results);
  }
  
  /**
   * Creates a properly configured ObjectMapper for serializing/deserializing performance data.
   * 
   * @return a configured ObjectMapper instance
   */
  private static ObjectMapper createObjectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    // Configure for pretty printing
    mapper.enable(SerializationFeature.INDENT_OUTPUT);
    return mapper;
  }
}