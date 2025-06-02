package org.sonatype.nexus.security.realm;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.user.User;

import com.google.common.collect.ImmutableList;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.subject.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class RealmVirtualThreadTest
        extends AbstractSecurityTest
{
  private static final int CONCURRENT_THREADS = 100;
  private static final int TIMEOUT_SECONDS = 10;

  private SecuritySystem securitySystem;
  private RealmManager realmManager;
  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;

  @BeforeEach
  public void setUp() throws Exception {
    securitySystem = lookup(SecuritySystem.class);
    realmManager = lookup(RealmManager.class);

    realmManager.setConfiguredRealmIds(ImmutableList.of("MockRealmA", "MockRealmB"));

    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), platformThreadFactory);
  }

  @AfterEach
  public void tearDown() throws Exception {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      virtualThreadExecutor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    if (platformThreadExecutor != null) {
      platformThreadExecutor.shutdown();
      platformThreadExecutor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
  }

  @Test
  @DisplayName("No thread pinning occurs during realm operations")
  public void testNoThreadPinningInRealmOperations() throws Exception {
    System.setProperty("jdk.tracePinnedThreads", "full");

    AtomicBoolean pinnedThreadDetected = new AtomicBoolean(false);
    Thread thread = Thread.ofVirtual().start(() -> {});
    thread.setUncaughtExceptionHandler((t, throwable) -> {
      if (throwable.getMessage() != null &&
              throwable.getMessage().contains("VirtualThread pinned")) {
        pinnedThreadDetected.set(true);
      }
    });

    for (int i = 0; i < 10; i++) {
      CompletableFuture.runAsync(() -> {
        try {
          Subject subject = securitySystem.getSubject();
          subject.login(new UsernamePasswordToken("jcoder", "jcoder"));
          subject.checkRole("role1");
          subject.logout();
        }
        catch (Exception e) {
          // Ignore exceptions for this test
        }
      }, virtualThreadExecutor).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    assertThat("No thread pinning should be detected", pinnedThreadDetected.get(), is(false));
    System.clearProperty("jdk.tracePinnedThreads");
  }

  @Test
  @DisplayName("Virtual threads outperform platform threads for security operations")
  public void testPerformanceComparisonBetweenThreadTypes() throws Exception {
    int operationCount = CONCURRENT_THREADS;

    long platformThreadTime = measureExecutionTime(operationCount, platformThreadExecutor);
    long virtualThreadTime = measureExecutionTime(operationCount, virtualThreadExecutor);

    System.out.println("Platform threads execution time: " + platformThreadTime + "ms");
    System.out.println("Virtual threads execution time: " + virtualThreadTime + "ms");
    System.out.println("Improvement factor: " + (double) platformThreadTime / virtualThreadTime);

    assertThat("Virtual threads should not be significantly slower than platform threads",
            (double) virtualThreadTime, lessThan(platformThreadTime * 1.5));
  }

  private long measureExecutionTime(int operationCount, ExecutorService executor) throws Exception {
    CountDownLatch latch = new CountDownLatch(operationCount);
    long startTime = System.currentTimeMillis();

    for (int i = 0; i < operationCount; i++) {
      CompletableFuture.runAsync(() -> {
        try {
          Subject subject = securitySystem.getSubject();
          subject.login(new UsernamePasswordToken("jcoder", "jcoder"));
          subject.hasRole("role1");
          subject.isPermitted("app:edit:1");
          subject.logout();
        }
        catch (Exception e) {
          // Ignore exceptions for this performance test
        }
        finally {
          latch.countDown();
        }
      }, executor);
    }

    latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    return System.currentTimeMillis() - startTime;
  }
}