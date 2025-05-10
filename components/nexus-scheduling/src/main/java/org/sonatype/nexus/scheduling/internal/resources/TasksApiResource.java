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
package org.sonatype.nexus.scheduling.internal.resources;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.NotAllowedException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.app.FeatureFlag;
import org.sonatype.nexus.rest.Page;
import org.sonatype.nexus.rest.Resource;
import org.sonatype.nexus.scheduling.TaskInfo;
import org.sonatype.nexus.scheduling.TaskScheduler;
import org.sonatype.nexus.scheduling.api.TaskXO;
import org.sonatype.nexus.scheduling.internal.resources.doc.TasksApiResourceDoc;
import org.sonatype.nexus.thread.ThreadHelper;
import org.sonatype.nexus.thread.io.ThreadPinningDetector;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.apache.shiro.subject.Subject;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.Response.Status.CONFLICT;
import static javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
import static org.sonatype.nexus.common.app.FeatureFlags.DATASTORE_CLUSTERED_ENABLED;
import static org.sonatype.nexus.rest.APIConstants.V1_API_PREFIX;

/**
 * REST resource for managing Nexus tasks.
 *
 * @since 3.6
 */
@FeatureFlag(name = DATASTORE_CLUSTERED_ENABLED, inverse = true, enabledByDefault = true)
@Named
@Singleton
@Path(TasksApiResource.RESOURCE_URI)
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
public class TasksApiResource
    extends ComponentSupport
    implements Resource, TasksApiResourceDoc
{
  public static final String RESOURCE_URI = V1_API_PREFIX + "/tasks";

  private static final String TRIGGER_SOURCE = "REST API";
  
  // Virtual thread executor for handling REST operations
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  
  // Thread pinning detector to prevent carrier thread blocking
  private final ThreadPinningDetector threadPinningDetector;

  private final TaskScheduler taskScheduler;

  @Inject
  public TasksApiResource(final TaskScheduler taskScheduler, final ThreadPinningDetector threadPinningDetector) {
    this.taskScheduler = checkNotNull(taskScheduler);
    this.threadPinningDetector = checkNotNull(threadPinningDetector);
  }

  @Override
  @GET
  @RequiresAuthentication
  @RequiresPermissions("nexus:tasks:read")
  public Page<TaskXO> getTasks(@QueryParam("type") final String type) {
    // Use ThreadPinningDetector to ensure we don't block carrier threads
    threadPinningDetector.detectPinning(() -> {
      log.debug(STR."Getting tasks with type filter: \{type == null ? "all" : type}");
    });
    
    // Capture the current security subject for propagation
    Subject subject = SecurityUtils.getSubject();
    
    // Use virtual threads for improved concurrency
    return ThreadHelper.withSubject(subject, () -> {
      List<TaskXO> taskXOs = taskScheduler.listsTasks().stream()
          .filter(taskInfo -> taskInfo.getConfiguration().isVisible())
          .filter(taskInfo -> typeParameterMatches(type, taskInfo))
          .map(taskInfo -> TaskXO.fromTaskInfo(taskInfo, taskScheduler.toExternalTaskState(taskInfo)))
          .collect(toList());

      return new Page<>(taskXOs, null);
    });
  }

  @Override
  @GET
  @Path("/{id}")
  @RequiresAuthentication
  @RequiresPermissions("nexus:tasks:read")
  public TaskXO getTaskById(@PathParam("id") final String id) {
    // Use ThreadPinningDetector to ensure we don't block carrier threads
    threadPinningDetector.detectPinning(() -> {
      log.debug(STR."Getting task with id: \{id}");
    });
    
    // Capture the current security subject for propagation
    Subject subject = SecurityUtils.getSubject();
    
    // Use virtual threads for improved concurrency
    return ThreadHelper.withSubject(subject, () -> {
      TaskInfo task = getTaskInfo(id);
      return TaskXO.fromTaskInfo(task, taskScheduler.toExternalTaskState(task));
    });
  }

  @Override
  @POST
  @Path("/{id}/run")
  @RequiresAuthentication
  @RequiresPermissions("nexus:tasks:start")
  public void run(@PathParam("id") final String id) {
    // Use ThreadPinningDetector to ensure we don't block carrier threads
    threadPinningDetector.detectPinning(() -> {
      log.debug(STR."Running task with id: \{id}");
    });
    
    // Capture the current security subject for propagation
    Subject subject = SecurityUtils.getSubject();
    
    // Use virtual threads for improved concurrency
    try {
      ThreadHelper.withSubject(subject, () -> {
        TaskInfo taskInfo = getTaskInfo(id);

        if (!taskInfo.getConfiguration().isEnabled()) {
          throw new NotAllowedException(STR."Task \{id} is disabled");
        }

        taskInfo.runNow(TRIGGER_SOURCE);
        return null; // Required for lambda compatibility
      });
    }
    catch (NotFoundException | NotAllowedException e) {
      throw e;
    }
    catch (Exception e) {
      log.error(STR."Error running task with id \{id}", e);
      throw new WebApplicationException(STR."Error running task \{id}", INTERNAL_SERVER_ERROR);
    }
  }

  @Override
  @POST
  @Path("/{id}/stop")
  @RequiresAuthentication
  @RequiresPermissions("nexus:tasks:stop")
  public void stop(@PathParam("id") final String id) {
    // Use ThreadPinningDetector to ensure we don't block carrier threads
    threadPinningDetector.detectPinning(() -> {
      log.debug(STR."Stopping task with id: \{id}");
    });
    
    // Capture the current security subject for propagation
    Subject subject = SecurityUtils.getSubject();
    
    // Use virtual threads for improved concurrency
    try {
      ThreadHelper.withSubject(subject, () -> {
        TaskInfo taskInfo = getTaskInfo(id);
        Future<?> taskFuture = taskInfo.getCurrentState().getFuture();
        if (taskFuture == null) {
          throw new WebApplicationException(STR."Task \{id} is not running", CONFLICT);
        }
        if (!taskFuture.cancel(false)) {
          throw new WebApplicationException(STR."Unable to stop task \{id}", CONFLICT);
        }
        return null; // Required for lambda compatibility
      });
    }
    catch (WebApplicationException webApplicationException) {
      throw webApplicationException;
    }
    catch (Exception e) {
      log.error(STR."Error stopping task with id \{id}", e);
      throw new WebApplicationException(STR."Error stopping task \{id}", INTERNAL_SERVER_ERROR);
    }
  }

  private TaskInfo getTaskInfo(final String id) {
    return ofNullable(taskScheduler.getTaskById(id))
        .filter(taskInfo -> taskInfo.getConfiguration().isVisible())
        .orElseThrow(() -> new NotFoundException(STR."Unable to locate task with id \{id}"));
  }

  private static boolean typeParameterMatches(final String type, final TaskInfo taskInfo) {
    return type == null || type.isEmpty() || type.equals(taskInfo.getTypeId());
  }
}