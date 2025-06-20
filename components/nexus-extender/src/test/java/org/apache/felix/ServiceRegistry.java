package org.apache.felix;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;

public class ServiceRegistry {
    private final Map<ServiceReference<?>, Object> services = new ConcurrentHashMap<>();

    public ServiceRegistry(Bundle bundle) {
    }

    public ServiceRegistration<?> registerService(BundleContext context, String[] clazzes, Object service, Dictionary<String, ?> props) {
        ServiceReference<?> reference = mock(ServiceReference.class);
        when(reference.getProperty("service.name")).thenReturn(props.get("service.name"));
        when(reference.getProperty("service.priority")).thenReturn(props.get("service.priority"));
        services.put(reference, service);

        return new ServiceRegistration<Object>() {
            public void unregister() { services.remove(reference); }
            public ServiceReference<Object> getReference() { return (ServiceReference<Object>) reference; }
            public void setProperties(Dictionary<String, ?> dict) {}
        };
    }

    public Object getService(BundleContext context, ServiceReference<?> reference) {
        return services.get(reference);
    }

    public ServiceReference<?>[] getServiceReferences(BundleContext context, String className, String filter) {
        return services.keySet().toArray(new ServiceReference[0]);
    }

    public ServiceReference<?> getServiceReference(BundleContext context, String className) {
        return services.keySet().iterator().next(); // naive implementation
    }

    public void addServiceListener(BundleContext context, ServiceListener listener, String filter) {}
    public void removeServiceListener(BundleContext context, ServiceListener listener) {}
    public void removeBundle(Bundle bundle) { services.clear(); }
}

