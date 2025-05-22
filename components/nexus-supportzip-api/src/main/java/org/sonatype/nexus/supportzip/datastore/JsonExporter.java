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
package org.sonatype.nexus.supportzip.datastore;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.goodies.common.Time;
import org.sonatype.nexus.common.io.SanitizingJsonOutputStream;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.joda.JodaModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.io.ByteStreams;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;

import static com.fasterxml.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS;
import static com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;
import static org.sonatype.nexus.supportzip.PasswordSanitizing.REPLACEMENT;
import static org.sonatype.nexus.supportzip.PasswordSanitizing.SENSITIVE_FIELD_NAMES;

/**
 * Export/Import data to/from the JSON by replacing sensitive data.
 * Uses Java 21 Virtual Threads for I/O-bound operations to improve throughput and concurrency.
 *
 * @since 3.29
 */
@Named
@Singleton
public class JsonExporter
    extends ComponentSupport
{
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final String EMPTY_JSON = "{}";
  
  /**
   * Record representing JSON export operation context
   */
  private record ExportContext<T>(T data, File file, boolean isList) {}

  /**
   * Record representing JSON import operation context
   */
  private record ImportContext<T>(File file, Class<T> clazz, boolean isList) {}

  static {
    OBJECT_MAPPER.registerModule(new SimpleModule()
        .addSerializer(Time.class, new SecondsSerializer())
        .addDeserializer(Time.class, new SecondsDeserializer()));
    OBJECT_MAPPER.registerModule(new JavaTimeModule());
    OBJECT_MAPPER.registerModule(new JodaModule());
    OBJECT_MAPPER.registerModule(new Jdk8Module());
    OBJECT_MAPPER.disable(WRITE_DATES_AS_TIMESTAMPS);
    OBJECT_MAPPER.disable(FAIL_ON_EMPTY_BEANS);
  }

  /**
   * Export data to the JSON file and hide sensitive fields.
   * Uses Virtual Threads for improved I/O performance.
   *
   * @param objects to be exported.
   * @param file    where to export.
   * @throws IOException for any issue during writing a file.
   */
  public <T> void exportToJson(final List<T> objects, final File file) throws IOException {
    checkNotNull(file);
    if (objects == null || objects.isEmpty()) {
      log.debug(STR."Writing empty JSON to file: \{file.getName()}");
      writeEmptyJson(file);
    }
    else {
      log.debug(STR."Exporting \{objects.size()} objects to JSON file: \{file.getName()}");
      ExportContext<List<T>> context = new ExportContext<>(objects, file, true);
      executeWithVirtualThread(() -> performExport(context));
    }
  }

  /**
   * Export data to the JSON file and hide sensitive fields.
   * Uses Virtual Threads for improved I/O performance.
   *
   * @param object to be exported.
   * @param file   where to export.
   * @throws IOException for any issue during writing a file.
   */
  public <T> void exportObjectToJson(final T object, final File file) throws IOException {
    checkNotNull(file);
    if (object == null) {
      log.debug(STR."Writing empty JSON to file: \{file.getName()}");
      writeEmptyJson(file);
    }
    else {
      log.debug(STR."Exporting object to JSON file: \{file.getName()}");
      ExportContext<T> context = new ExportContext<>(object, file, false);
      executeWithVirtualThread(() -> performExport(context));
    }
  }

  /**
   * Read JSON data.
   * Uses Virtual Threads for improved I/O performance.
   *
   * @param file  file where data will be read.
   * @param clazz the type of imported data.
   * @return the list of {@link T} objects.
   * @throws IOException for any issue during reading a file.
   */
  public <T> List<T> importFromJson(final File file, final Class<T> clazz) throws IOException {
    checkNotNull(file);
    checkNotNull(clazz);
    log.debug(STR."Importing list of \{clazz.getSimpleName()} from JSON file: \{file.getName()}");
    ImportContext<T> context = new ImportContext<>(file, clazz, true);
    return executeWithVirtualThread(() -> performImport(context));
  }

  /**
   * Read JSON data.
   * Uses Virtual Threads for improved I/O performance.
   *
   * @param file  file where data will be read.
   * @param clazz the type of imported data.
   * @return {@link T} object or {@link Optional#empty} is case of an empty JSON file.
   * @throws IOException for any issue during reading a file.
   */
  public <T> Optional<T> importObjectFromJson(final File file, final Class<T> clazz) throws IOException {
    checkNotNull(file);
    checkNotNull(clazz);
    log.debug(STR."Importing \{clazz.getSimpleName()} object from JSON file: \{file.getName()}");
    ImportContext<T> context = new ImportContext<>(file, clazz, false);
    return executeWithVirtualThread(() -> performObjectImport(context));
  }

  /**
   * Executes the given task in a virtual thread and returns the result.
   *
   * @param task the task to execute
   * @param <R> the type of the result
   * @return the result of the task
   * @throws IOException if an I/O error occurs
   */
  private <R> R executeWithVirtualThread(IOSupplier<R> task) throws IOException {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<R> future = executor.submit(() -> {
        try {
          return task.get();
        }
        catch (IOException e) {
          throw new RuntimeException(e);
        }
      });
      
      try {
        return future.get();
      }
      catch (Exception e) {
        Throwable cause = e.getCause();
        if (cause instanceof IOException) {
          throw (IOException) cause;
        }
        throw new IOException(STR."Error executing task: \{e.getMessage()}", e);
      }
    }
  }

  /**
   * Performs the actual export operation.
   *
   * @param context the export context
   * @param <T> the type of data to export
   * @throws IOException if an I/O error occurs
   */
  private <T> Void performExport(ExportContext<T> context) throws IOException {
    if (context instanceof ExportContext(var data, var file, var isList)) {
      byte[] jsonBytes = isList ? 
          OBJECT_MAPPER.writeValueAsBytes(data) : 
          OBJECT_MAPPER.writeValueAsBytes(data);
      
      try (ByteArrayInputStream is = new ByteArrayInputStream(jsonBytes);
           OutputStream os = new BufferedOutputStream(new FileOutputStream(file));
           SanitizingJsonOutputStream stream = new SanitizingJsonOutputStream(os, SENSITIVE_FIELD_NAMES, REPLACEMENT)) {
        ByteStreams.copy(is, stream);
        log.debug(STR."Successfully exported data to file: \{file.getName()}");
      }
    }
    return null;
  }

  /**
   * Performs the actual import operation for a list.
   *
   * @param context the import context
   * @param <T> the type of data to import
   * @return the imported list
   * @throws IOException if an I/O error occurs
   */
  private <T> List<T> performImport(ImportContext<T> context) throws IOException {
    if (context instanceof ImportContext(var file, var clazz, var isList) && isList) {
      try (FileInputStream inputStream = new FileInputStream(file)) {
        String jsonData = IOUtils.toString(inputStream, StandardCharsets.UTF_8);
        if (StringUtils.isNotBlank(jsonData) && !jsonData.equals(EMPTY_JSON)) {
          JavaType type = OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, clazz);
          List<T> result = OBJECT_MAPPER.readValue(jsonData, type);
          log.debug(STR."Successfully imported \{result.size()} items from file: \{file.getName()}");
          return result;
        }
      }
      log.debug(STR."No data found in file: \{file.getName()}");
      return Collections.emptyList();
    }
    throw new IllegalArgumentException("Invalid import context");
  }

  /**
   * Performs the actual import operation for a single object.
   *
   * @param context the import context
   * @param <T> the type of data to import
   * @return the imported object wrapped in an Optional
   * @throws IOException if an I/O error occurs
   */
  private <T> Optional<T> performObjectImport(ImportContext<T> context) throws IOException {
    if (context instanceof ImportContext(var file, var clazz, var isList) && !isList) {
      try (FileInputStream inputStream = new FileInputStream(file)) {
        String jsonData = IOUtils.toString(inputStream, StandardCharsets.UTF_8);
        if (StringUtils.isNotBlank(jsonData) && !jsonData.equals(EMPTY_JSON)) {
          T result = OBJECT_MAPPER.readValue(jsonData, clazz);
          log.debug(STR."Successfully imported object from file: \{file.getName()}");
          return Optional.of(result);
        }
      }
      log.debug(STR."No data found in file: \{file.getName()}");
      return Optional.empty();
    }
    throw new IllegalArgumentException("Invalid import context");
  }

  /**
   * Writes an empty JSON object to the specified file.
   *
   * @param file the file to write to
   * @throws IOException if an I/O error occurs
   */
  private void writeEmptyJson(final File file) throws IOException {
    try (FileWriter fileWriter = new FileWriter(file)) {
      fileWriter.write(EMPTY_JSON);
      log.debug(STR."Successfully wrote empty JSON to file: \{file.getName()}");
    }
  }
  
  /**
   * Functional interface for operations that can throw IOException.
   *
   * @param <R> the type of the result
   */
  @FunctionalInterface
  private interface IOSupplier<R> {
    R get() throws IOException;
  }
}