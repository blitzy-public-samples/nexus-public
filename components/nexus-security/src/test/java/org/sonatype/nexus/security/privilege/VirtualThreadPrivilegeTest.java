package org.sonatype.nexus.security.privilege;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;

import org.apache.shiro.authz.Permission;
import org.apache.shiro.authz.permission.PermissionResolver;
import org.apache.shiro.authz.permission.WildcardPermissionResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

public class VirtualThreadPrivilegeTest extends TestSupport {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(VirtualThreadPrivilegeTest.class);
  private final PermissionResolver permissionResolver = new WildcardPermissionResolver();

  public static class ApplicationPermission implements Permission {
    private final String domain;
    private final String action;
    private final String target;

    public ApplicationPermission(String domain, String action, String target) {
      this.domain = domain;
      this.action = action;
      this.target = target;
    }

    @Override
    public boolean implies(Permission permission) {
      if (!(permission instanceof ApplicationPermission)) {
        return false;
      }
      ApplicationPermission other = (ApplicationPermission) permission;
      return this.domain.equals(other.domain) && this.action.equals(other.action);
    }
  }

  @Test
  public void testBasicPermissionImplicationWithVirtualThread() throws Exception {
    Thread.startVirtualThread(() -> {
      Permission authorizedPermission = new ApplicationPermission("feature", "action", "anotherAction");
      Permission requiredPermission = permissionResolver.resolvePermission("nexus:feature:action");

      assertThat(authorizedPermission.implies(requiredPermission), is(true));
    }).join();
  }

  @Test
  @Timeout(value = 30)
  public void testConcurrentPermissionEvaluationWithVirtualThreads() throws Exception {
    int threadCount = 1000;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            Permission authorizedPermission = new ApplicationPermission("feature" + index, "action", "anotherAction");
            Permission requiredPermission = permissionResolver.resolvePermission("nexus:feature" + index + ":action");

            if (authorizedPermission.implies(requiredPermission)) {
              successCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }

      latch.await(20, TimeUnit.SECONDS);
      assertThat(successCount.get(), is(threadCount));
    }
  }

  @Test
  public void testComplexPermissionEvaluationWithVirtualThreads() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();

      for (int i = 0; i < 100; i++) {
        futures.add(executor.submit(() -> {
          Permission authorizedPermission = new ApplicationPermission("feature:method", "action", "anotherAction");
          Permission requiredPermission = permissionResolver.resolvePermission("nexus:feature:method:action");

          assertThat(authorizedPermission.implies(requiredPermission), is(true));

          Permission wildcardPermission = new ApplicationPermission("feature:*", "action", "anotherAction");
          Permission specificPermission = permissionResolver.resolvePermission("nexus:feature:specific:action");

          assertThat(wildcardPermission.implies(specificPermission), is(true));
        }));
      }

      for (Future<?> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  public void testPerformanceComparisonBetweenPlatformAndVirtualThreads() throws Exception {
    int threadCount = 500;

    long platformThreadTime = measureExecutionTime(() -> {
      try (ExecutorService executor = Executors.newFixedThreadPool(50)) {
        runConcurrentPermissionChecks(executor, threadCount);
      }
    });

    long virtualThreadTime = measureExecutionTime(() -> {
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        runConcurrentPermissionChecks(executor, threadCount);
      }
    });

    log.info("Platform thread execution time: {} ms", platformThreadTime);
    log.info("Virtual thread execution time: {} ms", virtualThreadTime);
  }

  @Test
  @Timeout(value = 60)
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    int threadCount = 10_000;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            Permission authorizedPermission = new ApplicationPermission("feature", "action", "anotherAction");
            Permission requiredPermission = permissionResolver.resolvePermission("nexus:feature:action");

            if (authorizedPermission.implies(requiredPermission)) {
              successCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }

      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertThat("All threads should complete in time", completed, is(true));
      assertThat(successCount.get(), is(threadCount));
    }
  }

  @Test
  @Timeout(value = 30)
  public void testPermissionResolutionUnderLoadWithDelay() throws Exception {
    int threadCount = 200;
    CountDownLatch latch = new CountDownLatch(threadCount);

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            Thread.sleep(50);

            Permission authorizedPermission = new ApplicationPermission("feature", "action", "anotherAction");
            Permission requiredPermission = permissionResolver.resolvePermission("nexus:feature:action");

            assertThat(authorizedPermission.implies(requiredPermission), is(true));
            Thread.sleep(50);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            latch.countDown();
          }
        });
      }

      long startTime = System.currentTimeMillis();
      latch.await(10, TimeUnit.SECONDS);
      long duration = System.currentTimeMillis() - startTime;

      long sequentialTime = threadCount * 100L;
      log.info("Concurrent execution time: {} ms, Sequential would be: {} ms", duration, sequentialTime);
      assertThat(duration, lessThan(sequentialTime / 2));
    }
  }

  private void runConcurrentPermissionChecks(ExecutorService executor, int threadCount) {
    CountDownLatch latch = new CountDownLatch(threadCount);
    List<Future<?>> futures = new ArrayList<>();

    for (int i = 0; i < threadCount; i++) {
      futures.add(executor.submit(() -> {
        try {
          Permission authorizedPermission = new ApplicationPermission("feature", "action", "anotherAction");
          Permission requiredPermission = permissionResolver.resolvePermission("nexus:feature:action");

          assertThat(authorizedPermission.implies(requiredPermission), is(true));
        } finally {
          latch.countDown();
        }
      }));
    }

      try {
          latch.await(20, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
          throw new RuntimeException(e);
      }
      for (Future<?> future : futures) {
      try {
        future.get(1, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        // Optionally log or handle interruption
      } catch (ExecutionException | TimeoutException e) {
        // Optionally log or handle execution/timeout
        throw new RuntimeException(e);
      }
    }
  }

  private long measureExecutionTime(Runnable runnable) throws Exception {
    long startTime = System.currentTimeMillis();
    runnable.run();
    return System.currentTimeMillis() - startTime;
  }
}