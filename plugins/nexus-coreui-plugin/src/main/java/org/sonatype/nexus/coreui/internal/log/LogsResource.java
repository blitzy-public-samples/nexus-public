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
package org.sonatype.nexus.coreui.internal.log;

import java.io.IOException;
import java.io.InputStream;
import java.lang.StringTemplate;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Set;
import java.util.stream.Stream;
// Import for Java 21 String Templates is included for clarity
import static java.lang.StringTemplate.STR;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.log.LogManager;
import org.sonatype.nexus.logging.task.TaskLogHome;
import org.sonatype.nexus.rest.APIConstants;
import org.sonatype.nexus.rest.Resource;

import org.apache.shiro.authz.annotation.RequiresPermissions;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.net.HttpHeaders.CONTENT_DISPOSITION;
import static java.util.stream.Collectors.toSet;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.MediaType.TEXT_PLAIN;

/**
 * Logs REST resource.
 * 
 * Updated for Java 21 to use String Templates for improved readability and type safety.
 *
 * @since 3.3
 */
@Named
@Singleton
@Path(LogsResource.RESOURCE_URI)
public class LogsResource
    extends ComponentSupport
    implements Resource
{
  public static final String RESOURCE_URI = APIConstants.INTERNAL_API_PREFIX + "/logging/logs";

  public static final String DEFAULT_MARK = "MARK";

  private final LogManager logManager;

  @Inject
  public LogsResource(final LogManager logManager) {
    this.logManager = checkNotNull(logManager);
  }

  /**
   * List the log files known by the system.
   * 
   * This method collects log files from the main log directory and additional log homes
   * if they exist, using Java streams for efficient processing.
   * 
   * @return A set of LogXO objects representing the available log files
   * @throws IOException If an I/O error occurs while accessing log files
   */
  @GET
  @Produces({APPLICATION_JSON})
  @RequiresPermissions("nexus:logging:read")
  public Set<LogXO> listLogs() throws IOException {

    Set<LogXO> logs = logManager.getLogFiles().stream().map(file -> new LogXO(file.toPath())).collect(toSet());

    if (TaskLogHome.getTaskLogsHome() != null) {
      aggregateLogs(logs, TaskLogHome.getTaskLogsHome());
    }

    if (TaskLogHome.getReplicationLogsHome().isPresent()) {
      aggregateLogs(logs, TaskLogHome.getReplicationLogsHome().get());
    }

    return logs;
  }

  /**
   * Aggregates logs from the specified pathname into the provided logs set.
   * Uses method reference for filtering and a lambda for adding to the set.
   *
   * @param logs The set of logs to aggregate into
   * @param pathname The path to search for log files
   * @throws IOException If an I/O error occurs
   */
  private void aggregateLogs(final Set<LogXO> logs, final String pathname) throws IOException {
    if (pathname != null) {
      try (Stream<java.nio.file.Path> paths = Files.list(Paths.get(pathname))) {
        paths.filter(logManager::isValidLogFile).forEach(path -> logs.add(new LogXO(path)));
      }
    }
  }

  /**
   * Downloads a part of a log file or the complete log file if fromByte/bytesCount are null.
   * 
   * This method uses Java 21 String Templates for error messages and response headers.
   */
  @GET
  @Path("/{filename: .*\\.log}")
  @Produces({TEXT_PLAIN})
  @RequiresPermissions("nexus:logging:read")
  public Response get(
      @PathParam("filename") final String filename,
      @QueryParam("fromByte") final Long fromByte,
      @QueryParam("bytesCount") final Long bytesCount)
      throws NotFoundException, IOException
  {
    // Using pattern-like approach for parameter validation and defaults
    // This is more readable than nested if-else statements
    Long from = switch(fromByte) {
      case null, Long l when l < 0 -> 0L;
      default -> fromByte;
    };
    
    Long count = bytesCount == null ? Long.MAX_VALUE : bytesCount;
    InputStream log = logManager.getLogFileStream(filename, from, count);
    if (log == null) {
      // Using Java 21 String Templates for improved readability and type safety
      throw new NotFoundException(STR."\{filename} not found");
    }
    return Response.ok(log)
        .header(CONTENT_DISPOSITION, STR."attachment; filename=\"\{filename}\"")
        .build();
  }
}