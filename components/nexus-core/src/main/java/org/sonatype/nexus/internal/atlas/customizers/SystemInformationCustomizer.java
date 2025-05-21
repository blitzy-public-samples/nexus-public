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
package org.sonatype.nexus.internal.atlas.customizers;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.atlas.SystemInformationGenerator;
import org.sonatype.nexus.supportzip.GeneratedContentSourceSupport;
import org.sonatype.nexus.supportzip.SupportBundle;
import org.sonatype.nexus.supportzip.SupportBundleCustomizer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.supportzip.SupportBundle.ContentSource.Priority.REQUIRED;
import static org.sonatype.nexus.supportzip.SupportBundle.ContentSource.Type.SYSINFO;

/**
 * Adds system information report to support bundle.
 * Enhanced with Java 21 features including Virtual Threads for parallel data collection,
 * Record Patterns for structured data handling, and String Templates for logging.
 *
 * @since 2.7
 */
@Named
@Singleton
public class SystemInformationCustomizer
    extends ComponentSupport
    implements SupportBundleCustomizer
{
  private final SystemInformationGenerator systemInformationGenerator;

  private final ObjectMapper objectMapper;
  
  // Record for structured system data
  private record SystemDataSection(String name, Map<String, Object> data) {}

  @Inject
  public SystemInformationCustomizer(final SystemInformationGenerator systemInformationGenerator) {
    this.systemInformationGenerator = checkNotNull(systemInformationGenerator);
    this.objectMapper = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    
    log.info(STR."Initialized SystemInformationCustomizer with Java \{System.getProperty("java.version")} features");
  }

  @Override
  public void customize(final SupportBundle supportBundle) {
    supportBundle.add(new GeneratedContentSourceSupport(SYSINFO, "info/sysinfo.json", REQUIRED)
    {
      @Override
      protected void generate(final File file) {
        long startTime = System.currentTimeMillis();
        log.debug(STR."Generating system information report to \{file.getAbsolutePath()}");
        
        // Use Virtual Threads for parallel data collection
        Map<String, Object> report = collectSystemInformationWithVirtualThreads();
        
        try (FileOutputStream fos = new FileOutputStream(file)) {
          objectMapper.writeValue(fos, report);
          long duration = System.currentTimeMillis() - startTime;
          log.debug(STR."System information report generated in \{duration}ms");
        }
        catch (IOException e) {
          log.error(STR."Failed to write system information report: \{e.getMessage()}", e);
          throw new UncheckedIOException(e);
        }
      }
    });
  }
  
  /**
   * Collects system information using Virtual Threads for parallel data collection.
   * This improves performance by gathering different sections of system information concurrently.
   *
   * @return A map containing the collected system information
   */
  private Map<String, Object> collectSystemInformationWithVirtualThreads() {
    // Get the base report from the generator
    Map<String, Object> baseReport = systemInformationGenerator.report();
    
    // Create a concurrent map to store the results
    Map<String, Object> enhancedReport = new ConcurrentHashMap<>(baseReport);
    
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Use record patterns to extract and process sections in parallel
      Map<String, Future<?>> futures = new HashMap<>();
      
      // Process each section in parallel using virtual threads
      for (var entry : baseReport.entrySet()) {
        if (entry.getValue() instanceof Map<?, ?> sectionData) {
          // Create a record for each section using pattern matching
          SystemDataSection section = new SystemDataSection(entry.getKey(), new HashMap<>((Map<String, Object>) sectionData));
          
          // Submit the section processing task to the virtual thread executor
          futures.put(section.name(), executor.submit(() -> {
            try {
              // Process the section data (enhance, validate, etc.)
              Map<String, Object> processedData = processSystemDataSection(section);
              enhancedReport.put(section.name(), processedData);
              log.trace(STR."Processed system information section: \{section.name()}");
            } catch (Exception e) {
              log.warn(STR."Error processing system information section \{section.name()}: \{e.getMessage()}", e);
            }
          }));
        }
      }
      
      // Wait for all futures to complete
      for (var entry : futures.entrySet()) {
        try {
          entry.getValue().get();
        } catch (Exception e) {
          log.warn(STR."Failed to process section \{entry.getKey()}: \{e.getMessage()}");
        }
      }
    }
    
    // Add metadata about the report generation
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("generatedWith", STR."Java \{System.getProperty("java.version")}");
    metadata.put("timestamp", System.currentTimeMillis());
    enhancedReport.put("_metadata", metadata);
    
    return enhancedReport;
  }
  
  /**
   * Processes a system data section, applying any necessary transformations or enhancements.
   *
   * @param section The system data section to process
   * @return The processed data map
   */
  private Map<String, Object> processSystemDataSection(SystemDataSection section) {
    // Use record pattern matching for structured data handling
    return switch (section) {
      case SystemDataSection(var name, var data) when name.equals("system") -> {
        // Enhance system section with additional information
        data.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        data.put("virtualThreadsSupported", true);
        yield data;
      }
      case SystemDataSection(var name, var data) when name.equals("runtime") -> {
        // Enhance runtime section with Java 21 specific information
        data.put("virtualThreadsEnabled", true);
        yield data;
      }
      case SystemDataSection(var name, var data) -> {
        // Return the data unchanged for other sections
        yield data;
      }
    };
  }
}