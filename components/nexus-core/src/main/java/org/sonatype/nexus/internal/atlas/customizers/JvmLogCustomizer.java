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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.log.LogManager;
import org.sonatype.nexus.supportzip.GeneratedContentSourceSupport;
import org.sonatype.nexus.supportzip.SupportBundle;
import org.sonatype.nexus.supportzip.SupportBundleCustomizer;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;
import static org.sonatype.nexus.common.text.Strings2.MASK;
import static org.sonatype.nexus.supportzip.SupportBundle.ContentSource.Priority.LOW;
import static org.sonatype.nexus.supportzip.SupportBundle.ContentSource.Type.LOG;

/**
 * Adds jvm log file to support bundle.
 * Masks sensitive data passed as JVM arguments.
 */
@Named
@Singleton
public class JvmLogCustomizer
    extends ComponentSupport
    implements SupportBundleCustomizer
{
  private static final List<String> SENSITIVE_FIELD_NAMES =
      Arrays.asList("password", "secret", "token", "sign", "auth", "cred", "key", "pass");

  private final LogManager logManager;

  @Inject
  public JvmLogCustomizer(final LogManager logManager) {
    this.logManager = checkNotNull(logManager);
  }

  @Override
  public void customize(final SupportBundle supportBundle) {
    supportBundle.add(new GeneratedContentSourceSupport(LOG, "log/jvm.log", LOW)
    {
      @Override
      protected void generate(final File file) {
        File logFile = logManager.getLogFile("jvm.log");

        if (logFile != null) {
          try {
            // Use virtual threads for file I/O operations
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
              executor.submit(() -> processLogFile(logFile.toPath(), file.toPath())).get();
            }
          } catch (Exception e) {
            log.debug(STR."Unable to include jvm.log file: \{e.getMessage()}");
          }
        }
        else {
          log.debug("Not including missing jvm.log file");
        }
      }

      private void processLogFile(Path sourcePath, Path targetPath) {
        try {
          // Use NIO Files API with buffered streams for better performance
          try (Stream<String> lines = Files.lines(sourcePath)) {
            List<String> processedLines = lines
                .map(this::maybeMaskSensitiveData)
                .toList();
            
            Files.write(targetPath, processedLines);
          }
        } catch (IOException e) {
          log.debug(STR."Error processing log file: \{e.getMessage()}");
        }
      }

      private String maybeMaskSensitiveData(final String input) {
        // Use pattern matching for switch to enhance sensitive data masking
        return switch (input) {
          // Special case: empty line
          case String s when s.isEmpty() -> s;
          
          // Process lines with sensitive data
          case String s -> {
            String result = s;
            for (String fieldName : SENSITIVE_FIELD_NAMES) {
              // Check if the line contains the sensitive field
              switch (fieldName) {
                case String field when result.contains(field + "=") -> 
                  result = result.replaceAll(field + "=\\S*", field + "=" + MASK);
                default -> { /* No action needed */ }
              }
            }
            yield result;
          }
        };
      }
    });
  }
}