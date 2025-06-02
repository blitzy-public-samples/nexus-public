package org.sonatype.nexus.security.authc;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import jakarta.inject.Named;
import jakarta.inject.Singleton;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authz.AuthorizationException;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.subject.support.DefaultSubjectContext;
import org.apache.shiro.web.filter.authc.BasicHttpAuthenticationFilter;
import org.apache.shiro.web.util.WebUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.sonatype.nexus.security.SecurityFilter.ATTR_USER_ID;
import static org.sonatype.nexus.security.SecurityFilter.ATTR_USER_PRINCIPAL;

/**
 * Nexus security filter providing HTTP BASIC authentication support.
 *
 * Knows about special handling needed for anonymous subjects.
 *
 * Does not create sessions.
 *
 * Optimized for Java 21 with Virtual Threads for improved performance and scalability.
 *
 * @since 3.0
 */
@Named
@Singleton
public class NexusBasicHttpAuthenticationFilter extends BasicHttpAuthenticationFilter {

  public static final String NAME = "nx-basic-authc";
  public static final String BASIC_AUTH_REALM = "Sonatype Nexus Repository Manager";

  // Base64 for ":" for anonymous access
  private static final String EMPTY_CREDENTIALS = "Og==";

  private final Executor virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  private final Logger log = LoggerFactory.getLogger(getClass());

  public NexusBasicHttpAuthenticationFilter() {
    setApplicationName(BASIC_AUTH_REALM);
  }

  @Override
  protected boolean isPermissive(final Object mappedValue) {
    return true;
  }

  @Override
  public boolean onPreHandle(final ServletRequest request, final ServletResponse response, final Object mappedValue)
          throws Exception {
    request.setAttribute(DefaultSubjectContext.SESSION_CREATION_ENABLED, Boolean.FALSE);
    return super.onPreHandle(request, response, mappedValue);
  }

  @Override
  protected void cleanup(final ServletRequest request, final ServletResponse response, Exception failure)
          throws ServletException, IOException {
    if (failure instanceof ServletException se && se.getCause() instanceof AuthorizationException) {
      handleAuthorizationException(request, response);
      failure = null;
    } else if (failure instanceof AuthorizationException) {
      handleAuthorizationException(request, response);
      failure = null;
    }
    super.cleanup(request, response, failure);
  }

  private void handleAuthorizationException(ServletRequest request, ServletResponse response)
          throws IOException, ServletException {
    Subject subject = getSubject(request, response);
    boolean authenticated = subject.getPrincipal() != null && subject.isAuthenticated();

    if (authenticated) {
      log.debug("User {} is authenticated but not authorized for the requested resource", subject.getPrincipal());
      WebUtils.toHttp(response).sendError(HttpServletResponse.SC_FORBIDDEN);
    } else {
      log.debug("Unauthenticated access attempt to protected resource");
      try {
        onAccessDenied(request, response);
      } catch (Exception e) {
        log.error("Error during access denied handling: {}", e.getMessage(), e);
        throw new ServletException("Error during access denied handling", e);
      }
    }
  }

  @Override
  protected boolean onLoginSuccess(AuthenticationToken token, Subject subject,
                                   ServletRequest request, ServletResponse response)
          throws Exception {
    if (request instanceof HttpServletRequest) {
      Object principal = subject.getPrincipal();
      if (principal == null) {
        principal = token.getPrincipal();
      }
      String userId = principal.toString();
      request.setAttribute(ATTR_USER_PRINCIPAL, principal);
      request.setAttribute(ATTR_USER_ID, userId);
      log.debug("Successful authentication for user: {}", userId);
    }
    return super.onLoginSuccess(token, subject, request, response);
  }

  @Override
  protected boolean isLoginAttempt(final String authzHeader) {
    return !isEmptyCredentials(authzHeader) && super.isLoginAttempt(authzHeader);
  }

  private boolean isEmptyCredentials(final String authzHeader) {
    if (authzHeader == null || !authzHeader.toLowerCase().contains("basic ")) {
      return false;
    }
    String[] parts = authzHeader.split(" ");
    return parts.length > 1 && parts[1].equals(EMPTY_CREDENTIALS);
  }
}
