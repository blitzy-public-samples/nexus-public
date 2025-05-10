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
package org.sonatype.nexus.scheduling.internal.resources.datastore;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

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
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.rest.Page;
import org.sonatype.nexus.rest.Resource;
import org.sonatype.nexus.scheduling.TaskInfo;
import org.sonatype.nexus.scheduling.TaskScheduler;
import org.sonatype.nexus.scheduling.TaskState;
import org.sonatype.nexus.scheduling.api.TaskXO;
import org.sonatype.nexus.scheduling.internal.resources.doc.TasksApiResourceDoc;
import org.sonatype.nexus.thread.SubjectAwareVirtualThreadExecutorService;
import org.sonatype.nexus.thread.io.ThreadPinningDetector;

import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.Response.Status.CONFLICT;
import static javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
import static org.sonatype.nexus.rest.APIConstants.V1_API_PREFIX;

/**
 * REST resource for task management operations.
 * 
 * @since 3.6
 */
@Named
@Singleton
@Path(TasksResource.RESOURCE_URI)
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
public class TasksResource
    extends ComponentSupport
    implements Resource, TasksApiResourceDoc
{
  public static final String RESOURCE_URI = V1_API_PREFIX + "/tasks";

  private static final String TRIGGER_SOURCE = "REST API";

  private final TaskScheduler taskScheduler;
  private final ThreadPinningDetector threadPinningDetector;
  private final ExecutorService executorService;

  /**
   * Constructor with required dependencies.
   *
   * @param taskScheduler the task scheduler service
   * @param threadPinningDetector utility to detect and prevent virtual thread pinning
   */
  @Inject
  public TasksResource(
      final TaskScheduler taskScheduler,
      final ThreadPinningDetector threadPinningDetector)
  {
    this.taskScheduler = checkNotNull(taskScheduler);
    this.threadPinningDetector = checkNotNull(threadPinningDetector);
    this.executorService = SubjectAwareVirtualThreadExecutorService.forCurrentSubject();
  }

  @Override
  @GET
  @RequiresAuthentication
  @RequiresPermissions("nexus:tasks:read")
  public Page<TaskXO> getTasks(@QueryParam("type") final String type) {
    // Use the thread pinning detector to ensure this operation doesn't pin virtual threads
    return threadPinningDetector.detectPinningWithResult(() -> {
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
    // Use the thread pinning detector to ensure this operation doesn't pin virtual threads
    return threadPinningDetector.detectPinningWithResult(() -> {
      TaskInfo task = getTaskInfo(id);
      return TaskXO.fromTaskInfo(task, taskScheduler.toExternalTaskState(task));
    });
  }

  @Override
  @POST
  @Path("/{id}/run")
  @RequiresAuthentication
  @RequiresPermissions("nexus:tasks:start")
  public void run(@PathParam("id") final String id, @Suspended final AsyncResponse asyncResponse) {
    // Execute task operations asynchronously using virtual threads
    CompletableFuture.runAsync(() -> {
      try {
        // Use the thread pinning detector to ensure this operation doesn't pin virtual threads
        threadPinningDetector.detectPinning(() -> {
          TaskInfo taskInfo = getTaskInfo(id);

          if (!taskInfo.getConfiguration().isEnabled()) {
            throw new NotAllowedException(format("Task %s is disabled", id));
          }

          taskInfo.runNow(TRIGGER_SOURCE);
        });
        
        // Complete the async response successfully
        asyncResponse.resume((Object) null);
      }
      catch (NotFoundException | NotAllowedException e) {
        // Resume with specific exceptions for proper HTTP status codes
        asyncResponse.resume(e);
      }
      catch (Exception e) {
        log.error("Error running task with id {}", id, e);
        // Check for virtual thread interruption
        if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
          log.warn("Task operation interrupted for task {}", id);
          asyncResponse.resume(new WebApplicationException(format("Task operation interrupted for task %s", id), INTERNAL_SERVER_ERROR));
        } else {
          asyncResponse.resume(new WebApplicationException(format("Error running task %s", id), INTERNAL_SERVER_ERROR));
        }
      }
    }, executorService);
  }

  @Override
  @POST
  @Path("/{id}/stop")
  @RequiresAuthentication
  @RequiresPermissions("nexus:tasks:stop")
  public void stop(@PathParam("id") final String id, @Suspended final AsyncResponse asyncResponse) {
    // Execute task operations asynchronously using virtual threads
    CompletableFuture.runAsync(() -> {
      try {
        // Use the thread pinning detector to ensure this operation doesn't pin virtual threads
        threadPinningDetector.detectPinning(() -> {
          TaskInfo taskInfo = getTaskInfo(id);
          TaskState currentState = taskScheduler.toExternalTaskState(taskInfo).getState();

          boolean running = Optional.ofNullable(currentState).map(TaskState::isRunning).orElse(false);
          if (running) {
            boolean cancelled = taskScheduler.cancel(id, false);
            log.debug("Cancel {} for task {}", cancelled, id);
          }
          else {
            throw new WebApplicationException(format("Task %s is not running", id), CONFLICT);
          }
        });
        
        // Complete the async response successfully
        asyncResponse.resume((Object) null);
      }
      catch (WebApplicationException webApplicationException) {
        // Resume with specific exceptions for proper HTTP status codes
        asyncResponse.resume(webApplicationException);
      }
      catch (Exception e) {
        log.error("Error stopping task with id {}", id, e);
        // Check for virtual thread interruption
        if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
          log.warn("Task operation interrupted for task {}", id);
          asyncResponse.resume(new WebApplicationException(format("Task operation interrupted for task %s", id), INTERNAL_SERVER_ERROR));
        } else {
          asyncResponse.resume(new WebApplicationException(format("Error stopping task %s", id), INTERNAL_SERVER_ERROR));
        }
      }
    }, executorService);
  }

  /**
   * Retrieves task information by ID, ensuring the task is visible.
   *
   * @param id the task ID to retrieve
   * @return the task information
   * @throws NotFoundException if the task cannot be found or is not visible
   */
  private TaskInfo getTaskInfo(final String id) {
    return ofNullable(taskScheduler.getTaskById(id))
        .filter(taskInfo -> taskInfo.getConfiguration().isVisible())
        .orElseThrow(() -> new NotFoundException("Unable to locate task with id " + id));
  }

  /**
   * Checks if the task matches the specified type filter.
   *
   * @param type the type filter (can be null or empty for no filtering)
   * @param taskInfo the task to check
   * @return true if the task matches the type filter
   */
  private static boolean typeParameterMatches(final String type, final TaskInfo taskInfo) {
    return type == null || type.isEmpty() || type.equals(taskInfo.getTypeId());
  }
}